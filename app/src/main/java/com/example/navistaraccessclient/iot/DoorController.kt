package com.example.navistaraccessclient.iot

import com.example.navistaraccessclient.iot.protocol.DoorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Điều khiển khoá cửa VẬT LÝ (chỉ OPEN / CLOSE).
 * Chế độ khẩn cấp do DeviceAgent quản lý qua DeviceState.isEmergencyOpen.
 *
 * Hiện chưa biết tablet nối relay bằng cách nào (câu hỏi đang chờ GVHD) →
 * dùng [SimulatedDoorController]. Khi có phần cứng chỉ cần viết 1 class mới implement interface này.
 */
interface DoorController {
    val state: StateFlow<DoorStatus>
    /** Mở cửa [holdMs] mili-giây rồi tự đóng (LOCKER_TIME_HOLD trong push_config) */
    fun pulseOpen(holdMs: Long)
    /** Mở và giữ nguyên (EMERGENCY_OPEN) */
    fun holdOpen()
    fun close()
}

class SimulatedDoorController(private val scope: CoroutineScope) : DoorController {
    private val _state = MutableStateFlow(DoorStatus.CLOSE)
    override val state: StateFlow<DoorStatus> = _state.asStateFlow()
    private var closeJob: Job? = null

    @Synchronized
    override fun pulseOpen(holdMs: Long) {
        closeJob?.cancel()
        _state.value = DoorStatus.OPEN
        closeJob = scope.launch {
            delay(holdMs.coerceIn(500L, 60_000L))
            _state.value = DoorStatus.CLOSE
        }
    }

    @Synchronized
    override fun holdOpen() {
        closeJob?.cancel(); closeJob = null
        _state.value = DoorStatus.OPEN
    }

    @Synchronized
    override fun close() {
        closeJob?.cancel(); closeJob = null
        _state.value = DoorStatus.CLOSE
    }
}
