package com.milenote.android.domain.calc

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全局「今天」定时刷新用的零点间隔计算。 */
class DatesMidnightTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun millisAt(text: String, zone: ZoneId = shanghai): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `returns the remaining time until local midnight`() {
        val now = millisAt("2026-11-15T23:59:30")
        assertEquals(30_000L, Dates.millisUntilNextMidnight(now, shanghai))
    }

    @Test
    fun `at midnight returns almost a full day`() {
        val now = millisAt("2026-11-15T00:00:00")
        assertEquals(24 * 60 * 60 * 1000L, Dates.millisUntilNextMidnight(now, shanghai))
    }

    @Test
    fun `never returns a non-positive delay`() {
        val now = millisAt("2026-11-15T23:59:59.999")
        assertTrue(Dates.millisUntilNextMidnight(now, shanghai) >= 1_000L)
    }

    @Test
    fun `handles daylight saving start`() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-03-08 凌晨进入夏令时：从 00:00 到次日 00:00 只有 23 小时。
        val now = millisAt("2026-03-08T00:00:00", newYork)
        assertEquals(23 * 60 * 60 * 1000L, Dates.millisUntilNextMidnight(now, newYork))
    }
}
