package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.camera.databinding.FragmentCameraBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class CameraFragment : Fragment() {

    lateinit var binding: FragmentCameraBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var step = arrayListOf(false, false, false)

    private lateinit var cameraExecutor: ExecutorService
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //TODO: this hw level specified your camera, so you know to bind your camera, video and analyzer
        val manager = requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId!!).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if(characteristics == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY){
                Log.d("cameraLvl","camera_level : LEGACY")
            }else if(characteristics == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL){
                Log.d("cameraLvl","camera_level : FULL")
            }else if(characteristics == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED){
                Log.d("cameraLvl","camera_level : LIMITED")
            }else if(characteristics == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3){
                Log.d("cameraLvl","camera_level : LEVEL 3")
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestMultiplePermissions.launch(REQUIRED_PERMISSIONS)
        }

        binding.imageCaptureButton.setOnClickListener { takePhoto() }
        binding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.getDefault())
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                @SuppressLint("RestrictedApi")
                override fun onImageSaved(output: ImageCapture.OutputFileResults){
//                    val file = output.savedUri?.path?.let { File(it)}
//                    val bitmap = BitmapFactory.decodeFile(file?.path)
//                    file?.path?.let {
//                        CameraUtils.changeRotate(it, bitmap)
//                    }
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d("checkuri", "Uri: $msg Path: ${output.savedUri?.path}")

                    output.savedUri?.let { uri ->
                        val bitmap = if(Build.VERSION.SDK_INT < 28) {
                            MediaStore.Images.Media.getBitmap(requireContext().contentResolver, output.savedUri)
                        } else {
                            val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        }
                        Log.d(TAG, "$bitmap")
                    }

                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        binding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireContext().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
//            .apply {
//                if (PermissionChecker.checkSelfPermission(requireContext(),
//                        Manifest.permission.RECORD_AUDIO) ==
//                    PermissionChecker.PERMISSION_GRANTED)
//                {
//                    withAudioEnabled()
//                }
//            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            //comment this because we want to try video
            imageCapture = ImageCapture.Builder().build()

            // High-accuracy landmark detection and face classification
            val highAccuracyOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.5f)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            // Real-time contour detection
            val realTimeOpts = FaceDetectorOptions.Builder()
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()

            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        val mediaImage = imageProxy.image
                        if(mediaImage?.format == ImageFormat.YUV_420_888){
                            Log.d("cameraFormat", "YUV_420_888")
                        }else if(mediaImage?.format == ImageFormat.NV21){
                            Log.d("cameraFormat", "NV21")
                        }

                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            val detector = FaceDetection.getClient(if(step[0] && step[1]) realTimeOpts else highAccuracyOpts)
                            val result = detector.process(image)
                                .addOnSuccessListener { faces ->
                                    for (face in faces) {
                                        val bounds = face.boundingBox
                                        val rotY = face.headEulerAngleY // Head is rotated to the right rotY degrees
                                        val rotX = face.headEulerAngleX // Head is facing upward
                                        val rotZ = face.headEulerAngleZ // Head is tilted sideways rotZ degrees

                                        if(rotY < -30){
                                            binding.photoDesc.text="Nengok kanan: $rotY"
                                            step[0] = true
                                        }else if (rotY > 30){
                                            binding.photoDesc.text="Nengok kiri: $rotY"
                                            step[1] = true
                                        }

                                        // If landmark detection was enabled (mouth, ears, eyes, cheeks, and
                                        // nose available):
                                        val leftEar = face.getLandmark(FaceLandmark.LEFT_EAR)
                                        leftEar?.let {
                                            val leftEarPos = leftEar.position
                                        }

                                        val rightEar = face.getLandmark(FaceLandmark.RIGHT_EAR)
                                        rightEar?.let {
                                            val rightEarPos = rightEar.position
                                        }

                                        // If contour detection was enabled:
                                        val leftEyeContour = face.getContour(FaceContour.LEFT_EYE)?.points
                                        val rightEyeContour = face.getContour(FaceContour.RIGHT_EYE)?.points
                                        val upperLipBottomContour = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points
                                        val lowerLipTopContour = face.getContour(FaceContour.LOWER_LIP_TOP)?.points

                                        lowerLipTopContour?.let{ lowerLip ->
                                            upperLipBottomContour?.let{ upperLip ->
                                                if(lowerLip[4].y - upperLip[4].y > 50){
                                                    binding.photoDesc.text="kamu nganga ?"
                                                }
                                            }
                                        }

                                        // If classification was enabled:
                                        if (face.smilingProbability != null) {
                                            val smileProb = face.smilingProbability
                                        }
                                        if (face.rightEyeOpenProbability != null) {
                                            val rightEyeOpenProb = face.rightEyeOpenProbability
                                        }

                                        face.headEulerAngleY

                                        // If face tracking was enabled:
                                        if (face.trackingId != null) {
                                            val id = face.trackingId
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    //Log.d(TAG, "Error analyze : ${e.message}")
                                }
                        }
                        imageProxy.close()
                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireActivity().baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all {
                it.value
            }
            if(granted){
                startCamera()
            }else{
                Toast.makeText(requireContext(), "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
}