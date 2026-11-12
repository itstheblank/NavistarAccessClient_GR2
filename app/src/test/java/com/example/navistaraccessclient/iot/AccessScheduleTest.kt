package com.example.navistaraccessclient.iot

import com.example.navistaraccessclient.iot.protocol.AccessSchedule
import com.example.navistaraccessclient.iot.protocol.DaySchedule
import com.example.navistaraccessclient.iot.protocol.TimeRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId

/** Lịch truy cập tính theo giờ Việt Nam (UTC+7) */
class AccessScheduleTest {

    private val vn = ZoneId.of("Asia/Ho_Chi_Minh")
    /** Tạo thời điểm theo GIỜ VIỆT NAM cho dễ đọc */
    private fun vnTime(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), vn).toInstant()

    private val officeHours = AccessSchedule(
        fromDate = LocalDate.parse("2026-10-22"), toDate = LocalDate.parse("2026-10-31"),
        fromTime = LocalTime.parse("08:00:00"), toTime = LocalTime.parse("17:30:00")
    )

    @Test fun insideWindow() = assertTrue(officeHours.isAllowed(vnTime("2026-10-25T09:00:00")))
    @Test fun endTimeInclusive() = assertTrue(officeHours.isAllowed(vnTime("2026-10-25T17:30:00")))
    @Test fun afterHours() = assertFalse(officeHours.isAllowed(vnTime("2026-10-25T18:00:00")))
    @Test fun beforeFromDate() = assertFalse(officeHours.isAllowed(vnTime("2026-10-21T09:00:00")))
    @Test fun toDateInclusive() = assertTrue(officeHours.isAllowed(vnTime("2026-10-31T09:00:00")))
    @Test fun afterToDate() = assertFalse(officeHours.isAllowed(vnTime("2026-11-01T09:00:00")))

    /** 07:30 giờ VN = 00:30 UTC. Nếu lỡ tính theo UTC sẽ ra kết quả sai → test này bắt lỗi múi giờ */
    @Test fun usesVietnamTimeNotUtc() {
        assertFalse(officeHours.isAllowed(vnTime("2026-10-25T07:30:00")))
        // 08:30 giờ VN = 01:30 UTC → vẫn phải được phép
        assertTrue(officeHours.isAllowed(vnTime("2026-10-25T08:30:00")))
    }

    @Test fun overnightShift() {
        val night = AccessSchedule(fromTime = LocalTime.parse("22:00:00"), toTime = LocalTime.parse("06:00:00"))
        assertTrue(night.isAllowed(vnTime("2026-10-25T23:00:00")))
        assertTrue(night.isAllowed(vnTime("2026-10-26T05:59:00")))
        assertFalse(night.isAllowed(vnTime("2026-10-26T12:00:00")))
    }

    @Test fun lstTimesOverridesDailyWindow() {
        val s = officeHours.copy(lstTimes = listOf(
            DaySchedule(LocalDate.parse("2026-10-22"), listOf(TimeRange(LocalTime.parse("08:00"), LocalTime.parse("11:00"))))
        ))
        assertTrue(s.isAllowed(vnTime("2026-10-22T10:00:00")))
        assertFalse("ngoài khoảng của LstTimes", s.isAllowed(vnTime("2026-10-22T14:00:00")))
        assertFalse("ngày không có trong LstTimes", s.isAllowed(vnTime("2026-10-23T10:00:00")))
    }

    @Test fun noScheduleMeansAlways() = assertTrue(AccessSchedule().isAllowed(vnTime("2026-10-25T03:00:00")))

    @Test fun activeDaysParsing() {
        assertTrue(AccessSchedule.parseActiveDays("") == null)
        assertTrue(AccessSchedule.parseActiveDays("1,2,3,4,5") == setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        assertTrue("định dạng lạ → không giới hạn", AccessSchedule.parseActiveDays("Mon,Tue") == null)
    }

    @Test fun activeDaysBlocksWeekend() {
        val weekdays = AccessSchedule(activeDays = "1,2,3,4,5")
        assertTrue(weekdays.isAllowed(vnTime("2026-11-13T10:00:00")))   // Thứ Sáu
        assertFalse(weekdays.isAllowed(vnTime("2026-11-14T10:00:00")))  // Thứ Bảy
    }
}
