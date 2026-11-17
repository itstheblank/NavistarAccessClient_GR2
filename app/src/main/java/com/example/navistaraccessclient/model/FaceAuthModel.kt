package com.example.navistaraccessclient.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.navistaraccessclient.iot.protocol.DaySchedule

/**
 * Face Authentication status
 */
enum class AuthStatus{
    IDLE,           // No face detected or face is not inside the oval
    DETECTED,       // Face detected, processing to recognize
    SUCCESS,        // Auth success
    FAILED,         // Mismatch (stranger)
    MULTIPLE_FACES  // Warning: There are 2 or more people in the frame
}

/**
 * Face Recognition result
 */
data class FaceAuthResult(
    val status: AuthStatus,
    val userId: String? = null,
    val userName: String? = null,
    val confidence: Float = 0f,
    val message: String = ""
)

/**
 * Data for users who have registered their faces in the system
 */
data class RegisteredUser(
    val id: String,
    val name: String,
    val embedding: FloatArray,
    val bioId: Long? = null,
    val idNumber: String? = null,
    val lstTimes: List<DaySchedule>? = null
) {
    companion object {
        fun serverId(bioId: Long) = "BIO_$bioId"
    }
}
