package com.android.mca2021.keyboard.core

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.asav.facialprocessing.mtcnn.MTCNNModel
import com.google.mlkit.vision.face.Face
import java.util.*
import android.graphics.BitmapFactory

import android.graphics.Bitmap

import android.content.Context
import android.content.res.AssetManager
import com.android.mca2021.keyboard.core.mtcnn.Box
import com.asav.facialprocessing.mtcnn.MTCNNModel.Companion.create
import java.io.ByteArrayOutputStream
import java.util.Collections.max
import kotlin.math.max
import android.graphics.YuvImage
import android.media.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Collections.min
import kotlin.math.min


internal class FaceAnalyzer(
    context: Context,
    assets: AssetManager,
) : ImageAnalysis.Analyzer {
    private val minFaceSize = 32
    private var mtcnnFaceDetector: MTCNNModel? = null
    private var emotionClassifierTfLite: EmotionTfLiteClassifier? = null
    private var paused: Boolean = false

    var listener: Listener? = null

    init {
        try {
            emotionClassifierTfLite = EmotionTfLiteClassifier(context)
        } catch (e: java.lang.Exception) {
            Log.e("FACEOMJI", "Exception initializing EmotionTfLiteClassifier!")
        }
        try {
            mtcnnFaceDetector = create(assets)
        } catch (e: java.lang.Exception) {
            Log.e("FACEOMJI", "Exception initializing MTCNNModel!")
        }
    }

    fun pauseAnalysis() {
        paused = true
    }

    fun resumeAnalysis() {
        paused = false
    }

    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()

        if (!paused) {
            val bitmapImage = imageProxy.toBitmap()!!

            val rotateMatrix = Matrix()
            rotateMatrix.postRotate(-90f)

            val rotated = Bitmap.createBitmap(bitmapImage, 0, 0,
                bitmapImage.width, bitmapImage.height, rotateMatrix, false);
            mtcnnDetectionAndAttributesRecognition(rotated, emotionClassifierTfLite)
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(1000 - (System.currentTimeMillis() - currentTimestamp))
            imageProxy.close()
        }
    }

    private fun mtcnnDetectionAndAttributesRecognition(image: Bitmap, classifier: TfLiteClassifier?) {
        val bmp: Bitmap = image
        val bboxes: Vector<Box> = mtcnnFaceDetector!!.detectFaces(
            bmp,
            minFaceSize
        )
        if (bboxes.isEmpty()) {
            listener?.onEmotionDetected("Noface")
            return
        }
        val box = bboxes.first()
        val bbox: Rect =
            box.transform2Rect() //new android.graphics.Rect(Math.max(0,box.left()),Math.max(0,box.top()),box.right(),box.bottom());
        if (classifier != null && bbox.width() > 0 && bbox.height() > 0) {
            val bboxOrig = Rect(
                bbox.left * bmp.width / bmp.width,
                bmp.height * bbox.top / bmp.height,
                bmp.width * bbox.right / bmp.width,
                bmp.height * bbox.bottom / bmp.height
            )
            val faceBitmap = Bitmap.createBitmap(
                bmp,
                max(bboxOrig.left, 0),
                max(bboxOrig.top, 0),
                min(bboxOrig.width(), bmp.width - max(bboxOrig.left, 0)),
                min(bboxOrig.height(), bmp.height - max(bboxOrig.top, 0))
            )
            val resultBitmap = Bitmap.createScaledBitmap(
                faceBitmap,
                classifier.imageSizeX,
                classifier.imageSizeY,
                false
            )
            val res = classifier.classifyFrame(resultBitmap) as EmotionData
            listener?.onEmotionDetected(res.toString())
            listener?.onEmotionScoreDetected(res.emotionScores)
        }
    }

    // implemented at KeyboardCamera.kt
    internal interface Listener {
        /** Callback that receives face bounds that can be drawn on top of the viewfinder.  */
        fun onFacesDetected(proxyWidth: Int, proxyHeight: Int, face: Face)

        /** Callback that receives emotion string can be mapped to emojis.  */
        fun onEmotionDetected(emotion: String)

        /** Callback that receives emotion scores that can be drawn on top of the viewfinder.  */
        fun onEmotionScoreDetected(scores: FloatArray)

        /** Invoked when an error is encounter during face detection.  */
        fun onError(exception: Exception)
    }
}
