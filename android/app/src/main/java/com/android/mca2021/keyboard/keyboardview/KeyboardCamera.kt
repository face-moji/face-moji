package com.android.mca2021.keyboard.keyboardview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.*
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputConnection
import android.widget.Button
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.face.Face
import com.android.mca2021.keyboard.*
import com.android.mca2021.keyboard.MainActivity.Companion.REQUEST_PERMISSION
import com.android.mca2021.keyboard.MainActivity.Companion.REQUIRED_PERMISSIONS
import com.android.mca2021.keyboard.core.FaceAnalyzer
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import com.android.mca2021.keyboard.CircularButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class KeyboardCamera(
    private val service: FacemojiService,
    override val context: Context,
    private val assets: AssetManager,
    private val layoutInflater: LayoutInflater,
    override val keyboardInteractionListener: KeyboardInteractionManager,
) : FacemojiKeyboard(), LifecycleOwner {
    private lateinit var cameraLayout: View

    override var inputConnection: InputConnection? = null
    override var vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var cameraExecutor: ExecutorService
    private val TAG: String = "mojiface"

    /*

    private var emojiItemIds = listOf(
        R.id.recommendation_1,
        R.id.recommendation_2,
        R.id.recommendation_3,
        R.id.recommendation_4
    )
     */

    private val labelEmojis = mapOf(
        "Anger" to "\uD83D\uDE21",
        "Contempt" to "\uD83D\uDE12",
        "Disgust" to "\uD83D\uDE23",
        "Fear" to "\uD83D\uDE28",
        "Happiness" to "\uD83D\uDE42",
        "Neutral" to "\uD83D\uDE10",
        "Sadness" to "\uD83D\uDE1E",
        "Surprise" to "\uD83D\uDE2E",
    )

    private val emotions = arrayOf(
        "Anger",
        "Disgust",
        "Fear",
        "Happiness",
        "Neutral",
        "Sadness",
        "Surprise",
    )

    /* UI */
    private lateinit var circularButton: CircularButton
    private lateinit var disabledIndicator: View

    private var scaledEmojiIndex: Int? = null

    override fun changeCaps() {}

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val faceAnalyzer: FaceAnalyzer by lazy {
        createFaceAnalyzer()
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun getEmojiByUnicode(unicode: Int): String {
        return String(Character.toChars(unicode))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initKeyboard() {
        cameraLayout = layoutInflater.inflate(R.layout.keyboard_camera, null)
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val config = context.resources.configuration
        sharedPreferences = context.getSharedPreferences("setting", Context.MODE_PRIVATE)
        sound = sharedPreferences.getInt("keyboardSound", -1)
        vibrate = sharedPreferences.getInt("keyboardVibrate", -1)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera(config)
        } else {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK)
                putExtra(REQUEST_PERMISSION, true)
            }
            context.startActivity(intent)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        val changeModeButton = cameraLayout.findViewById<Button>(R.id.change_camera_input_mode)
        changeModeButton.setOnClickListener {
            cameraExecutor.shutdown()
            keyboardInteractionListener.changeMode(KeyboardInteractionManager.KeyboardType.ENGLISH)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

    }

    private fun startAnalysis(moveGraph: Boolean = true) {
        faceAnalyzer.resumeAnalysis()
    }

    private fun startTraverse() {
        faceAnalyzer.pauseAnalysis()
        disabledIndicator.visibility = View.VISIBLE
    }

    private fun getAdjacentEmojis(emoji0: String): String {
        /*
        This function will return adjacent emojis of emoji0 (based on emoji graph) later.
        As prototype, just get integer string and return doubled value of it.
         */
        return (emoji0.toInt() * 2).toString()
    }

    private fun degreesToFirebaseRotation(degrees: Int): Int = when (degrees) {
        0 -> FirebaseVisionImageMetadata.ROTATION_0
        90 -> FirebaseVisionImageMetadata.ROTATION_90
        180 -> FirebaseVisionImageMetadata.ROTATION_180
        270 -> FirebaseVisionImageMetadata.ROTATION_270
        else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera(config: Configuration) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(service)
        val viewFinder = cameraLayout.findViewById<PreviewView>(R.id.view_finder)

        val preferredHeight = sharedPreferences.getFloat("cameraHeight", 350f)
        val heightInDp =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) preferredHeight
            else preferredHeight
        val height = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            heightInDp,
            context.resources.displayMetrics
        ).toInt()
        viewFinder.layoutParams.height = height

        //Face Detect option
        val realTimeOpts = FirebaseVisionFaceDetectorOptions.Builder()
            .setContourMode(FirebaseVisionFaceDetectorOptions.ALL_CONTOURS)
            .build()

        val detector = FirebaseVision.getInstance()
            .getVisionFaceDetector(realTimeOpts)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.width()
            } else {
                windowManager.defaultDisplay.width
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(width, height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_90)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, faceAnalyzer)
                }

            // Select front camera as a default
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageAnalysis, preview
                )

            } catch (exc: Exception) {
                Log.e("facemoji", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(service))
    }

    override fun getLayout(): View {
        return cameraLayout
    }

    private fun createFaceAnalyzer(): FaceAnalyzer {
        val faceAnalyzer = FaceAnalyzer(context, assets)
        faceAnalyzer.listener = object : FaceAnalyzer.Listener {
            override fun onFacesDetected(proxyWidth: Int, proxyHeight: Int, face: Face) {
//                val faceContourOverlay = cameraLayout.findViewById<FaceContourOverlay>(R.id.faceContourOverlay)
//                faceContourOverlay.post { faceContourOverlay.drawFaceBounds(proxyWidth, proxyHeight, face)}
            }

            override fun onEmotionDetected(emotion: String) {
                Handler(Looper.getMainLooper()).post {
                    //mainEmojiText.text = labelEmojis[emotion]
                }
            }

            override fun onEmotionScoreDetected(scores: FloatArray) {
//                Handler(Looper.getMainLooper()).post {
//                    setEmotionText(scores)
//                }
            }

            override fun onError(exception: Exception) {
                Log.e(TAG, "Face detection error", exception)
            }
        }
        return faceAnalyzer
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }
}