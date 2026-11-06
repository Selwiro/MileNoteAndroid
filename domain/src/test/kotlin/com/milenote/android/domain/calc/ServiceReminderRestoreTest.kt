package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceReminderRestoreTest {

    private val record = Record(
        id = "r1",
        date = "2026-10-01",
        category = "保养",
        odometer = 20_000.0,
    )

    @Test
    fun `restores completed reminder and removes generated next cycle`() {
        val completed = Reminder(
            id = "oil",
            title = "机油机滤",
            done = true,
            completedDate = "2026-10-01",
            completedKm = 20_000.0,
            recordId = "r1",
            baseDate = "2026-04-01",
            baseKm = 15_000.0,
            dueDate = "2026-10-01",
            dueKm = 20_000.0,
        )
        val generatedNext = Reminder(
            id = "oil-next",
            title = "机油机滤",
            done = false,
            baseDate = "2026-10-01",
            baseKm = 20_000.0,
            dueDate = "2027-04-01",
            dueKm = 25_000.0,
            historyRecordId = "r1",
        )
        val other = Reminder(id = "air", title = "空气滤芯", done = false)

        val restored = restoreServiceRemindersAfterRecordDelete(
            listOf(completed, generatedNext, other),
            record,
        )

        assertEquals(listOf("oil", "air"), restored.map { it.id })
        val oil = restored.first { it.id == "oil" }
        assertFalse(oil.done)
        assertNull(oil.completedDate)
        assertNull(oil.completedKm)
        assertNull(oil.recordId)
        // 原有到期基准保留，供 syncServiceHistory 重新计算。
        assertEquals("2026-10-01", oil.dueDate)
        assertEquals(20_000.0, oil.dueKm!!, 1e-9)
    }

    @Test
    fun `does nothing when record is not linked to a reminder`() {
        val reminders = listOf(Reminder(id = "air", title = "空气滤芯", done = false))
        assertEquals(reminders, restoreServiceRemindersAfterRecordDelete(reminders, record))
    }

    @Test
    fun `clears record link only when choosing not to restore`() {
        val completed = Reminder(id = "oil", title = "机油机滤", done = true, recordId = "r1")
        val kept = listOf(completed).map {
            if (it.recordId == "r1") it.copy(recordId = null) else it
        }
        assertTrue(kept.single().done)
        assertNull(kept.single().recordId)
    }
}
