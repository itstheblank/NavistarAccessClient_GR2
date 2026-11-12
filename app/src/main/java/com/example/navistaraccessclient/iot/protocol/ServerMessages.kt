package com.example.navistaraccessclient.iot.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/*
 * Bản tin SERVER → THIẾT BỊ (mục 5 của tài liệu).
 * Quy tắc chung: bỏ qua khoá lạ, chấp nhận khoá thiếu (checklist mục 7).
 */

/**
 * 5.1 push_config (retained). Có 2 dạng: ngắn (chỉ BioAuthType) và đầy đủ.
 * Chỉ giữ lại các khoá CÓ MẶT; khoá nào không có thì để null → bên áp dụng giữ giá trị cũ.
 */
data class PushConfig(
    val bioAuthType: JSONObject?,          // để dạng thô → BioAuthType.mergedWith() chỉ đè trường có mặt
    val identifyThreshold: Double?,
    val lockerTimeHoldMs: Long?,
    val oneIsOpen: Boolean?,
    val offlineMode: Boolean?,
    val ntpServer: String?,
    /** Toàn bộ JSON gốc (kể cả khoá app chưa dùng) → lưu lại để hiển thị/gỡ lỗi */
    val raw: JSONObject
) {
    companion object {
        fun parse(json: String): PushConfig {
            val o = JSONObject(json)
            return PushConfig(
                bioAuthType = o.optJSONObject("BioAuthType"),
                identifyThreshold = o.optDoubleOrNull("IDENTIFY_THRESHOLD"),
                lockerTimeHoldMs = o.optLongOrNull("LOCKER_TIME_HOLD"),
                oneIsOpen = o.optBoolOrNull("ONE_IS_OPEN"),
                offlineMode = o.optBoolOrNull("OFFLINE_MODE"),
                ntpServer = o.optStringOrNull("NTP_SERVER"),
                raw = o
            )
        }
    }
}

enum class CommandType { REMOTE_OPEN, EMERGENCY_OPEN, CLOSE_DOOR, ENABLE_DEVICE, DISABLE_DEVICE }

/** 5.2 command · QoS 2 */
data class ServerCommand(
    val macAddress: String?,
    val cmdId: Long,
    /** null nếu server gửi loại lệnh app chưa biết → vẫn phải trả command_resp */
    val cmdType: CommandType?,
    val rawCmdType: String,
    val cmdTime: Instant?,
    val cmdTimeoutSec: Long?
) {
    /** "Lệnh cũ hơn CmdTime + CmdTimeout có thể bỏ qua". Thiếu thông tin thời gian → coi như còn hạn. */
    fun isExpired(now: Instant = Instant.now()): Boolean {
        val t = cmdTime ?: return false
        val timeout = cmdTimeoutSec ?: return false
        return now.isAfter(t.plusSeconds(timeout))
    }

    companion object {
        fun parse(json: String): ServerCommand {
            val o = JSONObject(json)
            val raw = o.optString("CmdType")
            return ServerCommand(
                macAddress = o.optStringOrNull("MacAddress"),
                cmdId = o.optLong("CmdId", 0L),
                cmdType = CommandType.entries.firstOrNull { it.name == raw },
                rawCmdType = raw,
                cmdTime = IotTime.parseServerUtc(o.optStringOrNull("CmdTime")),
                cmdTimeoutSec = o.optLongOrNull("CmdTimeout")
            )
        }
    }
}

/** Một khoảng giờ trong LstTimes */
data class TimeRange(val start: LocalTime, val end: LocalTime)

/** Lịch theo từng ngày trong LstTimes */
data class DaySchedule(val day: LocalDate, val ranges: List<TimeRange>)

/**
 * 5.3 push_biometric — mới ĐỌC được, CHƯA áp dụng.
 * Template (FaceTemps) là định dạng của SDK khuôn mặt của Lab → chờ có SDK mới lưu/so khớp được.
 * Phần lịch truy cập thì dùng được ngay (xem AccessSchedule).
 */
data class PushBiometric(
    val cmdType: String,                // NEW_BIO | DELETE_BIO
    val bioId: Long,
    val doorId: Long?,
    val idNumber: String?,
    val fullName: String?,
    val schedule: AccessSchedule,
    val faceTemps: List<String>,
    val irFaceTemps: List<String>,
    val isNoTemplate: Boolean,
    val faceImg: String?,
    val syncEnded: Boolean,
    val isCheckIn: Boolean
) {
    companion object {
        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }

        private fun time(s: String?): LocalTime? = try { if (s.isNullOrBlank()) null else LocalTime.parse(s) } catch (e: Exception) { null }
        private fun date(s: String?): LocalDate? = try { if (s.isNullOrBlank()) null else LocalDate.parse(s.take(10)) } catch (e: Exception) { null }

        fun parse(json: String): PushBiometric {
            val o = JSONObject(json)
            val days = o.optJSONArray("LstTimes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val d = arr.optJSONObject(i) ?: return@mapNotNull null
                    val day = date(d.optStringOrNull("Day")) ?: return@mapNotNull null
                    val ranges = d.optJSONArray("Time")?.let { ta ->
                        (0 until ta.length()).mapNotNull { j ->
                            val r = ta.optJSONObject(j) ?: return@mapNotNull null
                            val s = time(r.optStringOrNull("Start")); val e = time(r.optStringOrNull("End"))
                            if (s != null && e != null) TimeRange(s, e) else null
                        }
                    } ?: emptyList()
                    DaySchedule(day, ranges)
                }
            } ?: emptyList()

            return PushBiometric(
                cmdType = o.optString("CmdType"),
                bioId = o.getLong("BioId"),
                doorId = o.optLongOrNull("DoorId"),
                idNumber = o.optStringOrNull("IdNumber"),
                fullName = o.optStringOrNull("FullName"),
                schedule = AccessSchedule(
                    fromDate = date(o.optStringOrNull("FromDate")),
                    toDate = date(o.optStringOrNull("ToDate")),
                    fromTime = time(o.optStringOrNull("FromTime")),
                    toTime = time(o.optStringOrNull("ToTime")),
                    activeDays = o.optString("ActiveDays", ""),
                    lstTimes = days
                ),
                faceTemps = strings(o.optJSONArray("FaceTemps")),
                irFaceTemps = strings(o.optJSONArray("IrFaceTemps")),
                isNoTemplate = o.optBoolean("IsNoTemplate", false),
                faceImg = o.optStringOrNull("FaceImg")?.takeIf { it.isNotEmpty() },
                syncEnded = o.optBoolean("SyncEnded", false),
                isCheckIn = o.optBoolean("IsCheckIn", false)
            )
        }
    }
}

/** 5.4 push_required_door (chưa dùng — anti-passback, chờ chốt phạm vi) */
data class PushRequiredDoor(val isConstrained: Boolean, val requiredDoorIds: List<Long>) {
    companion object {
        fun parse(json: String): PushRequiredDoor {
            val o = JSONObject(json)
            val a = o.optJSONArray("RequiredDoorIds")
            return PushRequiredDoor(
                o.optBoolean("IsConstrained", false),
                if (a == null) emptyList() else List(a.length()) { a.optLong(it) }
            )
        }
    }
}

/** 5.6 update_apk (chưa dùng) */
data class UpdateApk(val version: Long) {
    companion object { fun parse(json: String) = UpdateApk(JSONObject(json).optLong("Version")) }
}
