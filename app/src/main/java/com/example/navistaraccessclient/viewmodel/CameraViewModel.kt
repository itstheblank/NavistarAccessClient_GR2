package com.example.navistaraccessclient.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.navistaraccessclient.data.FaceStore
import com.example.navistaraccessclient.model.*
import com.example.navistaraccessclient.utils.BitmapUtils
import com.example.navistaraccessclient.utils.OverlayGeometry
import com.example.navistaraccessclient.utils.OverlayGeometry.Placement
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Gói dữ liệu 1 khung hình cho Overlay (1 state duy nhất → 1 lần invalidate) */
data class FaceFrame(
    val faces: List<Face> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

/**
 * QUY TẮC LUỒNG:
 *  - onFacesDetected() chạy trên luồng camera.
 *  - Mọi thay đổi State của Compose đều được đẩy về Main Thread.
 *  - TFLite (verify / register) luôn chạy trên Dispatchers.Default, KHÔNG BAO GIỜ trên Main.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    // Người dùng đã đăng ký được lưu xuống file (FaceStore) → mở lại app vẫn còn
    private val recognizer = TFLiteFaceRecognizer(application, store = FaceStore(application))

    // Chỉ Overlay đọc state này (trong pha draw) → không recompose cả màn hình mỗi frame
    var faceFrame by mutableStateOf(FaceFrame())
        private set

    // Chỉ đổi khi chuyển trạng thái có mặt / không có mặt → Dialog không bị recompose mỗi frame
    var hasFaceCandidate by mutableStateOf(false)
        private set

    var authResult by mutableStateOf(FaceAuthResult(AuthStatus.IDLE))
        private set

    var isSettingsOpen by mutableStateOf(false)
        private set

    var isRegistering by mutableStateOf(false)
        private set

    /** Bật trong Cài đặt để vẽ bounding box của ML Kit (phục vụ debug / chụp báo cáo) */
    var showDebugBox by mutableStateOf(false)

    /** Danh sách người đã đăng ký — là State để đăng ký/xoá xong Dialog tự cập nhật */
    var registeredUsers by mutableStateOf<List<RegisteredUser>>(emptyList())
        private set

    // Kích thước vùng vẽ (px) → dùng để kiểm tra mặt có nằm trong Oval không
    @Volatile private var canvasWidth = 0f
    @Volatile private var canvasHeight = 0f
    @Volatile private var faceWasValid = false

    fun onCanvasSizeChanged(width: Int, height: Int) {
        canvasWidth = width.toFloat()
        canvasHeight = height.toFloat()
    }

    // Đọc từ luồng camera → cần @Volatile
    @Volatile private var settingsOpenFlag = false
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var lastInferenceTime = 0L

    // Ảnh mặt 112x112 gần nhất (cùng frame với bounding box) để đăng ký
    private val candidateLock = Any()
    private var lastFaceCrop: Bitmap? = null

    init {
        // Nạp người dùng đã lưu từ bộ nhớ trong (đọc file → luồng IO, không chặn Main)
        viewModelScope.launch(Dispatchers.IO) {
            recognizer.loadFromStore()
            val users = recognizer.getRegisteredUsers()
            withContext(Dispatchers.Main) { registeredUsers = users }
        }
    }

    /** Gọi từ CameraPreview để tạm dừng ML Kit khi mở Cài đặt */
    fun isAnalysisPaused(): Boolean = settingsOpenFlag

    fun openSettings() {
        settingsOpenFlag = true
        isSettingsOpen = true
    }

    fun closeSettings() {
        settingsOpenFlag = false
        isSettingsOpen = false
    }

    private fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun setStatus(status: AuthStatus, message: String) {
        postToMain { authResult = FaceAuthResult(status, message = message) }
    }

    /** Chạy trên luồng camera (imageProxy còn hợp lệ trong hàm này) */
    fun onFacesDetected(faces: List<Face>, width: Int, height: Int, imageProxy: ImageProxy) {
        if (settingsOpenFlag) return

        // 1. Kiểm tra vị trí khuôn mặt so với Oval (chỉ khi có đúng 1 mặt)
        val placement: Placement? = if (faces.size == 1 && canvasWidth > 0f && canvasHeight > 0f) {
            val faceRect = OverlayGeometry.mapToCanvas(
                faces[0].boundingBox, width, height, canvasWidth, canvasHeight, mirror = true
            )
            OverlayGeometry.checkPlacement(faceRect, OverlayGeometry.guideOval(canvasWidth, canvasHeight))
        } else null
        val isValid = placement == Placement.OK

        postToMain {
            faceFrame = FaceFrame(faces, width, height)
            hasFaceCandidate = isValid // mutableStateOf bỏ qua nếu giá trị không đổi
        }

        // 2. Phản hồi hướng dẫn cho người dùng
        when {
            faces.size >= 2 -> {
                faceWasValid = false
                setStatus(AuthStatus.MULTIPLE_FACES, "Cảnh báo: Có ${faces.size} người! Vui lòng chỉ đứng 1 người.")
                return
            }
            faces.isEmpty() -> {
                faceWasValid = false
                setStatus(AuthStatus.IDLE, "Vui lòng đưa mặt vào giữa khung Oval")
                return
            }
            placement == Placement.OFF_CENTER -> {
                faceWasValid = false
                setStatus(AuthStatus.IDLE, "Hãy đưa mặt vào giữa khung Oval")
                return
            }
            placement == Placement.TOO_FAR -> {
                faceWasValid = false
                setStatus(AuthStatus.IDLE, "Hãy tiến lại gần hơn")
                return
            }
            placement == Placement.TOO_CLOSE -> {
                faceWasValid = false
                setStatus(AuthStatus.IDLE, "Hãy lùi ra xa một chút")
                return
            }
        }

        // 3. Mặt vừa vào đúng vị trí → báo "Đang nhận diện" (Oval xanh dương)
        if (!faceWasValid) {
            faceWasValid = true
            setStatus(AuthStatus.DETECTED, "Đang nhận diện...")
        }

        // 4. THROTTLING: tối đa 1 lần suy luận / 500ms, không chồng lệnh
        val now = System.currentTimeMillis()
        if (now - lastInferenceTime < 500 || !isProcessing.compareAndSet(false, true)) return
        lastInferenceTime = now

        val frameBitmap = BitmapUtils.imageProxyToBitmap(imageProxy)
        if (frameBitmap == null) {
            isProcessing.set(false); return
        }
        val faceBitmap = BitmapUtils.cropFace(frameBitmap, faces[0].boundingBox)
        frameBitmap.recycle() // ảnh full-frame không giữ lại nữa → tiết kiệm RAM
        if (faceBitmap == null) {
            isProcessing.set(false); return
        }

        // Lưu bản sao ảnh mặt (đã đúng vị trí trong Oval) cho chức năng đăng ký
        synchronized(candidateLock) {
            lastFaceCrop?.recycle()
            lastFaceCrop = faceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = recognizer.verify(faceBitmap)
                withContext(Dispatchers.Main) {
                    // Bỏ kết quả nếu người dùng đã rời Oval hoặc đang mở Cài đặt
                    if (!settingsOpenFlag && faceWasValid) authResult = result
                }
            } finally {
                faceBitmap.recycle()
                isProcessing.set(false)
            }
        }
    }

    /** Đăng ký khuôn mặt gần nhất. TFLite chạy nền → bàn phím/UI không bị đơ. */
    fun registerCurrentFace(name: String, onDone: (Boolean) -> Unit) {
        val crop = synchronized(candidateLock) {
            lastFaceCrop?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (crop == null) {
            onDone(false); return
        }
        isRegistering = true
        viewModelScope.launch(Dispatchers.Default) {
            val ok = try {
                // UUID → không bị trùng (bản cũ dùng millis % 100000 có thể lặp lại)
                val userId = "USER_" + UUID.randomUUID().toString().take(8).uppercase()
                recognizer.registerFace(userId, name, crop) // có ghi file → đang ở luồng nền
            } catch (e: Exception) {
                false
            } finally {
                crop.recycle()
            }
            val users = recognizer.getRegisteredUsers()
            withContext(Dispatchers.Main) {
                isRegistering = false
                registeredUsers = users
                onDone(ok)
            }
        }
    }

    /** Xoá 1 người đã đăng ký (ghi file → luồng IO) */
    fun removeUser(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            recognizer.removeFace(id)
            val users = recognizer.getRegisteredUsers()
            withContext(Dispatchers.Main) { registeredUsers = users }
        }
    }

    fun setThreshold(threshold: Float) = recognizer.setThreshold(threshold)

    override fun onCleared() {
        super.onCleared()
        synchronized(candidateLock) {
            lastFaceCrop?.recycle()
            lastFaceCrop = null
        }
        recognizer.close()
    }
}
