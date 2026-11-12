package com.example.navistaraccessclient.iot.protocol

import org.json.JSONArray
import org.json.JSONObject

/*
 * Bản tin THIẾT BỊ → SERVER (mục 4 của tài liệu).
 * Tên khoá JSON là PascalCase, phân biệt hoa/thường — giữ đúng từng ký tự.
 *
 * Lưu ý org.json: put(key, null) sẽ XOÁ khoá. Tài liệu ghi rõ các trường null (VD "BioId": null)
 * nên dùng putNullable() để vẫn gửi khoá với giá trị JSON null.
 */

internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

internal fun JSONObject.optBoolOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

enum class DoorStatus { OPEN, CLOSE, EMERGENCYOPEN }

enum class Direction { IN, OUT;
    companion object {
        fun parse(s: String?): Direction? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

/** Mã lỗi khi IsAuthenSuccess = false (bảng ở mục 4.2) */
enum class FailedCode { NO_SCHEDULE, FACE_NOT_MATCH, FINGER_NOT_MATCH, IRIS_NOT_MATCH, TEMPLATE_NOT_MATCH }

/** Cấu hình xác thực hiện tại — dùng chung cho healthcheck và push_config */
data class BioAuthType(
    val isFace: Boolean = true,
    val isFinger: Boolean = false,
    val isIris: Boolean = false,
    val isIdCard: Boolean = false,
    val direction: Direction? = null,
    val oneIsOpen: Boolean = true,
    val oneIsOpenDoorState: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("IsFace", isFace)
        .put("IsFinger", isFinger)
        .put("IsIris", isIris)
        .put("IsIdCard", isIdCard)
        .putNullable("Direction", direction?.name)
        .put("OneIsOpen", oneIsOpen)
        .put("OneIsOpenDoorState", oneIsOpenDoorState)

    /** "Chỉ áp dụng các trường có mặt" (mục 5.1) → trường nào thiếu thì giữ giá trị cũ */
    fun mergedWith(o: JSONObject): BioAuthType = copy(
        isFace = o.optBoolOrNull("IsFace") ?: isFace,
        isFinger = o.optBoolOrNull("IsFinger") ?: isFinger,
        isIris = o.optBoolOrNull("IsIris") ?: isIris,
        isIdCard = o.optBoolOrNull("IsIdCard") ?: isIdCard,
        direction = if (o.has("Direction")) Direction.parse(o.optStringOrNull("Direction")) else direction,
        oneIsOpen = o.optBoolOrNull("OneIsOpen") ?: oneIsOpen,
        oneIsOpenDoorState = o.optBoolOrNull("OneIsOpenDoorState") ?: oneIsOpenDoorState
    )

    companion object {
        fun fromJson(o: JSONObject?): BioAuthType = if (o == null) BioAuthType() else BioAuthType().mergedWith(o)
    }
}

/** 4.1 iot/devices/healthcheck · QoS 0 · khoảng 30 giây/lần */
data class HealthCheckMsg(
    val macAddress: String,
    val ipAddress: String?,
    val deviceTime: String,
    val isDeviceDisable: Boolean,
    val isEmergencyOpen: Boolean,
    val doorStatus: DoorStatus?,
    val version: Long,
    val bioAuthType: BioAuthType
) {
    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .putNullable("IpAddress", ipAddress)
        .put("DeviceTime", deviceTime)
        .put("IsDeviceDisable", isDeviceDisable)
        .put("IsEmergencyOpen", isEmergencyOpen)
        .putNullable("DoorStatus", doorStatus?.name)
        .put("Version", version)
        .put("BioAuthType", bioAuthType.toJson())
        .toString()
}

/** 4.2 iot/devices/access · QoS 2 · 1 bản tin / 1 lượt xác thực (thành công hay thất bại) */
data class AccessMsg(
    val macAddress: String,
    val bioId: Long?,
    val idNumber: String?,
    /** Khoá chống trùng khi gửi lại → GIỮ NGUYÊN giá trị gốc khi resend */
    val accessTime: String,
    val direction: Direction?,
    val authenType: String = "face",
    val isAuthenSuccess: Boolean,
    val failedCode: FailedCode? = null,
    val failedReason: String? = null,
    val abnormal: Boolean = false,
    val doorStatus: DoorStatus?,
    val faceImg: String? = null,
    val isResend: Boolean = false,
    val rgbScore: Double? = null,
    val irScore: Double? = null
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("MacAddress", macAddress)
        .putNullable("BioId", bioId)
        .putNullable("IdNumber", idNumber)
        .put("AccessTime", accessTime)
        .putNullable("Direction", direction?.name)
        .put("AuthenType", authenType)
        .put("IsAuthenSuccess", isAuthenSuccess)
        .putNullable("FailedCode", failedCode?.name)
        .putNullable("FailedReason", failedReason)
        .put("Abnormal", abnormal)
        .putNullable("DoorStatus", doorStatus?.name)
        .putNullable("FaceImg", faceImg)
        .putNullable("FingerImg", null)
        .putNullable("IrisImg", null)
        .put("IsResend", isResend)
        .putNullable("RgbScore", rgbScore?.let { Math.round(it * 1000) / 1000.0 })
        .putNullable("IrScore", irScore)
        .put("RgbTemplates", "")
        .put("IrTemplates", "")

    fun toJson(): String = toJsonObject().toString()

    companion object {
        /** Đọc lại từ hàng đợi offline */
        fun fromJson(json: String): AccessMsg {
            val o = JSONObject(json)
            return AccessMsg(
                macAddress = o.getString("MacAddress"),
                bioId = o.optLongOrNull("BioId"),
                idNumber = o.optStringOrNull("IdNumber"),
                accessTime = o.getString("AccessTime"),
                direction = Direction.parse(o.optStringOrNull("Direction")),
                authenType = o.optString("AuthenType", "face"),
                isAuthenSuccess = o.getBoolean("IsAuthenSuccess"),
                failedCode = o.optStringOrNull("FailedCode")?.let { c -> FailedCode.entries.firstOrNull { it.name == c } },
                failedReason = o.optStringOrNull("FailedReason"),
                abnormal = o.optBoolean("Abnormal", false),
                doorStatus = o.optStringOrNull("DoorStatus")?.let { s -> DoorStatus.entries.firstOrNull { it.name == s } },
                faceImg = o.optStringOrNull("FaceImg"),
                isResend = o.optBoolean("IsResend", false),
                rgbScore = o.optDoubleOrNull("RgbScore"),
                irScore = o.optDoubleOrNull("IrScore")
            )
        }
    }
}

/** 4.3 iot/devices/doorstatus · QoS 1 · khi cửa đổi trạng thái (chỉ OPEN | CLOSE) */
data class DoorStatusMsg(
    val macAddress: String,
    val doorStatus: DoorStatus,
    val deviceTime: String,
    val abnormal: Boolean = false
) {
    init { require(doorStatus != DoorStatus.EMERGENCYOPEN) { "doorstatus chỉ nhận OPEN/CLOSE" } }

    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .put("DoorStatus", doorStatus.name)
        .put("DeviceTime", deviceTime)
        .put("Abnormal", abnormal)
        .toString()
}

/** 4.4 iot/devices/command_resp · QoS 2 · trả lời MỌI lệnh nhận được */
data class CommandRespMsg(val macAddress: String, val cmdId: Long, val deviceTime: String) {
    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .put("CmdId", cmdId)
        .put("DeviceTime", deviceTime)
        .toString()
}

/** 4.5 iot/devices/device_sync_bio · QoS 2 (chưa dùng — chờ SDK khuôn mặt của Lab) */
data class DeviceSyncBioMsg(val macAddress: String, val doorId: Long?, val bioIds: List<Long>) {
    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .putNullable("DoorId", doorId)
        .put("LstBioId", JSONArray(bioIds))
        .toString()
}

/** 4.6 iot/devices/device_received_bio · QoS 2 (chưa dùng) */
data class DeviceReceivedBioMsg(
    val macAddress: String, val bioId: Long, val doorId: Long?, val cmdType: String, val deviceTime: String
) {
    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .put("BioId", bioId)
        .putNullable("DoorId", doorId)
        .put("CmdType", cmdType)
        .put("DeviceTime", deviceTime)
        .toString()
}

/** 4.8 iot/devices/setting_changed · QoS 1 · OldSettings/NewSettings là CHUỖI chứa JSON */
data class SettingChangedMsg(
    val macAddress: String, val deviceTime: String, val oldSettings: JSONObject, val newSettings: JSONObject
) {
    fun toJson(): String = JSONObject()
        .put("MacAddress", macAddress)
        .put("DeviceTime", deviceTime)
        .put("OldSettings", oldSettings.toString())
        .put("NewSettings", newSettings.toString())
        .toString()
}
