// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat.lookie

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatPresenter
import com.alibaba.mnnllm.android.chat.GenerateResultProcessor
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.databinding.FragmentLookieVideoBinding
import com.alibaba.mnnllm.android.utils.ImageUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    // Camera
    private var imageCapture: ImageCapture? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var cameraExecutor: ExecutorService? = null
    private var currentCamera: Camera? = null

    // Gallery — captured photos in this session
    private val capturedUris = mutableListOf<Uri>()

    // TTFT state
    private var sendTime: Long = 0L
    private var ttft: Long = -1L
    private var isInferencing = false
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
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLookieVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = modelName
        binding.toolbar.setNavigationOnClickListener { activity?.supportFragmentManager?.popBackStack() }
        setupClickListeners()
        checkAndRequestCameraPermission()
        warmupGpu()
    }

    private fun setupClickListeners() {
        binding.btnAiTips.setOnClickListener {
            if (isInferencing) chatPresenter.stopGenerate()
            else captureAndAnalyze()
        }
        binding.btnCapture.setOnClickListener { captureSnapshot() }
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                currentCamera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, currentCameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun switchCamera() {
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
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
                    activity?.runOnUiThread {
                        binding.btnGallery.setImageURI(galleryUri)
                        flashShutter()
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
                    ImageUtils.compressImageFile(photoFile)
                    val galleryUri = saveToGallery(photoFile) ?: Uri.fromFile(photoFile)
                    capturedUris.add(0, galleryUri)
                    activity?.runOnUiThread {
                        binding.btnGallery.setImageURI(galleryUri)
                        flashShutter()
                        // Reset KV-cache so each press is a fresh single-turn call (prevents TTFT growing)
                        chatPresenter.getLlmSession()?.reset()
                        setInferencing(true)
                    }

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
                // Create a tiny 8×8 white JPEG as the dummy image
                val dummy = File(requireContext().cacheDir, "lookie_warmup.jpg")
                if (!dummy.exists()) {
                    val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    dummy.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                    bmp.recycle()
                }
                val session = chatPresenter.getLlmSession()
                if (session != null) {
                    session.reset()
                    session.generate("<img>${dummy.absolutePath}</img>hi", mapOf(), object : com.alibaba.mnnllm.android.llm.GenerateProgressListener {
                        override fun onProgress(progress: String?): Boolean = true // stop immediately after first token
                    })
                    session.reset() // clear warm-up from context
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU warm-up failed (non-fatal)", e)
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
        binding.btnCapture.isEnabled = !inferencing
        if (!inferencing) elapsedHandler.removeCallbacks(elapsedRunnable)
    }

    // --- Gallery ---

    private fun openGallery() {
        if (capturedUris.isEmpty()) {
            Toast.makeText(requireContext(), R.string.lookie_no_photos, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(requireContext())
        val rv = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = GalleryAdapter(capturedUris.toList())
            setPadding(16, 24, 16, 24)
        }
        dialog.setContentView(rv)
        dialog.show()
    }

    private fun saveToGallery(file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MnnLookie")
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

    private fun flashShutter() {
        binding.shutterFlash.visibility = View.VISIBLE
        binding.shutterFlash.alpha = 1f
        binding.shutterFlash.animate().alpha(0f).setDuration(150).withEndAction {
            binding.shutterFlash.visibility = View.INVISIBLE
        }.start()
    }

    // --- ChatPresenter.GenerateListener ---

    override fun onGenerateStart() {
        activity?.runOnUiThread {
            sendTime = System.currentTimeMillis()
            ttft = -1L
            binding.responseOverlay.visibility = View.GONE
            binding.tvTtftBadge.visibility = View.GONE
            elapsedHandler.post(elapsedRunnable)
        }
    }

    override fun onLlmGenerateProgress(progress: String?, generateResultProcessor: GenerateResultProcessor) {
        activity?.runOnUiThread {
            // Record TTFT on first token
            if (ttft < 0 && progress != null) {
                ttft = System.currentTimeMillis() - sendTime
                elapsedHandler.removeCallbacks(elapsedRunnable)
                binding.tvTtftBadge.text = getString(R.string.lookie_ttft_final, ttft / 1000f)
                binding.tvTtftBadge.visibility = View.VISIBLE
            }
            val text = generateResultProcessor.normalStringBuilder.toString()
            if (text.isNotEmpty()) {
                binding.tvResponse.text = text
                binding.responseOverlay.visibility = View.VISIBLE
            }
        }
    }

    override fun onDiffusionGenerateProgress(progress: String?, diffusionDestPath: String?) {
        // Lookie does not use diffusion models
    }

    override fun onGenerateFinished(benchMarkResult: HashMap<String, Any>) {
        activity?.runOnUiThread { setInferencing(false) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        elapsedHandler.removeCallbacks(elapsedRunnable)
        cameraExecutor?.shutdown()
        _binding = null
    }
}
