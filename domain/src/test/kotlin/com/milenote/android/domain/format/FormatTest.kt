package com.milenote.android.domain.format

import com.milenote.android.domain.calc.reminderState
import com.milenote.android.domain.d
import com.milenote.android.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Test

/** 金额 / 数字 / 日期 / 周期文案，输出与 Web `toLocaleString('zh-CN')` 一致。 */
class FormatTest {

    @Test
    fun `money uses two decimals and grouping`() {
        assertEquals("0.00", Format.money(null))
        assertEquals("0.00", Format.money(0.0))
        assertEquals("1,234.50", Format.money(1234.5))
        assertEquals("10,511.00", Format.money(10511.0))
        assertEquals("1,234,567.89", Format.money(1234567.891))
        assertEquals("-1,234.50", Format.money(-1234.5))
    }

    @Test
    fun `fmt keeps at most one decimal`() {
        assertEquals("0", Format.fmt(null))
        assertEquals("42,000", Format.fmt(42000.0))
        assertEquals("6.3", Format.fmt(6.25))
        assertEquals("6.3", Format.fmt(6.3))
        assertEquals("100", Format.fmt(99.96))
        assertEquals("999,999.6", Format.fmt(999999.6))
    }

    @Test
    fun `dates and weekday`() {
        assertEquals("2026.11.04", Format.dateDots("2026-11-04"))
        assertEquals("星期三", Format.weekday(d("2026-11-04")))
        assertEquals("2026.11.04 星期三", Format.todayLabel(d("2026-11-04")))
        assertEquals("42,000 公里", Format.km(42000.0))
    }

    @Test
    fun `cycle text matches web`() {
        assertEquals("10,000 公里 或 1 年", Format.cycleText(10000.0, 12))
        assertEquals("10,000 公里", Format.cycleText(10000.0, null))
        assertEquals("5,000 公里 或 3 个月", Format.cycleText(5000.0, 3))
        assertEquals("50,000 公里", Format.cycleText(50000.0, null))
        assertEquals("4 年", Format.cycleText(null, 48))
        assertEquals("11 个月", Format.cycleText(null, 11))
        assertEquals("单次事项", Format.cycleText(null, null))
        assertEquals("单次事项", Format.cycleText(0.0, 0))
    }

    @Test
    fun `reminder deadline text`() {
        val reminder = Reminder(dueDate = "2026-11-22", dueKm = 6000.0)
        val state = reminderState(reminder, 5500.0, d("2026-11-01"))
        val text = Format.reminderDeadline(reminder, state)
        assertEquals("2026-11-22 · 还有 21 天\n6,000 公里 · 还可行驶 500 公里", text)

        val overdue = reminderState(Reminder(dueDate = "2026-10-31"), 0.0, d("2026-11-01"))
        assertEquals("2026-10-31 · 已过 1 天", Format.reminderDeadline(Reminder(dueDate = "2026-10-31"), overdue))
    }
}
