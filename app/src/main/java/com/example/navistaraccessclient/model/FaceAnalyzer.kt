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

/**
 * FaceAnalyzer - Nhận ImageProxy từ CameraX, đẩy cho ML Kit để phát hiện khuôn mặt.
 *
 * Lớp này implement ImageAnalysis.Analyzer - giao diện callback của CameraX.
 * Mỗi khi CameraX capture được 1 frame ảnh, hàm analyze() sẽ được gọi tự động.
 *
 * @param onFacesDetected Callback trả về: danh sách Face, chiều rộng ảnh, chiều cao ảnh
 */
class FaceAnalyzer(
    private val onFacesDetected: (List<Face>, Int, Int) -> Unit
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

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val isImageFlipped = rotationDegrees == 90 || rotationDegrees == 270

        val imageWidth = if (isImageFlipped) mediaImage.height else mediaImage.width
        val imageHeight = if (isImageFlipped) mediaImage.width else mediaImage.height

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                onFacesDetected(faces, imageWidth, imageHeight)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
