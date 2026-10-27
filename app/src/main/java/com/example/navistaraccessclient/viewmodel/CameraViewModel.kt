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

    fun onFacesDetected(faces: List<Face>, width: Int, height: Int, imageProxy: ImageProxy){
        detectedFaces = faces
        imageHeight = height
        imageWidth = width

        // 1. Check the number of faces (Rule: If >= 2 faces are detected, either select only one or issue a warning)
        if (faces.size >= 2) {
            authResult = FaceAuthResult(
                status = AuthStatus.MULTIPLE_FACES,
                message = "Cảnh báo: Phát hiện ${faces.size} khuôn mặt! Vui lòng chỉ đứng 1 người."
            )
            return
        }

        if (faces.isEmpty()) {
            authResult = FaceAuthResult(AuthStatus.IDLE, message = "Vui lòng đưa mặt vào giữa khung Oval")
            return
        }

        // 2. Convert and crop the face for recognition
        val primaryFace = faces[0]
        val frameBitmap = BitmapUtils.imageProxyToBitmap(imageProxy)
        latestFrameBitmap = frameBitmap

        if (frameBitmap != null) {
            val faceBitmap = BitmapUtils.cropFace(frameBitmap, primaryFace.boundingBox)
            if (faceBitmap != null) {
                viewModelScope.launch(Dispatchers.Default) {
                    val result = recognizer.verify(faceBitmap)
                    authResult = result
                }
            }
        }
    }

    /**
     * Register the current face as a new user
     */
    fun registerCurrentFace(name: String, onSuccess: () -> Unit) {
        val bitmap = latestFrameBitmap
        val face = detectedFaces.firstOrNull()
        if (bitmap != null && face != null) {
            val faceBitmap = BitmapUtils.cropFace(bitmap, face.boundingBox)
            if (faceBitmap != null) {
                val userId = "USER_${System.currentTimeMillis() % 10000}"
                recognizer.registerFace(userId, name, faceBitmap)
                onSuccess()
            }
        }
    }

    fun setThreshold(threshold: Float){
        recognizer.setThreshold(threshold)
    }

    fun getRegisteredUsers(): List<RegisteredUser>{
        return recognizer.getRegisteredUsers()
    }
}
