package com.milenote.android.ui.screens.maintenance

import com.milenote.android.domain.calc.reminderState
import com.milenote.android.domain.calc.serviceProgress
import com.milenote.android.domain.model.Reminder
import com.milenote.android.i18n.I18n
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 待办卡（方案 A 紧凑清单）文案：
 * - 状态行 = 状态词 + 剩余量（取 `serviceProgress` 的先到维度，缺进度时按状态兜底）；
 * - 绝对值 = 到期日 · 到期里程（只有一项就只显示一项）。
 *
 * 数据取 0.34.7 演示账本：今天 2026-11-09、当前 42,000 公里。
 */
class ReminderStatusTextTest {

    private val today = LocalDate.of(2026, 11, 9)
    private val km = 42_000.0

    private fun reminder(
        title: String = "空气滤芯",
        dueDate: String? = "2026-12-09",
        dueKm: Double? = null,
        intervalKm: Double? = null,
        intervalMonths: Int? = null,
        note: String? = null,
    ): Reminder = Reminder(
        id = "r-test",
        title = title,
        dueDate = dueDate,
        dueKm = dueKm,
        intervalKm = intervalKm,
        intervalMonths = intervalMonths,
        note = note,
    )

    private fun statusLine(reminder: Reminder): String = reminderStatusLine(
        reminder = reminder,
        state = reminderState(reminder, km, today),
        progress = serviceProgress(reminder, km, today),
    )

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `overdue by mileage prefixes status and shows overrun`() {
        val r = reminder(dueKm = 40_000.0, intervalKm = 10_000.0, intervalMonths = 12)
        assertEquals("已到期 · 超出 2,000 公里", statusLine(r))
    }

    @Test
    fun `mileage used up is worded instead of zero overrun`() {
        val r = reminder(dueDate = "2027-02-09", dueKm = 42_000.0, intervalKm = 10_000.0)
        assertEquals("已到期 · 里程已用完", statusLine(r))
    }

    @Test
    fun `due soon by days shows days left`() {
        val r = reminder(title = "洗车", dueDate = "2026-12-01", intervalMonths = 12)
        assertEquals("即将到期 · 还有 22 天", statusLine(r))
    }

    @Test
    fun `planned row shows the nearer mileage`() {
        val r = reminder(
            title = "机油机滤",
            dueDate = "2027-03-09",
            dueKm = 43_000.0,
            intervalKm = 10_000.0,
            intervalMonths = 12,
        )
        assertEquals("计划中 · 还剩 1,000 公里", statusLine(r))
    }

    @Test
    fun `no interval data falls back to days`() {
        val r = reminder(dueDate = "2026-12-01")
        assertEquals("即将到期 · 还有 22 天", statusLine(r))
    }

    @Test
    fun `no due date falls back to mileage`() {
        val r = reminder(title = "轮胎", dueDate = null, dueKm = 45_000.0)
        assertEquals("计划中 · 还剩 3,000 公里", statusLine(r))
    }

    @Test
    fun `absolute deadline joins date and mileage`() {
        val r = reminder(dueDate = "2026-12-09", dueKm = 40_000.0)
        assertEquals("2026-12-09 到期 · 40,000 公里", reminderAbsoluteDeadline(r))
    }

    @Test
    fun `absolute deadline handles single field`() {
        assertEquals("2026-12-01 到期", reminderAbsoluteDeadline(reminder(dueDate = "2026-12-01")))
        assertEquals("40,000 公里", reminderAbsoluteDeadline(reminder(dueDate = null, dueKm = 40_000.0)))
    }

    @Test
    fun `english wording`() {
        I18n.applyEnglish(true)
        val r = reminder(dueKm = 40_000.0, intervalKm = 10_000.0, intervalMonths = 12)
        assertEquals("Overdue · over by 2,000 km", statusLine(r))
        assertEquals("Due 2026-12-09 · 40,000 km", reminderAbsoluteDeadline(r))
    }
}
