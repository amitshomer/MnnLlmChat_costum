package com.alibaba.mnnllm.android.benchmark

import android.util.Log
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VisionAccuracyService {

    companion object {
        private const val TAG = "VisionAccuracyService"
        const val TESTSET_SUBDIR = "testset_all"
        const val LABELS_FILENAME = "testset_labels.json"

        // Same prompt as LookieVideoFragment.AI_TIPS_PROMPT
        private const val AI_TIPS_PROMPT =
            "You must output EXACTLY one line.\n" +
            "CAPTURE\n" +
            "IMPROVE: <short explanation>\n" +
            "Give me very short recommendation how to make image composition better just if needed.\n" +
            "Only output IMPROVE if the composition problem is obvious and severe.\n" +
            "Otherwise output CAPTURE.\n" +
            "When in doubt, always choose CAPTURE.\n"
    }

    data class ImageResult(
        val filename: String,
        val gt: String,       // "CAPTURE" or "IMPROVE"
        val pred: String,     // "CAPTURE" or "IMPROVE"
        val rawOutput: String // full raw model response
    )

    data class VisionTestResult(
        val stats: AccuracyStats,
        val outputFile: String?  // absolute path to saved JSON, null on save failure
    )

    data class AccuracyStats(
        val total: Int,
        val correct: Int,
        val captureTotal: Int,
        val captureCorrect: Int,
        val improveTotal: Int,
        val improveCorrect: Int
    ) {
        val accuracy: Float get() = if (total > 0) correct.toFloat() / total else 0f
        val captureAccuracy: Float get() = if (captureTotal > 0) captureCorrect.toFloat() / captureTotal else 0f
        val improveAccuracy: Float get() = if (improveTotal > 0) improveCorrect.toFloat() / improveTotal else 0f
    }

    @Volatile
    private var shouldStop = false

    fun stop() {
        shouldStop = true
    }

    private fun parseLabel(raw: String): String {
        val upper = raw.uppercase().trim()
        return when {
            upper.startsWith("CAPTURE") -> "CAPTURE"
            upper.startsWith("IMPROVE") -> "IMPROVE"
            upper.contains("CAPTURE") -> "CAPTURE"
            upper.contains("IMPROVE") -> "IMPROVE"
            else -> "CAPTURE" // default when ambiguous
        }
    }

    /**
     * Run the vision accuracy test.
     * Initializes the model via BenchmarkService, then runs all images in the testset.
     * @param testsetDir absolute path to the testset directory on the device
     * @param onProgress called on main thread with (current, total, currentFilename)
     * @return AccuracyStats or null on fatal error
     */
    suspend fun runTest(
        modelId: String,
        configPath: String?,
        backendType: String,
        testsetDir: String,
        limit: Int = Int.MAX_VALUE,
        onProgress: (current: Int, total: Int, filename: String) -> Unit
    ): VisionTestResult? = withContext(Dispatchers.IO) {
        shouldStop = false

        val labelsFile = File(testsetDir, LABELS_FILENAME)
        if (!labelsFile.exists()) {
            Log.e(TAG, "Labels file not found: ${labelsFile.absolutePath}")
            return@withContext null
        }

        val labelsJson = try {
            JSONObject(labelsFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse labels JSON", e)
            return@withContext null
        }

        val service = BenchmarkService.getInstance()
        val initSuccess = service.initializeModel(modelId, configPath, backendType)
        if (!initSuccess) {
            Log.e(TAG, "Failed to initialize model for vision test")
            return@withContext null
        }
        val session = service.getSession() ?: run {
            Log.e(TAG, "Session is null after successful init")
            return@withContext null
        }

        val allKeys = mutableListOf<String>()
        val iter = labelsJson.keys()
        while (iter.hasNext()) allKeys.add(iter.next())
        val keys = allKeys.take(limit)

        val total = keys.size
        var captureTotal = 0
        var captureCorrect = 0
        var improveTotal = 0
        var improveCorrect = 0
        var processed = 0
        val imageResults = mutableListOf<ImageResult>()

        for ((index, filename) in keys.withIndex()) {
            if (shouldStop) break

            val imageFile = File(testsetDir, filename)
            if (!imageFile.exists()) {
                Log.w(TAG, "Image not found, skipping: $filename")
                withContext(Dispatchers.Main) { onProgress(index + 1, total, filename) }
                continue
            }

            val gtEntry = labelsJson.optJSONObject(filename) ?: continue
            val gt = parseLabel(gtEntry.optString("final", "CAPTURE"))

            ImageUtils.compressImageFile(imageFile, maxDimension = 1024)

            val prompt = "<img>${imageFile.absolutePath}</img>$AI_TIPS_PROMPT"
            val output = StringBuilder()

            try {
                session.reset()
                session.generate(prompt, mapOf(), object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (progress != null) output.append(progress)
                        return shouldStop
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed for $filename", e)
                withContext(Dispatchers.Main) { onProgress(index + 1, total, filename) }
                continue
            }

            val rawOutput = output.toString().trim()
            val pred = parseLabel(rawOutput)
            val correct = gt == pred
            processed++
            imageResults.add(ImageResult(filename, gt, pred, rawOutput))

            when (gt) {
                "CAPTURE" -> { captureTotal++; if (correct) captureCorrect++ }
                "IMPROVE" -> { improveTotal++; if (correct) improveCorrect++ }
            }

            withContext(Dispatchers.Main) { onProgress(index + 1, total, filename) }
        }

        val stats = AccuracyStats(
            total = processed,
            correct = captureCorrect + improveCorrect,
            captureTotal = captureTotal,
            captureCorrect = captureCorrect,
            improveTotal = improveTotal,
            improveCorrect = improveCorrect
        )

        Log.i(TAG, "runTest completed: processed=$processed imageResults=${imageResults.size} testsetDir=$testsetDir")
        val outputFile = saveResultsJson(modelId, testsetDir, stats, imageResults)
        VisionTestResult(stats, outputFile)
    }

    private fun saveResultsJson(
        modelId: String,
        testsetDir: String,
        stats: AccuracyStats,
        imageResults: List<ImageResult>
    ): String? {
        return try {
            // Save to parent dir (app-owned) since testset_all may be shell-owned
            val saveDir = File(testsetDir).parentFile ?: File(testsetDir)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(saveDir, "results_$timestamp.json")
            Log.i(TAG, "saveResultsJson: saving to ${outFile.absolutePath}")

            val json = JSONObject().apply {
                put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                put("model", modelId)
                put("total", stats.total)
                put("correct", stats.correct)
                put("accuracy", stats.accuracy)
                put("capture_accuracy", stats.captureAccuracy)
                put("improve_accuracy", stats.improveAccuracy)
                put("results", JSONArray().also { arr ->
                    imageResults.forEach { r ->
                        arr.put(JSONObject().apply {
                            put("filename", r.filename)
                            put("gt", r.gt)
                            put("pred", r.pred)
                            put("raw_output", r.rawOutput)
                        })
                    }
                })
            }

            outFile.writeText(json.toString(2))
            Log.d(TAG, "Results saved to ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save results JSON", e)
            null
        }
    }
}
