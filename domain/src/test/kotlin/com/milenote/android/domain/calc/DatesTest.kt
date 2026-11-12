package com.milenote.android.domain.calc

import com.milenote.android.domain.d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日期边界：闰日、1 月 31 日、跨年、宽松输入。 */
class DatesTest {

    @Test
    fun `month end clamping and leap years`() {
        assertEquals("2026-02-28", Dates.addMonths("2026-01-31", 1))
        assertEquals("2024-02-29", Dates.addMonths("2024-01-31", 1))
        assertEquals("2025-02-28", Dates.addMonths("2024-02-29", 12))
        assertEquals("2027-01-31", Dates.addMonths("2026-12-31", 1))
        assertEquals("2026-04-30", Dates.addMonths("2026-01-31", 3))
        assertEquals("2026-01-31", Dates.addMonths("2026-03-31", -2))
        assertEquals("2025-12-31", Dates.addMonths("2026-01-31", -1))
    }

    @Test
    fun `date value normalizes loose formats`() {
        assertEquals("2026-02-03", Dates.dateValue("2026/2/3 10:00:00"))
        assertEquals("2026-02-03", Dates.dateValue("2026年2月3日"))
        assertEquals("2026-02-03", Dates.dateValue("2026-02-03"))
        assertEquals("", Dates.dateValue("2026-02-30"))
        assertEquals("", Dates.dateValue("not a date"))
        assertEquals("", Dates.dateValue(null))
        assertEquals("2026-02-03", Dates.dateValue(d("2026-02-03")))
    }

    @Test
    fun `excel serial number maps to date`() {
        // 45678 = 2025-01-15（Excel 1900 日期系统）
        assertEquals("2025-01-21", Dates.dateValue(45678))
        assertEquals("2025-01-21", Dates.dateValue(45678.0))
    }

    @Test
    fun `strict validation`() {
        assertTrue(Dates.isValid("2026-11-04"))
        assertTrue(Dates.isValid("2024-02-29"))
        assertFalse(Dates.isValid("2026-02-29"))
        assertFalse(Dates.isValid("2026-2-3"))
        assertFalse(Dates.isValid(""))
        assertNull(Dates.parse("2026-13-01"))
    }

    @Test
    fun `period ranges`() {
        val today = d("2026-11-04")
        assertEquals(DateRange("", "9999-12-31"), Dates.range(Period.ALL, today))
        assertEquals(DateRange("2026-11-01", "2026-11-04"), Dates.range(Period.MONTH, today))
        assertEquals(DateRange("2026-10-01", "2026-10-31"), Dates.range(Period.LAST_MONTH, today))
        assertEquals(DateRange("2026-09-01", "2026-11-04"), Dates.range(Period.LAST_3_MONTHS, today))
        assertEquals(DateRange("2026-01-01", "2026-11-04"), Dates.range(Period.YEAR, today))
    }

    @Test
    fun `last month crosses year boundary`() {
        val today = d("2026-01-15")
        assertEquals(DateRange("2025-12-01", "2025-12-31"), Dates.range(Period.LAST_MONTH, today))
        assertEquals(DateRange("2025-11-01", "2026-01-15"), Dates.range(Period.LAST_3_MONTHS, today))
    }

    @Test
    fun `last month handles leap february`() {
        val today = d("2024-03-31")
        assertEquals(DateRange("2024-02-01", "2024-02-29"), Dates.range(Period.LAST_MONTH, today))
    }

    @Test
    fun `weekday and display`() {
        assertEquals("星期三", Dates.weekday(d("2026-11-04")))
        assertEquals("星期日", Dates.weekday(d("2026-11-01")))
        assertEquals("2026.11.04", Dates.displayDots("2026-11-04"))
        assertEquals(21L, Dates.daysBetween("2026-11-01", "2026-11-22"))
        assertNull(Dates.daysBetween("bad", "2026-11-22"))
    }
}
