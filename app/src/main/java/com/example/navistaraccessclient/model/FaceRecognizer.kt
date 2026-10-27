package com.example.navistaraccessclient.model

import android.graphics.Bitmap

/**
 * Interface trừu tượng cho module Nhận diện khuôn mặt.
 * Sau này khi Lab cấp SDK riêng hoặc chuyển sang MediaPipe,
 * chỉ cần viết 1 class mới implement interface này mà không cần sửa code UI!
 */

interface FaceRecognizer {
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray

    fun verify(faceBitmap: Bitmap): FaceAuthResult

    fun registerFace(id: String, name: String, faceBitmap: Bitmap): Boolean

    fun getRegisteredUsers(): List<RegisteredUser>

    fun setThreshold(threshold: Float)
}