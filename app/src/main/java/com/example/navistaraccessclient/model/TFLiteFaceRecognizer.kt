package com.example.navistaraccessclient.model

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class TFLiteFaceRecognizer(
    private val context: Context,
    private val modelFileName: String = "mobilefacenet.tflite"
) : FaceRecognizer{

    private var interpreter: Interpreter? = null
    private var threshold: Float = 0.70f

    private val registeredUsers = mutableMapOf<String, RegisteredUser>()

    private val inputSize = 112
    private val embeddingDim = 192

    init {
        try {
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(modelBuffer)
        } catch (e: Exception){
            android.util.Log.w("TFLiteFaceRecognizer", "Chưa tìm thấy model $modelFileName trong assets, dùng chế độ Mocking.")
        }
    }

    override fun setThreshold(threshold: Float){
        this.threshold = threshold
    }

    override fun getRegisteredUsers(): List<RegisteredUser> {
        return registeredUsers.values.toList()
    }

    override fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        val output = Array(1) { FloatArray(embeddingDim) }
        val interpreterInstance = interpreter

        if (interpreterInstance == null) {
            return FloatArray(embeddingDim) { 0.1f}
        }

        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply{
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(inputSize * inputSize)
        val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for(pixel in pixels){
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }

        interpreterInstance.run(byteBuffer, output)
        return output[0]
    }

    override fun verify(faceBitmap: Bitmap): FaceAuthResult{
        if (registeredUsers.isEmpty()) {
            return FaceAuthResult(
                status = AuthStatus.FAILED,
                message = "Hệ thống chưa có người dùng nào được đăng ký!"
            )
        }

        val currentEmbedding = extractEmbedding(faceBitmap)
        var bestMatch: RegisteredUser? = null
        var maxSimilarity = -1f

        // Find the person with the highest Cosine Similarity
        for (user in registeredUsers.values) {
            val sim = calculateCosineSimilarity(currentEmbedding, user.embedding)
            if (sim > maxSimilarity) {
                maxSimilarity = sim
                bestMatch = user
            }
        }

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

    /**
     * Calculates the cosine similarity between two face vectors
     */
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