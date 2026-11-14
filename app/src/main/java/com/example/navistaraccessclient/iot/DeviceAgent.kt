package com.example.navistaraccessclient.iot

import android.content.Context
import android.util.Log
import com.example.navistaraccessclient.iot.protocol.AccessMsg
import com.example.navistaraccessclient.iot.protocol.AccessSchedule
import com.example.navistaraccessclient.iot.protocol.CommandRespMsg
import com.example.navistaraccessclient.iot.protocol.CommandType
import com.example.navistaraccessclient.iot.protocol.DoorStatus
import com.example.navistaraccessclient.iot.protocol.DoorStatusMsg
import com.example.navistaraccessclient.iot.protocol.FailedCode
import com.example.navistaraccessclient.iot.protocol.HealthCheckMsg
import com.example.navistaraccessclient.iot.protocol.IotTime
import com.example.navistaraccessclient.iot.protocol.PushBiometric
import com.example.navistaraccessclient.iot.protocol.PushConfig
import com.example.navistaraccessclient.iot.protocol.ServerCommand
import com.example.navistaraccessclient.iot.protocol.Topics
import com.example.navistaraccessclient.model.AuthStatus
import com.example.navistaraccessclient.model.FaceAuthResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Kết quả quyết định cho 1 lượt quẹt mặt — ViewModel dùng để hiển thị */
data class AccessDecision(val granted: Boolean, val message: String?, val sentOnline: Boolean)

/**
 * "Bộ não" phía IoT: nối kết nối MQTT, trạng thái thiết bị, cửa và luồng nhận diện.
 *
 * Phạm vi bản này (làm được khi CHƯA có SDK khuôn mặt của Lab):
 *  ✅ healthcheck mỗi 30 s
 *  ✅ push_config: áp dụng các trường có mặt, lưu lại
 *  ✅ command: 5 lệnh + command_resp, bỏ lệnh quá hạn, chống xử lý trùng CmdId
 *  ✅ doorstatus khi cửa (mô phỏng) đổi trạng thái
 *  ✅ access cho mỗi lượt quẹt mặt, lưu offline + gửi lại IsResend
 *  ⏸ push_biometric / device_sync_bio / device_received_bio: CHỜ SDK (chỉ đọc + ghi log, KHÔNG ack,
 *     vì ack nghĩa là báo server "đã lưu xong" trong khi app chưa lưu được template)
 *  ⏸ push_required_door / notify_access_depend / update_apk / setting_changed: chờ chốt phạm vi
 *
 * Mọi xử lý trạng thái chạy tuần tự trên 1 luồng riêng ([worker]) → không cần khoá.
 */
class DeviceAgent(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "DeviceAgent"
        private const val HEALTHCHECK_INTERVAL_MS = 30_000L
    }

    private val worker: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val configStore = DeviceConfigStore(context)
    private val stateStore = DeviceStateStore(context)
    private val offlineQueue = OfflineAccessQueue(context)
    val door: DoorController = SimulatedDoorController(scope)

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<DeviceConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(stateStore.load())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** 1 dòng mô tả sự kiện IoT gần nhất — hiển thị ở chế độ debug */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    private val recentCmdIds = ArrayDeque<Long>()

    private val connection = MqttV5Connection(
        scope = scope,
        onMessage = { topic, payload -> scope.launch(worker) { handleServerMessage(topic, payload) } },
        onConnected = { scope.launch(worker) { onConnectedWork() } }
    )
    val connState: StateFlow<ConnState> get() = connection.state

    private val mac: String get() = _config.value.macAddress

    fun start() {
        connection.start(_config.value)

        // Healthcheck định kỳ (chỉ gửi khi đang kết nối)
        scope.launch(worker) {
            while (true) {
                delay(HEALTHCHECK_INTERVAL_MS)
                if (connection.isConnected) publishHealthcheck()
            }
        }
        // Cửa đổi trạng thái → doorstatus (bỏ giá trị đầu tiên lúc khởi tạo)
        scope.launch(worker) {
            door.state.drop(1).collect { s ->
                publishOrLog(Topics.DOOR_STATUS, DoorStatusMsg(mac, s, IotTime.isoUtc()).toJson(), Topics.QOS_DOOR_STATUS)
                // Báo cả healthcheck để server thấy DoorStatus mới ngay
                if (connection.isConnected) publishHealthcheck()
            }
        }
    }

    fun updateConfig(newConfig: DeviceConfig) {
        configStore.save(newConfig)
        _config.value = configStore.load()
        connection.start(_config.value)
    }

    fun regenerateMac(): String = DeviceConfig.generateMac()

    // ------------------------------------------------------------------ Kết nối

    private fun onConnectedWork() {
        publishHealthcheck()
        val n = offlineQueue.drain { msg -> connection.publish(Topics.ACCESS, msg.toJson(), Topics.QOS_ACCESS) }
        if (n > 0) event("Đã gửi lại $n lượt ra vào lưu lúc offline")
    }

    private fun publishHealthcheck() {
        val s = _state.value
        val doorNow = if (s.isEmergencyOpen) DoorStatus.EMERGENCYOPEN else door.state.value
        val msg = HealthCheckMsg(
            macAddress = mac,
            ipAddress = NetworkUtils.currentIpv4(context),
            deviceTime = IotTime.isoUtc(),
            isDeviceDisable = s.isDeviceDisable,
            isEmergencyOpen = s.isEmergencyOpen,
            doorStatus = doorNow,
            version = DeviceConfig.APP_VERSION,
            bioAuthType = s.bioAuthType
        )
        connection.publish(Topics.HEALTHCHECK, msg.toJson(), Topics.QOS_HEALTHCHECK)
    }

    private fun publishOrLog(topic: String, json: String, qos: Int) {
        if (!connection.publish(topic, json, qos)) Log.w(TAG, "Offline, bỏ qua $topic")
    }

    // ------------------------------------------------------------------ Bản tin từ server

    private fun handleServerMessage(topic: String, payload: String) {
        val type = Topics.parseServerTopic(topic, mac)
        if (type == null) { Log.w(TAG, "Topic lạ: $topic"); return }
        try {
            when (type) {
                Topics.ServerType.PUSH_CONFIG -> applyPushConfig(PushConfig.parse(payload))
                Topics.ServerType.COMMAND -> handleCommand(ServerCommand.parse(payload))
                Topics.ServerType.PUSH_BIOMETRIC -> {
                    val bio = PushBiometric.parse(payload)
                    // Lưu DoorId ngay (tài liệu: "the door this device controls; store it")
                    if (bio.doorId != null && bio.doorId != _state.value.doorId) {
                        setState(_state.value.copy(doorId = bio.doorId))
                    }
                    event("Nhận push_biometric ${bio.cmdType} BioId=${bio.bioId} (${bio.fullName}) — CHƯA áp dụng, chờ SDK Lab")
                }
                Topics.ServerType.PUSH_REQUIRED_DOOR,
                Topics.ServerType.NOTIFY_ACCESS_DEPEND,
                Topics.ServerType.UPDATE_APK -> event("Nhận ${type.wire} — chưa hỗ trợ: $payload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bản tin ${type.wire} sai định dạng: $payload", e)
            event("Bản tin ${type.wire} sai định dạng")
        }
    }

    private fun applyPushConfig(cfg: PushConfig) {
        val old = _state.value
        val updated = old.copy(
            bioAuthType = cfg.bioAuthType?.let { old.bioAuthType.mergedWith(it) } ?: old.bioAuthType,
            lockerTimeHoldMs = cfg.lockerTimeHoldMs ?: old.lockerTimeHoldMs,
            serverThreshold = cfg.identifyThreshold ?: old.serverThreshold,
            lastConfigJson = cfg.raw.toString()
        )
        setState(updated)
        event("Nhận push_config: Direction=${updated.direction ?: "chưa có"}, giữ cửa ${updated.lockerTimeHoldMs}ms")
        // Gửi healthcheck ngay để server thấy BioAuthType đã khớp (tránh bị push_config lặp lại)
        if (connection.isConnected) publishHealthcheck()
    }

    private fun handleCommand(cmd: ServerCommand) {
        val duplicate = cmd.cmdId != 0L && cmd.cmdId in recentCmdIds
        when {
            duplicate -> Log.i(TAG, "CmdId ${cmd.cmdId} đã xử lý → chỉ trả lời lại")
            cmd.isExpired() -> event("Bỏ lệnh ${cmd.rawCmdType} #${cmd.cmdId}: đã quá hạn")
            cmd.cmdType == null -> event("Lệnh lạ '${cmd.rawCmdType}' — bỏ qua")
            else -> {
                executeCommand(cmd.cmdType)
                event("Thực hiện lệnh ${cmd.cmdType} #${cmd.cmdId}")
            }
        }
        if (cmd.cmdId != 0L && !duplicate) {
            recentCmdIds.addLast(cmd.cmdId)
            while (recentCmdIds.size > 50) recentCmdIds.removeFirst()
        }
        // LUÔN trả lời, kể cả lệnh trùng / quá hạn / không hiểu
        connection.publish(Topics.COMMAND_RESP, CommandRespMsg(mac, cmd.cmdId, IotTime.isoUtc()).toJson(), Topics.QOS_COMMAND_RESP)
    }

    private fun executeCommand(type: CommandType) {
        val s = _state.value
        when (type) {
            // Lệnh mở từ xa của quản trị viên → vẫn thực hiện kể cả khi thiết bị đang disable (cần xác nhận với Lab)
            CommandType.REMOTE_OPEN -> if (!s.isEmergencyOpen) door.pulseOpen(s.lockerTimeHoldMs)
            CommandType.EMERGENCY_OPEN -> { setState(s.copy(isEmergencyOpen = true)); door.holdOpen() }
            CommandType.CLOSE_DOOR -> { setState(s.copy(isEmergencyOpen = false)); door.close() }
            CommandType.ENABLE_DEVICE -> setState(s.copy(isDeviceDisable = false))
            CommandType.DISABLE_DEVICE -> setState(s.copy(isDeviceDisable = true))
        }
        if (connection.isConnected) publishHealthcheck()
    }

    // ------------------------------------------------------------------ Lượt quẹt mặt

    /**
     * Gọi khi AccessEventGate xác nhận 1 lượt (SUCCESS hoặc FAILED).
     * Quyết định mở cửa + gửi bản tin access (hoặc lưu offline).
     *
     * @param bioId / idNumber: hiện người dùng đăng ký tại máy CHƯA có BioId của server → null.
     *        Khi có SDK + đồng bộ push_biometric sẽ truyền giá trị thật.
     * @param schedule: lịch của người đó (từ push_biometric). null = không kiểm tra lịch.
     */
    suspend fun reportAccess(
        result: FaceAuthResult,
        bioId: Long? = null,
        idNumber: String? = null,
        schedule: AccessSchedule? = null
    ): AccessDecision = withContext(worker) {
        val s = _state.value
        val matched = result.status == AuthStatus.SUCCESS
        val inSchedule = schedule?.isAllowed() ?: true

        var failedCode: FailedCode? = null
        var failedReason: String? = null
        var userMessage: String? = null
        val granted = when {
            s.isDeviceDisable -> {
                failedReason = "DEVICE_DISABLED"; userMessage = "Thiết bị đang bị vô hiệu hoá"; false
            }
            !matched -> { failedCode = FailedCode.FACE_NOT_MATCH; false }
            !inSchedule -> {
                failedCode = FailedCode.NO_SCHEDULE; userMessage = "${result.userName}: ngoài khung giờ được phép"; false
            }
            else -> true
        }

        if (granted && !s.isEmergencyOpen) door.pulseOpen(s.lockerTimeHoldMs)

        val msg = AccessMsg(
            macAddress = mac,
            bioId = if (matched) bioId else null,
            idNumber = if (matched) idNumber else null,
            accessTime = IotTime.isoUtc(),
            direction = s.direction,
            isAuthenSuccess = granted,
            failedCode = failedCode,
            failedReason = failedReason,
            abnormal = !matched,              // "true = the person could not be identified"
            doorStatus = door.state.value,
            rgbScore = result.confidence.toDouble()
        )
        val online = connection.publish(Topics.ACCESS, msg.toJson(), Topics.QOS_ACCESS)
        if (!online) offlineQueue.add(msg)
        event("access ${if (granted) "OK" else "FAIL ${failedCode ?: failedReason}"} " +
              "(${result.userName ?: "người lạ"}) → ${if (online) "đã gửi" else "lưu offline (${offlineQueue.size()})"}")
        AccessDecision(granted, userMessage, online)
    }

    // ------------------------------------------------------------------

    private fun setState(s: DeviceState) {
        _state.value = s
        stateStore.save(s)
    }

    private fun event(text: String) {
        Log.i(TAG, text)
        _lastEvent.value = text
    }

    fun shutdown() {
        connection.shutdown()
        worker.close()
    }
}
