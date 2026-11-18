package com.example.navistaraccessclient.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Base64
import android.util.Log
import com.example.navistaraccessclient.utils.BitmapUtils
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Lấy vector khuôn mặt từ ảnh FaceImg (base64) mà server gửi trong push_biometric.
 * Dùng CÙNG pipeline với camera (ML Kit → crop +15% → 112x112 → MobileFaceNet)
 * để vector đăng ký so sánh được với vector lúc quẹt mặt.
 *
 * Ảnh chụp từ điện thoại thường có cờ xoay EXIF mà BitmapFactory KHÔNG tự áp dụng
 * → xoay theo EXIF trước, nếu vẫn không thấy mặt thì thử lần lượt các góc 90/270/180.
 *
 * Hàm CHẶN luồng (ML Kit + TFLite) → chỉ gọi trên Dispatchers.Default.
 */
class FaceImageEnroller(private val recognizer: FaceRecognizer) {
    sealed class Result {
        class Ok(val embedding: FloatArray) : Result()
        class Fail(val reason: String) : Result()
    }

    fun extract(base64Image: String): Result {
        val raw = base64Image.trim()
        // Bỏ tiền tố "data:image/jpeg;base64," nếu có
        val b64 = if (raw.startsWith("data:")) raw.substringAfter(',') else raw
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Fail("FaceImg không phải base64 hợp lệ")
        }
        val decoded = decodeScaled(bytes) ?: return Result.Fail("Không giải mã được ảnh")

        val exifDeg = readExifRotation(bytes)
        val bitmap = if (exifDeg != 0) rotate(decoded, exifDeg).also { decoded.recycle() } else decoded
        Log.i(TAG, "FaceImg ${bytes.size / 1024} KB → ${bitmap.width}x${bitmap.height}, EXIF xoay $exifDeg°")

        val detector = FaceDetection.getClient(OPTIONS)
        try {
            for (deg in TRY_ROTATIONS) {
                val r = tryAt(detector, bitmap, deg)
                if (r != null) return r
            }
            return Result.Fail("Không tìm thấy khuôn mặt trong ảnh (đã thử xoay 0/90/270/180°)")
        } catch (e: Exception) {
            Log.e(TAG, "Xử lý FaceImg lỗi", e)
            return Result.Fail("Lỗi xử lý ảnh: ${e.javaClass.simpleName}")
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }

    /** Thử dò mặt ở 1 góc xoay (kèm 1 lần thêm viền). null = không thấy mặt ở góc này. */
    private fun tryAt(detector: FaceDetector, base: Bitmap, deg: Int): Result? {
        val rotated = if (deg == 0) base else rotate(base, deg)
        var padded: Bitmap? = null
        try {
            var source = rotated
            var faces = detect(detector, rotated)
            if (faces.isEmpty()) {
                padded = withMargin(rotated)
                source = padded
                faces = detect(detector, padded)
            }
            Log.i(TAG, "Xoay thêm $deg°: ${faces.size} mặt trên ảnh ${source.width}x${source.height}")
            faces.forEach { Log.i(TAG, "  mặt ${it.boundingBox.toShortString()}") }
            if (faces.isEmpty()) return null

            val main = mainFaces(faces)
            if (main.size < faces.size) Log.i(TAG, "Bỏ ${faces.size - main.size} mặt trùng/quá nhỏ (coi là nhận nhầm)")
            if (main.size > 1) return Result.Fail("Ảnh có ${main.size} khuôn mặt")

            val crop = BitmapUtils.cropFace(source, main[0].boundingBox)
                ?: return Result.Fail("Cắt ảnh khuôn mặt lỗi")
            return try {
                Result.Ok(recognizer.extractEmbedding(crop))
            } finally {
                if (crop !== source) crop.recycle()
            }
        } finally {
            padded?.recycle()
            if (rotated !== base) rotated.recycle()
        }
    }

    /**
     * Chỉ giữ các mặt "thật" trước khi áp luật ≥ 2 mặt:
     *  - Bỏ khung TRÙNG: ML Kit đôi khi trả 2 khung chồng lên nhau cho cùng 1 mặt chụp sát
     *    (≥ [DUPLICATE_OVERLAP] diện tích khung nhỏ nằm trong khung lớn hơn) → giữ khung lớn.
     *  - Bỏ khung QUÁ NHỎ (< [MIN_RELATIVE_SIZE] lần mặt lớn nhất): phản chiếu trên kính, hoa văn nền.
     * Ảnh 2 người thật: 2 mặt nằm cạnh nhau, cỡ tương đương → vẫn bị từ chối.
     */
    private fun mainFaces(faces: List<Face>): List<Face> {
        val sorted = faces.sortedByDescending { area(it.boundingBox) }
        val maxW = sorted[0].boundingBox.width()
        val kept = mutableListOf<Face>()
        for (f in sorted) {
            if (f.boundingBox.width() < maxW * MIN_RELATIVE_SIZE) continue
            if (kept.any { overlapOfSmaller(it.boundingBox, f.boundingBox) >= DUPLICATE_OVERLAP }) continue
            kept += f
        }
        return kept
    }

    private fun area(r: Rect): Long = r.width().toLong() * r.height()

    /** Tỉ lệ diện tích giao nhau so với khung NHỎ hơn (0..1) */
    private fun overlapOfSmaller(a: Rect, b: Rect): Float {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return 0f
        return area(inter).toFloat() / minOf(area(a), area(b))
    }

    private fun detect(detector: FaceDetector, bmp: Bitmap): List<Face> =
        Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)

    private fun readExifRotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0 // PNG / ảnh không có EXIF
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
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
        /** Góc xoay THÊM sau EXIF: 0 trước (ảnh đúng chiều), rồi 90/270 (ảnh dọc/ngang), cuối cùng 180 */
        val TRY_ROTATIONS = intArrayOf(0, 90, 270, 180)
        val OPTIONS: FaceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) // ảnh tĩnh → ưu tiên chính xác
            .setMinFaceSize(0.1f)
            .build()
        /** Mặt phụ có chiều rộng < 40% mặt lớn nhất → bỏ qua */
        const val MIN_RELATIVE_SIZE = 0.4f
        /** ≥ 60% diện tích khung nhỏ nằm trong khung lớn → coi là cùng 1 mặt */
        const val DUPLICATE_OVERLAP = 0.6f
    }
}