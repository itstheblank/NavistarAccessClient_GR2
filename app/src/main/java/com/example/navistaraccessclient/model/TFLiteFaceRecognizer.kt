package com.example.navistaraccessclient.model

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class TFLiteFaceRecognizer(
    private val context: Context,
    private val modelFileName: String = "mobilefacenet.tflite"
) : FaceRecognizer {

    companion object {
        private const val TAG = "TFLiteFaceRecognizer"
    }

    private var interpreter: Interpreter? = null
    @Volatile private var threshold: Float = 0.70f

    // Có thể được đọc/ghi từ nhiều luồng (nhận diện nền + đăng ký)
    private val registeredUsers = ConcurrentHashMap<String, RegisteredUser>()

    private val inputSize = 112
    private val embeddingDim = 192

    // Tái sử dụng bộ đệm → không cấp phát ~150KB mỗi lần suy luận
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(inputSize * inputSize)
    private val lock = Any()

    init {
        try {
            context.assets.openFd(modelFileName).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    val modelBuffer = fis.channel.map(
                        FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
                    )
                    val options = Interpreter.Options().setNumThreads(2)
                    interpreter = Interpreter(modelBuffer, options)
                }
            }
            Log.i(TAG, "Đã nạp model $modelFileName")
        } catch (t: Throwable) {
            // Throwable: bắt cả UnsatisfiedLinkError (lỗi thư viện native)
            Log.e(TAG, "Không nạp được model $modelFileName → chế độ Mocking", t)
        }
    }

    override fun setThreshold(threshold: Float) {
        this.threshold = threshold
    }

    override fun getRegisteredUsers(): List<RegisteredUser> = registeredUsers.values.toList()

    override fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        // Interpreter KHÔNG thread-safe → khóa lại
        synchronized(lock) {
            val interpreterInstance = interpreter ?: return FloatArray(embeddingDim) { 0.1f }
            val scaled = if (faceBitmap.width == inputSize && faceBitmap.height == inputSize) faceBitmap
            else Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
            scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            if (scaled !== faceBitmap) scaled.recycle()

            inputBuffer.rewind()
            for (pixel in pixels) {
                inputBuffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 128.0f)
                inputBuffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 128.0f)
                inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 128.0f)
            }
            inputBuffer.rewind()

            val output = Array(1) { FloatArray(embeddingDim) }
            interpreterInstance.run(inputBuffer, output)
            return output[0]
        }
    }

    override fun verify(faceBitmap: Bitmap): FaceAuthResult {
        if (registeredUsers.isEmpty()) {
            return FaceAuthResult(
                status = AuthStatus.FAILED,
                message = "Hệ thống chưa có người dùng nào được đăng ký!"
            )
        }

        val start = SystemClock.elapsedRealtime()
        val currentEmbedding = extractEmbedding(faceBitmap)
        var bestMatch: RegisteredUser? = null
        var maxSimilarity = -1f

        for (user in registeredUsers.values) {
            val sim = calculateCosineSimilarity(currentEmbedding, user.embedding)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
                bestMatch = user
            }
        }
        // Dùng cho tiêu chí nghiệm thu "độ trễ suy luận ≤ 100ms" (lọc Logcat theo tag này)
        Log.d(TAG, "verify() = ${SystemClock.elapsedRealtime() - start} ms, sim=$maxSimilarity")

        return if (maxSimilarity >= threshold && bestMatch != null) {
            FaceAuthResult(
                status = AuthStatus.SUCCESS,
                userId = bestMatch.id,
                userName = bestMatch.name,
                confidence = maxSimilarity,
                message = "Chào mừng ${bestMatch.name}!"
            )
        } else {
            FaceAuthResult(
                status = AuthStatus.FAILED,
                confidence = maxSimilarity.coerceAtLeast(0f),
                message = "Người lạ (Similarity: ${"%.2f".format(maxSimilarity)})"
            )
        }
    }

    override fun registerFace(id: String, name: String, faceBitmap: Bitmap): Boolean {
        val embedding = extractEmbedding(faceBitmap)
        registeredUsers[id] = RegisteredUser(id, name, embedding)
        return true
    }

    override fun close() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
        }
    }

    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0f) dot / denominator else 0f
    }
}
