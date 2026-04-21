// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat.lookie

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatPresenter
import com.alibaba.mnnllm.android.chat.GenerateResultProcessor
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.databinding.FragmentLookieVideoBinding
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LookieVideoFragment : Fragment(), ChatPresenter.GenerateListener {

    companion object {
        const val TAG = "LookieVideoFragment"
        private const val ARG_MODEL_NAME = "model_name"
        private const val ARG_MODEL_ID = "model_id"

        // Verbatim default prompt from PocketPal pocketpal-custom/src/screens/ChatScreen/VideoPalScreen.tsx:26
        private const val AI_TIPS_PROMPT =
            "You must output EXACTLY one line.\n" +
            "Choose one:\n" +
            "CAPTURE\n" +
            "IMPROVE: <short explanation>\n" +
            "Give me very short recommendation how to make image composition better just if needed.\n" +
            "Only output IMPROVE if the composition problem is obvious and severe.\n" +
            "Otherwise output CAPTURE.\n" +
            "When in doubt, always choose CAPTURE.\n"

        fun newInstance(modelName: String, modelId: String, chatPresenter: ChatPresenter): LookieVideoFragment {
            val fragment = LookieVideoFragment()
            fragment.chatPresenter = chatPresenter
            val args = Bundle()
            args.putString(ARG_MODEL_NAME, modelName)
            args.putString(ARG_MODEL_ID, modelId)
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FragmentLookieVideoBinding? = null
    private val binding get() = _binding!!


    private lateinit var chatPresenter: ChatPresenter
    private var modelName: String = ""
    private var modelId: String = ""

    private val shutterSound = MediaActionSound()

    // Camera
    private var imageCapture: ImageCapture? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraExecutor: ExecutorService? = null
    private var currentCamera: Camera? = null
    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null

    // Gallery — captured photos in this session
    private val capturedUris = mutableListOf<Uri>()

    // TTFT state
    private var sendTime: Long = 0L
    private var modelStartTime: Long = 0L
    private var ttft: Long = -1L
    private var isInferencing = false
    private var lookieTokenCount = 0
    private val lookieMaxTokens = 80
    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedRunnable = object : Runnable {
        override fun run() {
            if (_binding == null) return
            if (sendTime > 0 && ttft < 0) {
                val elapsed = (System.currentTimeMillis() - sendTime) / 1000f
                binding.tvTtftBadge.text = getString(R.string.lookie_ttft_elapsed, elapsed)
                binding.tvTtftBadge.visibility = View.VISIBLE
            }
            if (isInferencing) elapsedHandler.postDelayed(this, 100)
        }
    }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) startCamera()
            else Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            modelName = it.getString(ARG_MODEL_NAME, "")
            modelId = it.getString(ARG_MODEL_ID, "")
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLookieVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Prevent keyboard from appearing (ChatActivity has adjustResize which triggers on any Button focus)
        activity?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        activity?.window?.navigationBarColor = android.graphics.Color.BLACK
        // Ensure navigation bar buttons are white (light icons) on black background
        activity?.window?.let { win ->
            androidx.core.view.WindowInsetsControllerCompat(win, win.decorView)
                .isAppearanceLightNavigationBars = false
        }
        binding.toolbar.title = modelName
        binding.toolbar.setNavigationOnClickListener { activity?.supportFragmentManager?.popBackStack() }
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        setupClickListeners()
        checkAndRequestCameraPermission()
        loadLastGalleryThumbnail()
        warmupGpu()
    }

    private fun setupClickListeners() {
        binding.btnAiTips.setOnClickListener {
            if (isInferencing) chatPresenter.stopGenerate()
            else {
                sendTime = System.currentTimeMillis()
                flashShutter()
                setInferencing(true)
                captureAndAnalyze()
            }
        }
        binding.btnCapture.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
        binding.btnCapture.setOnClickListener {
            playQuietShutter()
            flashShutter()
            captureSnapshot()
        }
        binding.btnFlip.setOnClickListener { switchCamera() }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnDismissResponse.setOnClickListener {
            binding.responseOverlay.visibility = View.GONE
        }
        binding.btnZoom1.setOnClickListener { setZoom(1f) }
        binding.btnZoom2.setOnClickListener { setZoom(2f) }
        binding.btnZoom3.setOnClickListener { setZoom(3f) }
    }

    // --- Camera ---

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val cached = cameraProvider
        if (cached != null) {
            bindCamera(cached)
            return
        }
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            cameraProvider = future.get()
            bindCamera(cameraProvider!!)
        }, executor)
    }

    private fun buildImageCapture() = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private fun bindCamera(provider: androidx.camera.lifecycle.ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
        }
        imageCapture = buildImageCapture()
        try {
            provider.unbindAll()
            currentCamera = provider.bindToLifecycle(
                viewLifecycleOwner, currentCameraSelector, preview, imageCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    private fun switchCamera() {
        // Instant visual feedback: flip preview horizontally then switch
        binding.cameraPreview.animate()
            .scaleX(0f).setDuration(120)
            .withEndAction {
                currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA
                startCamera()
                binding.cameraPreview.animate().scaleX(1f).setDuration(120).start()
            }.start()
    }

    private fun setZoom(zoom: Float) {
        currentCamera?.cameraControl?.setZoomRatio(zoom)
        binding.btnZoom1.alpha = if (zoom == 1f) 1f else 0.5f
        binding.btnZoom2.alpha = if (zoom == 2f) 1f else 0.5f
        binding.btnZoom3.alpha = if (zoom == 3f) 1f else 0.5f
    }

    // --- Capture: snapshot to gallery only ---

    private fun captureSnapshot() {
        val ic = imageCapture ?: return
        val photoFile = newPhotoFile()
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            cameraExecutor!!,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) { Log.e(TAG, "Snapshot failed", exc) }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ImageUtils.compressImageFile(photoFile)
                    val galleryUri = saveToGallery(photoFile) ?: Uri.fromFile(photoFile)
                    capturedUris.add(0, galleryUri)
                    val sizePx = (52 * resources.displayMetrics.density).toInt()
                    val circular = loadCircularBitmap(galleryUri, sizePx)
                    activity?.runOnUiThread {
                        if (circular != null) binding.btnGallery.setImageBitmap(circular)
                        else binding.btnGallery.setImageURI(galleryUri)
                    }
                }
            }
        )
    }

    // --- Capture + AI analysis ---

    private fun captureAndAnalyze() {
        val ic = imageCapture ?: return
        val photoFile = newPhotoFile()
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            cameraExecutor!!,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Capture for analysis failed", exc)
                    activity?.runOnUiThread { setInferencing(false) }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ImageUtils.compressImageFile(photoFile, maxDimension = 1024)
                    ImageUtils.padToSquareInPlace(photoFile)
                    chatPresenter.getLlmSession()?.reset()
                    chatPresenter.getLlmSession()?.setKeepHistory(false)

                    val imageUri = Uri.fromFile(photoFile)
                    val time = SimpleDateFormat("hh:mm aa", Locale.getDefault()).format(System.currentTimeMillis())
                    val userData = ChatDataItem.createImageInputData(time, AI_TIPS_PROMPT, listOf(imageUri))

                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            chatPresenter.requestGenerate(userData, this@LookieVideoFragment)
                        } catch (e: Exception) {
                            Log.e(TAG, "requestGenerate failed", e)
                            withContext(Dispatchers.Main) { setInferencing(false) }
                        }
                    }
                }
            }
        )
    }

    private fun newPhotoFile(): File {
        val outputDir = File(requireContext().cacheDir, "lookie").also { it.mkdirs() }
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        return File(outputDir, "$name.jpg")
    }

    private fun warmupGpu() {
        binding.btnAiTips.isEnabled = false
        binding.btnAiTips.text = getString(R.string.lookie_warming_up)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Wait for the model to finish loading (up to 60s)
                var session = chatPresenter.getLlmSession()
                var waited = 0
                while ((session == null || !session.isModelLoaded()) && waited < 60_000) {
                    kotlinx.coroutines.delay(500)
                    waited += 500
                    session = chatPresenter.getLlmSession()
                }
                Log.d(TAG, "warmupGpu: model ready after ${waited}ms")
            } catch (e: Exception) {
                Log.w(TAG, "warmupGpu: error waiting for model", e)
            } finally {
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.btnAiTips.isEnabled = true
                        binding.btnAiTips.text = getString(R.string.lookie_ai_tips_button)
                    }
                }
            }
        }
    }

    private fun setInferencing(inferencing: Boolean) {
        isInferencing = inferencing
        binding.btnAiTips.text = getString(
            if (inferencing) R.string.lookie_stop_button else R.string.lookie_ai_tips_button
        )
        binding.progressThinking.visibility = if (inferencing) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !inferencing
        if (!inferencing) elapsedHandler.removeCallbacks(elapsedRunnable)
    }

    // --- Gallery ---

    private fun openGallery() {
        // Open the device gallery. If we have a captured photo, open at that photo
        // using setDataAndType so the gallery shows the full camera roll (not just our album).
        // Saving to DCIM/Camera ensures it appears in the main timeline.
        val lastUri = capturedUris.firstOrNull()
        val intent = if (lastUri != null) {
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(lastUri, "image/*")
                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = "image/*"
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.lookie_no_photos, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLastGalleryThumbnail() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            requireContext().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val sizePx = (52 * resources.displayMetrics.density).toInt()
                    val bmp = loadCircularBitmap(uri, sizePx)
                    withContext(Dispatchers.Main) {
                        if (_binding != null && capturedUris.isEmpty() && bmp != null) {
                            binding.btnGallery.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }

    /** Decode [uri] into a circle-cropped [sizePx]×[sizePx] bitmap. Call on a background thread. */
    private fun loadCircularBitmap(uri: Uri, sizePx: Int): android.graphics.Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            val sampleSize = (maxOf(opts.outWidth, opts.outHeight) / sizePx).coerceAtLeast(1)
            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val src = requireContext().contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null
            toCircleBitmap(src, sizePx)
        } catch (e: Exception) {
            Log.w(TAG, "loadCircularBitmap failed", e)
            null
        }
    }

    private fun toCircleBitmap(src: android.graphics.Bitmap, sizePx: Int): android.graphics.Bitmap {
        val cropSize = minOf(src.width, src.height)
        val output = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Draw circle mask
        canvas.drawOval(android.graphics.RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), paint)
        // Draw src clipped to the circle
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val scale = sizePx.toFloat() / cropSize
        val dx = -(src.width - cropSize) / 2f * scale
        val dy = -(src.height - cropSize) / 2f * scale
        val matrix = android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
        canvas.drawBitmap(src, matrix, paint)
        src.recycle()
        return output
    }

    private fun saveToGallery(file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = requireContext().contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().copyTo(out) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save to gallery", e)
            null
        }
    }

    private fun playQuietShutter() {
        try {
            val am = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val stream = android.media.AudioManager.STREAM_SYSTEM
            val saved = am.getStreamVolume(stream)
            val quiet = (am.getStreamMaxVolume(stream) * 0.25f).toInt().coerceAtLeast(1)
            am.setStreamVolume(stream, quiet, 0)
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            binding.root.postDelayed({ am.setStreamVolume(stream, saved, 0) }, 400)
        } catch (_: Exception) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun flashShutter() {
        binding.shutterFlash.visibility = View.VISIBLE
        binding.shutterFlash.alpha = 1f
        binding.shutterFlash.animate().alpha(0f).setDuration(150).withEndAction {
            binding.shutterFlash.visibility = View.INVISIBLE
        }.start()
    }

    // --- ChatPresenter.GenerateListener ---

    override fun onGenerateStart() {
        lookieTokenCount = 0
        modelStartTime = System.currentTimeMillis()
        activity?.runOnUiThread {
            ttft = -1L
            binding.responseOverlay.visibility = View.GONE
            binding.tvTtftBadge.visibility = View.GONE
            binding.tvModelTimeBadge.visibility = View.GONE
            elapsedHandler.post(elapsedRunnable)
        }
    }

    override fun onLlmGenerateProgress(progress: String?, generateResultProcessor: GenerateResultProcessor) {
        if (progress != null) {
            lookieTokenCount++
            if (lookieTokenCount >= lookieMaxTokens) {
                chatPresenter.stopGenerate()
                return
            }
        }
        activity?.runOnUiThread {
            // Record TTFT and model inference time on first token
            if (ttft < 0 && progress != null) {
                val now = System.currentTimeMillis()
                ttft = now - sendTime
                val modelMs = now - modelStartTime
                elapsedHandler.removeCallbacks(elapsedRunnable)
                binding.tvTtftBadge.text = getString(R.string.lookie_ttft_final, ttft / 1000f)
                binding.tvTtftBadge.visibility = View.VISIBLE
                binding.tvModelTimeBadge.text = getString(R.string.lookie_model_time, modelMs / 1000f)
                binding.tvModelTimeBadge.visibility = View.VISIBLE
            }
            val text = generateResultProcessor.getNormalOutput()
            if (text.isNotEmpty()) {
                binding.tvResponse.text = text
                // Green glow when model says CAPTURE, plain dark otherwise
                val isCapture = text.trimStart().startsWith("CAPTURE", ignoreCase = true)
                binding.tvResponse.setBackgroundResource(
                    if (isCapture) R.drawable.bg_ai_tips_capture
                    else R.drawable.bg_ai_tips_response
                )
                binding.responseOverlay.visibility = View.VISIBLE
            }
        }
    }

    override fun onDiffusionGenerateProgress(progress: String?, diffusionDestPath: String?) {
        // Lookie does not use diffusion models
    }

    override fun onGenerateFinished(benchMarkResult: HashMap<String, Any>) {
        chatPresenter.getLlmSession()?.setKeepHistory(true)
        activity?.runOnUiThread { setInferencing(false) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        activity?.window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Restore system appearance for nav bar (light icons appropriate for app theme)
        activity?.window?.let { win ->
            androidx.core.view.WindowInsetsControllerCompat(win, win.decorView)
                .isAppearanceLightNavigationBars = true
        }
        elapsedHandler.removeCallbacks(elapsedRunnable)
        cameraExecutor?.shutdown()
        shutterSound.release()
        _binding = null
    }
}
