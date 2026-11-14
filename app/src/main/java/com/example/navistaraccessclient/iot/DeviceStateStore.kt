package com.example.navistaraccessclient.iot

import android.content.Context
import com.example.navistaraccessclient.iot.protocol.BioAuthType
import com.example.navistaraccessclient.iot.protocol.Direction
import org.json.JSONObject

/**
 * Trạng thái thiết bị PHẢI giữ qua khởi động lại (checklist mục 7 của tài liệu):
 * DoorId, Direction (nằm trong BioAuthType), trạng thái enable / emergency.
 * Server kiểm tra các giá trị này trong mỗi healthcheck và sẽ gửi lệnh sửa nếu lệch.
 */
data class DeviceState(
    val doorId: Long? = null,
    val bioAuthType: BioAuthType = BioAuthType(),
    val isDeviceDisable: Boolean = false,
    val isEmergencyOpen: Boolean = false,
    val lockerTimeHoldMs: Long = 3000L,
    val serverThreshold: Double? = null,
    /** push_config gần nhất (JSON gốc) — để xem trên màn hình Cài đặt / gỡ lỗi */
    val lastConfigJson: String? = null
) {
    val direction: Direction? get() = bioAuthType.direction
}

class DeviceStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_state", Context.MODE_PRIVATE)

    fun load(): DeviceState {
        val d = DeviceState()
        val bio = prefs.getString("bio_auth_type", null)?.let {
            try { BioAuthType.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        } ?: d.bioAuthType
        return DeviceState(
            doorId = if (prefs.contains("door_id")) prefs.getLong("door_id", 0L) else null,
            bioAuthType = bio,
            isDeviceDisable = prefs.getBoolean("is_disable", d.isDeviceDisable),
            isEmergencyOpen = prefs.getBoolean("is_emergency", d.isEmergencyOpen),
            lockerTimeHoldMs = prefs.getLong("locker_time_hold", d.lockerTimeHoldMs),
            serverThreshold = if (prefs.contains("server_threshold")) prefs.getFloat("server_threshold", 0f).toDouble() else null,
            lastConfigJson = prefs.getString("last_config", null)
        )
    }

    /** commit() (ghi đồng bộ) thay vì apply(): trạng thái enable/emergency phải chắc chắn xuống đĩa trước khi báo lại server */
    fun save(s: DeviceState) {
        prefs.edit().apply {
            if (s.doorId != null) putLong("door_id", s.doorId) else remove("door_id")
            putString("bio_auth_type", s.bioAuthType.toJson().toString())
            putBoolean("is_disable", s.isDeviceDisable)
            putBoolean("is_emergency", s.isEmergencyOpen)
            putLong("locker_time_hold", s.lockerTimeHoldMs)
            if (s.serverThreshold != null) putFloat("server_threshold", s.serverThreshold.toFloat()) else remove("server_threshold")
            if (s.lastConfigJson != null) putString("last_config", s.lastConfigJson) else remove("last_config")
        }.commit()
    }
}
