package com.example.navistaraccessclient.model

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

/**
 * Bộ phân tích khung hình bằng ML Kit.
 *
 * - [isPaused]: khi trả về true (VD: đang mở hộp thoại Cài đặt), frame bị bỏ qua ngay,
 *   không chạy ML Kit → giải phóng CPU cho bàn phím ảo / UI.
 * - [minIntervalMs]: giới hạn tần suất chạy ML Kit (giảm tải CPU trên giả lập / thiết bị yếu).
 * - [onFacesDetected] được gọi trên luồng camera, TRƯỚC khi imageProxy bị đóng,
 *   nên có thể chuyển imageProxy sang Bitmap một cách an toàn trong callback.
 */
class FaceAnalyzer(
    private val callbackExecutor: Executor,
    private val isPaused: () -> Boolean = { false },
    private val minIntervalMs: Long = 66L, // ~15 lần/giây là đủ cho kiểm soát ra vào
    private val onFacesDetected: (List<Face>, Int, Int, ImageProxy) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
    }

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(options)

    @Volatile
    private var lastRunTime = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val mediaImage = imageProxy.image
        if (mediaImage == null || isPaused() || now - lastRunTime < minIntervalMs) {
            imageProxy.close()
            return
        }
        lastRunTime = now

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        val isImageFlipped = rotationDegrees == 90 || rotationDegrees == 270
        val imageWidth = if (isImageFlipped) mediaImage.height else mediaImage.width
        val imageHeight = if (isImageFlipped) mediaImage.width else mediaImage.height

        // Gộp vào 1 listener duy nhất: đảm bảo thứ tự "xử lý xong -> mới close()"
        detector.process(inputImage)
            .addOnCompleteListener(callbackExecutor) { task ->
                try {
                    if (task.isSuccessful) {
                        onFacesDetected(task.result ?: emptyList(), imageWidth, imageHeight, imageProxy)
                    } else {
                        Log.e(TAG, "Face detection failed", task.exception)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onFacesDetected error", e)
                } finally {
                    imageProxy.close()
                }
            }
    }

    fun close() {
        detector.close()
    }
}
