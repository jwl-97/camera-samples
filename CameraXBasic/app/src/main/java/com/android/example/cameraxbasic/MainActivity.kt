package com.android.example.cameraxbasic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint.*
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.window.WindowManager
import com.android.example.cameraxbasic.databinding.ActivityMainBinding
import com.android.example.cameraxbasic.databinding.CameraUiContainerBinding
import com.android.example.cameraxbasic.utils.*
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var activityMainBinding: ActivityMainBinding
    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private var displayId: Int = -1
    private lateinit var outputDirectory: File

    private var imageCapture: ImageCapture? = null
    private var uriList: ArrayList<Uri> = arrayListOf()

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager by lazy {
        applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = activityMainBinding.root.let { view ->
            if (displayId == this@MainActivity.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        initSetting()
    }

    override fun onResume() {
        super.onResume()

        hideBottomSystemUI(window, activityMainBinding.root)

        if (!hasPermissions(applicationContext)) {
            requestPermissions(PERMISSIONS_REQUIRED, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onBackPressed() {
        if (cameraUiContainerBinding?.viewPhoto?.visibility == View.VISIBLE) {
            cameraUiContainerBinding?.viewPhoto?.visibility = View.INVISIBLE
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // Workaround for Android Q memory leak issue in IRequestFinishCallback$Stub.
            // (https://issuetracker.google.com/issues/139738913)
            finishAfterTransition()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()
    }

    /** When key down event is triggered, relay it via local broadcast so fragments can handle it */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val intent = Intent(KEY_EVENT_ACTION).apply { putExtra(KEY_EVENT_EXTRA, keyCode) }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && PackageManager.PERMISSION_GRANTED != grantResults.firstOrNull()) {
            Toast.makeText(applicationContext, "Permission request denied", Toast.LENGTH_LONG)
                .show()
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////

    private fun initSetting() {
        cameraExecutor = Executors.newSingleThreadExecutor()

        displayManager.registerDisplayListener(displayListener, null)
        outputDirectory = getOutputDirectory(applicationContext)

        activityMainBinding.viewFinder.post {
            displayId = activityMainBinding.viewFinder.display.displayId

            updateCameraUi()
            setUpCamera()
        }
    }

    private fun updateCameraUi() {
        cameraUiContainerBinding?.root?.let { activityMainBinding.root.removeView(it) }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(applicationContext),
            activityMainBinding.root,
            true
        )

        cameraUiContainerBinding?.btnCapture?.setOnClickListener(this)
        cameraUiContainerBinding?.btnViewPhoto?.setOnClickListener(this)
        cameraUiContainerBinding?.btnViewPhotoAll?.setOnClickListener(this)
        cameraUiContainerBinding?.btnUpload?.setOnClickListener(this)
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(applicationContext)
        cameraProviderFuture.addListener(Runnable {
            cameraProvider = cameraProviderFuture.get()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(applicationContext))
    }

    //////////////////////////////////////////////////////////////////////////////////////////

    /** Declare and bind preview, capture and analysis use cases */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindCameraUseCases() {
        val metrics = WindowManager(this).getCurrentWindowMetrics().bounds
        val screenAspectRatio = Util.aspectRatio(metrics.width(), metrics.height())
        val rotation = activityMainBinding.viewFinder.display.rotation

        val cameraProvider = cameraProvider ?: throw IllegalStateException("initialization failed.")
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview
        val previewBuilder = Preview.Builder()

        /* ref.
         * https://stackoverflow.com/questions/66275229/working-with-cameracapturesession-capturecallback-in-camerax
         */
        val previewExtender = Camera2Interop.Extender(previewBuilder)
        previewExtender.setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        previewExtender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)

                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                val lensState = result.get(CaptureResult.LENS_STATE)
                Log.d(
                    "TEST",
                    "afState : $afState, awbState : $awbState, aeState : $aeState, lensState : $lensState"
                )

                val value = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                        && awbState == CaptureRequest.CONTROL_AWB_STATE_CONVERGED
                        && lensState == CaptureRequest.LENS_STATE_STATIONARY

                CoroutineScope(Dispatchers.Main).launch {
                    if (value) {
                        cameraUiContainerBinding?.btnCapture?.setBackgroundResource(R.drawable.ic_shutter)
                        cameraUiContainerBinding?.btnCapture?.isEnabled = true
                    } else {
                        cameraUiContainerBinding?.btnCapture?.setBackgroundResource(R.drawable.ic_shutter_not_enable)
                        cameraUiContainerBinding?.btnCapture?.isEnabled = false
                    }
                }
            }
        })

        val preview = previewBuilder.setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        cameraProvider.unbindAll()

        try {
            val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            if (camera.cameraInfo.hasFlashUnit()) {
                cameraUiContainerBinding?.btnFlash?.visibility = View.VISIBLE

                cameraUiContainerBinding?.btnFlash?.setOnClickListener {
                    val value = camera.cameraInfo.torchState.value == TorchState.OFF
                    camera.cameraControl.enableTorch(value)
                }
            }

            preview.setSurfaceProvider(activityMainBinding.viewFinder.surfaceProvider)
            observeCameraState(camera.cameraInfo)

            activityMainBinding.viewPainter.setOnTouchListener(activityMainBinding.viewPainter)
            activityMainBinding.viewFinder.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performClick()
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_UP -> {
                        val factory = activityMainBinding.viewFinder.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        camera.cameraControl.startFocusAndMetering(action)
                        v.performClick()

                        return@setOnTouchListener true
                    }
                    else -> return@setOnTouchListener false
                }
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun observeCameraState(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.observe(this) { cameraState ->
            cameraState.error?.let { error ->
                val message = when (error.code) {
                    CameraState.ERROR_STREAM_CONFIG -> "Stream config error"
                    CameraState.ERROR_CAMERA_IN_USE -> "Camera in use"
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Other recoverable error"
                    CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled"
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> "Fatal error"
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Do not disturb mode enabled"
                    else -> "else"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }

        cameraInfo.torchState.observe(this) { torchState ->
            val drawable =
                if (torchState == TorchState.OFF) R.drawable.ic_baseline_flash_off else R.drawable.ic_baseline_flash_on
            cameraUiContainerBinding?.btnFlash?.setBackgroundResource(drawable)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomAndTapToFocus(camera: Camera) {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio: Float = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1F
                val delta = detector.scaleFactor
                camera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }

        val scaleGestureDetector =
            ScaleGestureDetector(activityMainBinding.viewFinder.context, listener)

        activityMainBinding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_DOWN) {
                handler.removeCallbacksAndMessages(null)

                val factory = activityMainBinding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()

                /*
                * ref
                * https://groups.google.com/a/android.com/g/camerax-developers/c/h0Q4Al8TXmc
                *
                * AF의 기본 설정은 계속해서 초점을 잡는 'CONTROL_AF_MODE_CONTINUOUS_PICTURE'로 되어있고,
                * startFocusAndMetering() 이 실행되면 위에서 선언한대로
                * '3초'간 'CONTROL_AF_MODE_AUTO'가 되어 클릭한 부분에 수동으로 초점을 맞출 수 있게된다.
                * 3초가 지나면 이전 상태인 'CONTROL_AF_MODE_CONTINUOUS_PICTURE'로 다시 돌아간다.
                */
                camera.cameraControl.startFocusAndMetering(action)
                cameraUiContainerBinding?.ivFocusCircle?.apply {
                    visibility = View.VISIBLE
                    x = event.x
                    y = event.y
                }

                handler.postDelayed({
                    cameraUiContainerBinding?.ivFocusCircle?.visibility = View.INVISIBLE
                }, 3000)
            }
            return@setOnTouchListener true
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////

    private fun showEachImage(image: Any) {
        val view =
            LayoutInflater.from(applicationContext).inflate(R.layout.item_image_list, null, false)

        Glide.with(this).load(image).into(view.findViewById(R.id.iv_image))
        cameraUiContainerBinding?.liImage?.addView(view)
    }

    private fun setPictures(isAll: Boolean) {
        cameraUiContainerBinding?.liImage?.removeAllViews()
        cameraUiContainerBinding?.viewPhoto?.visibility = View.VISIBLE

        val image = if (isAll) {
            if (outputDirectory.listFiles()?.isEmpty() == true) return
            val rootDirectory = File(outputDirectory.absolutePath)

            rootDirectory.listFiles { file ->
                "JPG" == file.extension.toUpperCase(Locale.ROOT)
            }?.sortedDescending()?.toMutableList() ?: mutableListOf()
        } else {
            uriList
        }

        image.forEach { showEachImage(it) }
    }

    //////////////////////////////////////////////////////////////////////////////////////////

    override fun onClick(v: View?) {
        when (v) {
            cameraUiContainerBinding?.btnCapture -> {
                if (imageCapture == null) return
                cameraUiContainerBinding?.btnCapture?.isEnabled = false

                val photoFile = Util.createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                    .setMetadata(ImageCapture.Metadata())
                    .build()

                imageCapture?.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)

                            CoroutineScope(Dispatchers.Main).launch {
                                cameraUiContainerBinding?.btnCapture?.isEnabled = true
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
//                            MediaActionSound().play(MediaActionSound.SHUTTER_CLICK) //play shutter sound

                            val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                            uriList.add(savedUri)

                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            // Implicit broadcasts will be ignored for devices running API level >= 24
                            // so if you only target API level 24+ you can remove this statement
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                                applicationContext.sendBroadcast(
                                    Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                                )
                            }

                            // If the folder selected is an external media directory, this is
                            // unnecessary but otherwise other apps will not be able to access our
                            // images unless we scan them using [MediaScannerConnection]
                            val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)

                            // save to album
                            MediaScannerConnection.scanFile(
                                applicationContext,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                            ) { _, uri ->
                                Log.d(TAG, "Image capture scanned into media store: $uri")
                            }

                            CoroutineScope(Dispatchers.Main).launch {
                                cameraUiContainerBinding?.tvCount?.text = uriList.size.toString()
                                cameraUiContainerBinding?.btnCapture?.isEnabled = true
                            }
                        }
                    })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    activityMainBinding.root.postDelayed({
                        activityMainBinding.root.foreground = ColorDrawable(Color.WHITE)
                        activityMainBinding.root.postDelayed(
                            { activityMainBinding.root.foreground = null },
                            ANIMATION_FAST_MILLIS
                        )
                    }, ANIMATION_SLOW_MILLIS)
                }
            }

            cameraUiContainerBinding?.btnViewPhoto -> setPictures(false)
            cameraUiContainerBinding?.btnViewPhotoAll -> setPictures(true)
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"

        const val KEY_EVENT_ACTION = "key_event_action"
        const val KEY_EVENT_EXTRA = "key_event_extra"
    }
}
