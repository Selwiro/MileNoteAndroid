package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 默认保养提醒生成：燃油 12 项 / 纯电 6 项，首保完成后重设对应循环项。 */
class ServiceRemindersTest {

    @Test
    fun `ev default reminders are six and first targets ev diagnostics`() {
        val reminders = defaultServiceReminders("2026-01-31", 0.0, ev = true)
        assertEquals(6, reminders.size)
        assertEquals(
            listOf("首保", "三电诊断", "空调滤芯", "刹车油", "冷却液", "轮胎"),
            reminders.map { it.title },
        )
        val first = reminders[0]
        assertEquals(true, first.firstOnly)
        assertEquals(5000.0, first.dueKm!!, 1e-9)
        assertEquals("2026-04-30", first.dueDate)
        assertEquals(listOf("三电诊断"), first.projects)
        assertTrue(first.note!!.contains("三电诊断"))
        // 一次性首保没有下一轮
        assertNull(nextService(first, "2026-04-30", 5000.0))
        // 不含首保时 5 项
        assertEquals(5, defaultServiceReminders("2026-01-01", 0.0, false, ev = true).size)
        // 燃油路径不变
        assertEquals(12, defaultServiceReminders("2026-01-01", 0.0).size)
        assertEquals(11, defaultServiceReminders("2026-01-01", 0.0, false).size)
    }

    @Test
    fun `ev first service reschedules ev diagnostics cycle`() {
        val reminders = defaultServiceReminders("2026-01-01", 0.0, ev = true)
        val first = reminders.first { it.firstOnly == true }
        val result = completeService(
            reminders,
            first,
            Record(
                id = "first",
                date = "2026-04-01",
                category = "保养",
                amount = 0.0,
                odometer = 5000.0,
                projects = "三电诊断",
            ),
        )
        assertEquals(6, result.size)
        assertTrue(result.first { it.id == first.id }.done)
        val diag = result.first { it.presetKey == "ev-diag" && !it.done }
        assertEquals(15000.0, diag.dueKm!!, 1e-9)
        assertEquals("2027-04-01", diag.dueDate)
        // 纯电方案里没有机油提醒，不会被凭空造出来
        assertTrue(result.none { it.title == "机油机滤" })
    }

    @Test
    fun `fuel first service keeps rescheduling oil cycle`() {
        val reminders = defaultServiceReminders("2026-01-01", 0.0)
        val first = reminders.first { it.firstOnly == true }
        val result = completeService(
            reminders,
            first,
            Record(
                id = "first",
                date = "2026-04-01",
                category = "保养",
                amount = 0.0,
                odometer = 5000.0,
                projects = "机油机滤",
            ),
        )
        assertEquals(12, result.size)
        assertEquals(15000.0, result.first { it.presetKey == "oil" && !it.done }.dueKm!!, 1e-9)
    }
}
