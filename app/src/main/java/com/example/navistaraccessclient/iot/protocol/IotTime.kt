package com.example.navistaraccessclient.iot.protocol

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Các định dạng thời gian theo tài liệu "Device MQTT Integration Guide":
 *  - DateTime gửi lên server: ISO 8601 UTC, VD "2026-10-22T03:15:00Z"
 *  - CmdTime / AccessTime trong notify_access_depend: "yyyy-MM-dd HH:mm:ss" UTC (KHÔNG phải ISO)
 *  - Lịch truy cập (FromTime/ToTime, LstTimes): giờ Việt Nam UTC+7 (đã xác nhận với GVHD)
 *
 * Dùng java.time (có sẵn từ API 26, thiết bị mục tiêu API 29) → không cần thư viện ngoài.
 */
object IotTime {
    /** Múi giờ của lịch truy cập */
    val LAB_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    private val SERVER_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** ISO 8601 UTC, bỏ phần mili-giây cho gọn: 2026-10-22T03:15:00Z */
    fun isoUtc(instant: Instant = Instant.now()): String =
        instant.truncatedTo(ChronoUnit.SECONDS).toString()

    /** Đọc DateTime ISO 8601 (chấp nhận cả có/không có mili-giây). Trả null nếu sai định dạng. */
    fun parseIso(text: String?): Instant? = try {
        if (text.isNullOrBlank()) null else Instant.parse(text)
    } catch (e: Exception) { null }

    /** Đọc "yyyy-MM-dd HH:mm:ss" theo UTC (CmdTime). Chấp nhận luôn ISO nếu server đổi định dạng. */
    fun parseServerUtc(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(text.trim(), SERVER_DATETIME).toInstant(ZoneOffset.UTC)
        } catch (e: Exception) {
            parseIso(text)
        }
    }
}
