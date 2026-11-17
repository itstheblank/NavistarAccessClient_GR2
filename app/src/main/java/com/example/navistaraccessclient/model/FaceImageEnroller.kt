package com.example.navistaraccessclient.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.example.navistaraccessclient.utils.BitmapUtils
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Lấy vector khuôn mặt từ ảnh FaceImg (base64) mà server gửi trong push_biometric.
 * Dùng CÙNG pipeline với camera (ML Kit → crop +15% → 112x112 → MobileFaceNet)
 * để vector đăng ký so sánh được với vector lúc quẹt mặt.
 *
 * Hàm CHẶN luồng (ML Kit + TFLite) → chỉ gọi trên Dispatchers.Default.
 */
class FaceImageEnroller(private val recognizer: FaceRecognizer) {
    sealed class Result {
        class Ok(val embedding: FloatArray) : Result()
        class Fail(val reason: String) : Result()
    }

    fun extract(base64Image: String): Result{
        val bytes = try {
            Base64.decode(base64Image.substringAfter("base64"), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Fail("FaceImg không phải base64 hợp lệ")
        }
        val bitmap = decodeScaled(bytes) ?: return Result.Fail("Không giải mã được ảnh")

        val detector = FaceDetection.getClient(OPTIONS)
        var padded: Bitmap? = null
        try {
            var source = bitmap
            var faces = detect(detector, bitmap)
            if (faces.isEmpty()) {
                padded = withMargin(bitmap)
                source = padded
                faces = detect(detector, padded)
            }
            if (faces.isEmpty()) return Result.Fail("Không tìm thấy khuôn mặt trong ảnh")
            if (faces.size > 1) return Result.Fail("Ảnh có ${faces.size} khuôn mặt")

            val crop = BitmapUtils.cropFace(source, faces[0].boundingBox)
                ?: return Result.Fail("Cắt ảnh khuôn mặt lỗi")
            return try {
                Result.Ok(recognizer.extractEmbedding(crop))
            } finally {
                if (crop !== source) crop.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Xử lý FaceImg lỗi", e)
            return Result.Fail("Lỗi xử lý ảnh: ${e.javaClass.simpleName}")
        } finally {
            detector.close()
            padded?.recycle()
            bitmap.recycle()
        }
    }

    private fun detect(detector: FaceDetector, bmp: Bitmap): List<Face> = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun withMargin(src: Bitmap): Bitmap {
        val m = (maxOf(src.width, src.height) * 0.4f).toInt()
        val out = Bitmap.createBitmap(src.width + 2 * m, src.height + 2 * m, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.GRAY)
        canvas.drawBitmap(src, m.toFloat(), m.toFloat(), null)
        return out
    }

    private companion object {
        const val TAG = "FaceImageEnroller"
        const val MAX_SIDE = 800
        val OPTIONS: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) // ảnh tĩnh → ưu tiên chính xác
            .setMinFaceSize(0.1f)
            .build()
    }
}