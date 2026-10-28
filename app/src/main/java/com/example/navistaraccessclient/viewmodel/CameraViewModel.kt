package com.example.navistaraccessclient.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.navistaraccessclient.model.*
import com.example.navistaraccessclient.utils.BitmapUtils
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraViewModel(application: Application): AndroidViewModel(application) {

    private val recognizer: FaceRecognizer = TFLiteFaceRecognizer(application)

    var detectedFaces by mutableStateOf<List<Face>>(emptyList())
        private set

    var imageWidth by mutableIntStateOf(0)
        private set

    var imageHeight by mutableIntStateOf(0)
        private set

    var authResult by mutableStateOf(FaceAuthResult(AuthStatus.IDLE))
        private set

    var latestFrameBitmap: Bitmap? = null
        private set

    // Cờ kiểm soát hiệu năng chống giật lag & ANR
    private var isProcessing = false
    private var lastInferenceTime = 0L
    var isSettingsOpen by mutableStateOf(false)

    fun onFacesDetected(faces: List<Face>, width: Int, height: Int, imageProxy: ImageProxy) {
        // Cập nhật tọa độ vẽ khung nhanh trên UI
        detectedFaces = faces
        imageHeight = height
        imageWidth = width

        // 1. Nếu đang mở hộp thoại Cài đặt để gõ tên -> DỪNG nhận diện để bàn phím không bị lag/ANR
        if (isSettingsOpen) {
            return
        }

        // 2. Kiểm tra số lượng khuôn mặt
        if (faces.size >= 2) {
            authResult = FaceAuthResult(
                status = AuthStatus.MULTIPLE_FACES,
                message = "Cảnh báo: Có ${faces.size} người! Vui lòng chỉ đứng 1 người."
            )
            return
        }

        if (faces.isEmpty()) {
            authResult = FaceAuthResult(AuthStatus.IDLE, message = "Vui lòng đưa mặt vào giữa khung Oval")
            return
        }

        // 3. THROTTLING: Chỉ chạy AI nếu không bận và cách lần trước ít nhất 500ms
        val currentTime = System.currentTimeMillis()
        if (isProcessing || (currentTime - lastInferenceTime < 500)) {
            return
        }

        isProcessing = true
        lastInferenceTime = currentTime

        val primaryFace = faces[0]
        val frameBitmap = BitmapUtils.imageProxyToBitmap(imageProxy)

        if (frameBitmap != null) {
            // Giải phóng frame cũ ngay lập tức tránh tràn RAM
            if (latestFrameBitmap != null && latestFrameBitmap != frameBitmap) {
                latestFrameBitmap?.recycle()
            }
            latestFrameBitmap = frameBitmap

            val faceBitmap = BitmapUtils.cropFace(frameBitmap, primaryFace.boundingBox)
            if (faceBitmap != null) {
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val result = recognizer.verify(faceBitmap)
                        authResult = result
                    } finally {
                        faceBitmap.recycle() // Thu hồi RAM lập tức
                        isProcessing = false
                    }
                }
            } else {
                isProcessing = false
            }
        } else {
            isProcessing = false
        }
    }

    fun registerCurrentFace(name: String, onSuccess: () -> Unit) {
        val bitmap = latestFrameBitmap
        val face = detectedFaces.firstOrNull()
        if (bitmap != null && !bitmap.isRecycled && face != null) {
            val faceBitmap = BitmapUtils.cropFace(bitmap, face.boundingBox)
            if (faceBitmap != null) {
                val userId = "USER_${System.currentTimeMillis() % 10000}"
                recognizer.registerFace(userId, name, faceBitmap)
                faceBitmap.recycle()
                onSuccess()
            }
        }
    }

    fun setThreshold(threshold: Float) {
        recognizer.setThreshold(threshold)
    }

    fun getRegisteredUsers(): List<RegisteredUser> {
        return recognizer.getRegisteredUsers()
    }
}