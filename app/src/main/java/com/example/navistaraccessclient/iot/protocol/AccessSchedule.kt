package com.example.navistaraccessclient.iot.protocol

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lịch truy cập của 1 người (lấy từ push_biometric), tính theo GIỜ VIỆT NAM (UTC+7).
 * Ngoài lịch → access với FailedCode = NO_SCHEDULE.
 *
 * Luật đang áp dụng (cần xác nhận lại với GVHD — xem README):
 *  1. Ngày hiện tại phải nằm trong [FromDate, ToDate] (tính cả 2 đầu). Thiếu đầu nào → không giới hạn đầu đó.
 *  2. Nếu có LstTimes → CHỈ dùng LstTimes: hôm nay phải có trong danh sách và giờ hiện tại nằm trong 1 khoảng.
 *  3. Nếu không có LstTimes → dùng FromTime..ToTime mỗi ngày. Hỗ trợ ca qua đêm (VD 22:00 → 06:00).
 *     Thiếu giờ, hoặc FromTime == ToTime → cả ngày.
 *  4. ActiveDays: tài liệu chưa mô tả định dạng (mẫu là ""). Tạm hiểu là danh sách số 1..7
 *     (1 = Thứ Hai … 7 = Chủ Nhật, theo ISO) cách nhau bởi dấu phẩy. Rỗng hoặc không đọc được → mọi ngày.
 */
data class AccessSchedule(
    val fromDate: java.time.LocalDate? = null,
    val toDate: java.time.LocalDate? = null,
    val fromTime: LocalTime? = null,
    val toTime: LocalTime? = null,
    val activeDays: String = "",
    val lstTimes: List<DaySchedule> = emptyList()
) {
    fun isAllowed(now: Instant = Instant.now(), zone: ZoneId = IotTime.LAB_ZONE): Boolean {
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val time = local.toLocalTime()

        if (fromDate != null && today.isBefore(fromDate)) return false
        if (toDate != null && today.isAfter(toDate)) return false

        if (lstTimes.isNotEmpty()) {
            val entry = lstTimes.firstOrNull { it.day == today } ?: return false
            return entry.ranges.any { inRange(time, it.start, it.end) }
        }

        parseActiveDays(activeDays)?.let { days -> if (local.dayOfWeek !in days) return false }

        val s = fromTime; val e = toTime
        if (s == null || e == null || s == e) return true
        return inRange(time, s, e)
    }

    private fun inRange(t: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (!end.isBefore(start)) !t.isBefore(start) && !t.isAfter(end)   // ca thường: start ≤ t ≤ end
        else !t.isBefore(start) || !t.isAfter(end)                          // ca qua đêm

    companion object {
        val NEVER = AccessSchedule(toDate = LocalDate.MIN)
        fun parseActiveDays(s: String): Set<DayOfWeek>? {
            if (s.isBlank()) return null
            val nums = s.split(',', ';', ' ').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return null }
            if (nums.isEmpty() || nums.any { it !in 1..7 }) return null
            return nums.map { DayOfWeek.of(it) }.toSet()
        }
    }
}

object LstTimesJson {
    fun toJson(days: List<DaySchedule>): JSONArray = JSONArray().apply {
        days.forEach { d->
            val times = JSONArray()
            d.ranges.forEach { r ->
                times.put(JSONObject().put("Start", r.start.toString()).put("End", r.end.toString()))
            }
            put(JSONObject().put("Day", d.day.toString()).put("Time", times))
        }
    }
    fun parse(arr: JSONArray?): List<DaySchedule> {
        if (arr == null) return emptyList()
        val result = mutableListOf<DaySchedule>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val day = try { LocalDate.parse(d.optString("Day").take(10)) } catch (e: Exception) { continue }
            val ranges = mutableListOf<TimeRange>()
            val t = d.optJSONArray("Time")
            if (t != null) {
                for (j in 0 until t.length()) {
                    val r = t.optJSONObject(j) ?: continue
                    try {
                        ranges.add(TimeRange(LocalTime.parse(r.optString("Start")), LocalTime.parse(r.optString("End"))))
                    } catch (e: Exception) { }
                }
            }
            result.add(DaySchedule(day, ranges))
        }
        return result
    }
}
