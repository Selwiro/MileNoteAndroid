package com.milenote.android.domain.calc

import com.milenote.android.domain.d

import com.milenote.android.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：「提醒以日期或里程任一到期为准」。 */
class ReminderStateTest {

    @Test
    fun `date or mileage overdue`() {
        val date = d("2026-10-29")
        assertEquals(
            ReminderLevel.OVERDUE,
            reminderState(Reminder(dueDate = "2026-11-01", dueKm = 5000.0), 5100.0, date).level,
        )
        assertEquals(
            ReminderLevel.SOON,
            reminderState(Reminder(dueDate = "2026-11-01"), 100.0, date).level,
        )
        assertEquals(
            ReminderLevel.DONE,
            reminderState(Reminder(dueKm = 1000.0, done = true), 2000.0, date).level,
        )
    }

    @Test
    fun `days and left are computed`() {
        val state = reminderState(
            Reminder(dueDate = "2026-11-22", dueKm = 6000.0),
            5500.0,
            d("2026-11-01"),
        )
        assertEquals(21L, state.days)
        assertEquals(500.0, state.left!!, 1e-9)
        assertEquals(ReminderLevel.SOON, state.level)
    }

    @Test
    fun `today due date is overdue`() {
        val state = reminderState(Reminder(dueDate = "2026-11-01"), 0.0, d("2026-11-01"))
        assertEquals(ReminderLevel.OVERDUE, state.level)
        assertEquals(0L, state.days)
    }

    @Test
    fun `no due date and no due km is normal`() {
        val state = reminderState(Reminder(title = "洗车"), 0.0, d("2026-11-01"))
        assertNull(state.days)
        assertNull(state.left)
        assertEquals(ReminderLevel.NORMAL, state.level)
    }
}
