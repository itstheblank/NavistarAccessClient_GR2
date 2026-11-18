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
import com.example.navistaraccessclient.iot.ConnState
import com.example.navistaraccessclient.iot.DeviceAgent
import com.example.navistaraccessclient.iot.DeviceConfig
import com.example.navistaraccessclient.iot.DeviceState
import com.example.navistaraccessclient.iot.protocol.DoorStatus
import com.example.navistaraccessclient.iot.protocol.AccessSchedule
import com.example.navistaraccessclient.iot.protocol.PushBiometric
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
 *  - [GĐ3] Toàn bộ việc MQTT nằm trong DeviceAgent (luồng riêng). ViewModel chỉ đọc trạng thái
 *    và báo kết quả nhận diện qua AccessEventGate → DeviceAgent.reportAccess().
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    // Người dùng đã đăng ký được lưu xuống file (FaceStore) → mở lại app vẫn còn
    private val recognizer = TFLiteFaceRecognizer(application, store = FaceStore(application))

    /** Lấy vector khuôn mặt từ FaceImg của server */
    private val enroller = FaceImageEnroller(recognizer)

    // ---------------- [GĐ3] IoT / MQTT ----------------
    private val deviceAgent = DeviceAgent(application, viewModelScope)
    /** Mỗi lượt người đứng trước cửa → đúng 1 bản tin access (không spam 2 lần/giây) */
    private val eventGate = AccessEventGate()

    var connState by mutableStateOf<ConnState>(ConnState.Disabled)
        private set
    var deviceConfig by mutableStateOf(deviceAgent.config.value)
        private set
    var deviceState by mutableStateOf(DeviceState())
        private set
    var doorStatus by mutableStateOf(DoorStatus.CLOSE)
        private set
    /** Sự kiện IoT gần nhất (hiện khi bật chế độ debug) */
    var lastIotEvent by mutableStateOf<String?>(null)
        private set

    /** Kết quả push_biometric gần nhất (hiện khi bật chế độ debug) */
    var lastBioEvent by mutableStateOf<String?>(null)
        private set

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

        // [GĐ3] Đồng bộ StateFlow của DeviceAgent sang Compose State (collect chạy trên Main)
        viewModelScope.launch { deviceAgent.connState.collect { connState = it } }
        viewModelScope.launch { deviceAgent.config.collect { deviceConfig = it } }
        viewModelScope.launch { deviceAgent.door.state.collect { doorStatus = it } }
        viewModelScope.launch { deviceAgent.lastEvent.collect { lastIotEvent = it } }
        viewModelScope.launch { deviceAgent.lastBioEvent.collect { lastBioEvent = it } }
        viewModelScope.launch {
            deviceAgent.state.collect { s ->
                deviceState = s
                // Chỉ áp ngưỡng của server khi người dùng bật (chưa rõ thang đo của Lab)
                val t = s.serverThreshold
                if (deviceAgent.config.value.applyServerThreshold && t != null) recognizer.setThreshold(t.toFloat())
            }
        }
        deviceAgent.biometricHandler = { bio -> applyBiometric(bio) }
        deviceAgent.start()
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
        eventGate.resetStreak()
    }

    private fun postToMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun setStatus(status: AuthStatus, message: String) {
        postToMain { authResult = FaceAuthResult(status, message = message) }
    }

    /** Mặt rời Oval → lượt sau phải xác nhận lại từ đầu (AccessEventGate) */
    private fun markFaceInvalid() {
        if (faceWasValid) {
            faceWasValid = false
            postToMain { eventGate.resetStreak() }
        }
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
                markFaceInvalid()
                setStatus(AuthStatus.MULTIPLE_FACES, "Cảnh báo: Có ${faces.size} người! Vui lòng chỉ đứng 1 người.")
                return
            }
            faces.isEmpty() -> {
                markFaceInvalid()
                setStatus(AuthStatus.IDLE, "Vui lòng đưa mặt vào giữa khung Oval")
                return
            }
            placement == Placement.OFF_CENTER -> {
                markFaceInvalid()
                setStatus(AuthStatus.IDLE, "Hãy đưa mặt vào giữa khung Oval")
                return
            }
            placement == Placement.TOO_FAR -> {
                markFaceInvalid()
                setStatus(AuthStatus.IDLE, "Hãy tiến lại gần hơn")
                return
            }
            placement == Placement.TOO_CLOSE -> {
                markFaceInvalid()
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
                    if (!settingsOpenFlag && faceWasValid) {
                        // [GĐ3] Thiết bị bị server khoá (DISABLE_DEVICE) → không chào mừng
                        authResult = if (deviceState.isDeviceDisable && result.status == AuthStatus.SUCCESS)
                            result.copy(status = AuthStatus.FAILED, message = "Thiết bị đang bị vô hiệu hoá")
                        else result
                        eventGate.submit(result)?.let { reportAccess(it) }
                    }
                }
            } finally {
                faceBitmap.recycle()
                isProcessing.set(false)
            }
        }
    }

    /** [GĐ3] Gửi 1 lượt ra vào (Main). Người từ server → kèm BioId/IdNumber thật + kiểm tra LstTimes. */
    private fun reportAccess(result: FaceAuthResult) {
        val user = result.userId?.let { id -> registeredUsers.firstOrNull { it.id == id } }
        viewModelScope.launch {
            val decision = deviceAgent.reportAccess(
                result,
                bioId = user?.bioId,
                idNumber = user?.idNumber,
                schedule = user?.let { scheduleOf(it) }
            )
            if (!decision.granted && decision.message != null && faceWasValid) {
                authResult = result.copy(status = AuthStatus.FAILED, message = decision.message)
            }
        }
    }

    /**
     * Theo yêu cầu GVHD: chỉ dùng LstTimes.
     * - Người đăng ký tại máy → không kiểm tra lịch.
     * - Người từ server mà LstTimes rỗng → TỪ CHỐI (an toàn, chờ GVHD xác nhận).
     */
    private fun scheduleOf(user: RegisteredUser): AccessSchedule? {
        val days = user.lstTimes ?: return null
        return if (days.isEmpty()) AccessSchedule.NEVER else AccessSchedule(lstTimes = days)
    }

    /** [GĐ3] Áp dụng push_biometric. null = thành công (DeviceAgent sẽ ACK), khác null = lý do lỗi. */
    private suspend fun applyBiometric(bio: PushBiometric): String? = withContext(Dispatchers.Default) {
        val id = RegisteredUser.serverId(bio.bioId)
        val error: String? = when (bio.cmdType) {
            "DELETE_BIO" -> {
                recognizer.removeFace(id) // xoá người không tồn tại vẫn coi là xong
                null
            }
            "NEW_BIO" -> {
                val img = bio.faceImg
                if (img == null) {
                    "Thiếu FaceImg"
                } else {
                    when (val r = enroller.extract(img)) {
                        is FaceImageEnroller.Result.Fail -> r.reason
                        is FaceImageEnroller.Result.Ok -> {
                            recognizer.upsertUser(
                                RegisteredUser(
                                    id = id,
                                    name = bio.fullName ?: "BioId ${bio.bioId}",
                                    embedding = r.embedding,
                                    bioId = bio.bioId,
                                    idNumber = bio.idNumber,
                                    lstTimes = bio.schedule.lstTimes
                                )
                            )
                            null
                        }
                    }
                }
            }
            else -> "CmdType lạ: ${bio.cmdType}"
        }
        val users = recognizer.getRegisteredUsers()
        withContext(Dispatchers.Main) { registeredUsers = users }
        error
    }

    // ---------------- [GĐ3] Cấu hình thiết bị ----------------

    fun saveDeviceConfig(config: DeviceConfig) = deviceAgent.updateConfig(config)

    fun generateMac(): String = deviceAgent.regenerateMac()

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
        deviceAgent.shutdown()
        synchronized(candidateLock) {
            lastFaceCrop?.recycle()
            lastFaceCrop = null
        }
        recognizer.close()
    }
}
