package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销本次导入（1.0 审查 #5）。
 *
 * 以前是整库回滚到导入前快照，导入后被同步拉下来的远端记录会被一起抹掉；现在只反做
 * 本次导入真正改过的记录 / 提醒，导入后又被改过的条目跳过。
 */
class ImportUndoTest {

    private fun record(id: String, amount: Double = 100.0, note: String = "") = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        note = note,
    )

    private fun reminder(id: String, done: Boolean = false) = Reminder(
        id = id,
        title = "换机油",
        intervalKm = 10_000.0,
        done = done,
        completedDate = if (done) "2026-11-01" else null,
        recordId = if (done) "imp-1" else null,
    )

    private fun garage(records: List<Record>, reminders: List<Reminder> = emptyList()) = Garage(
        version = 2,
        activeId = "car-1",
        theme = "blue",
        cars = listOf(Ledger(id = "car-1", records = records, reminders = reminders)),
    )

    @Test
    fun `undo removes imported records but keeps records synced in afterwards`() {
        val afterImport = garage(listOf(record("old-1"), record("imp-1"), record("imp-2")))
        val undo = ImportUndo(
            carId = "car-1",
            touched = mapOf("imp-1" to record("imp-1"), "imp-2" to record("imp-2")),
        )
        // 导入后另一台设备同步下来一笔。
        val current = afterImport.copy(
            cars = listOf(afterImport.cars.first().copy(records = afterImport.cars.first().records + record("remote-1"))),
        )

        val result = undoImport(current, undo)

        val ids = result.garage.cars.first().records.map { it.id }
        assertEquals(listOf("old-1", "remote-1"), ids)
        assertEquals(2, result.undone)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `undo restores a record that the import overwrote in place`() {
        val before = record("imp-1", amount = 200.0, note = "原备注")
        val after = record("imp-1", amount = 120.0, note = "导入覆盖")
        val current = garage(listOf(after))

        val result = undoImport(current, ImportUndo(carId = "car-1", touched = mapOf("imp-1" to after), previous = mapOf("imp-1" to before)))

        assertEquals(before, result.garage.cars.first().records.single())
        assertEquals(1, result.undone)
    }

    @Test
    fun `undo keeps a record the user edited after the import`() {
        val imported = record("imp-1", amount = 120.0)
        val edited = imported.copy(amount = 99.0)
        val current = garage(listOf(edited))

        val result = undoImport(current, ImportUndo(carId = "car-1", touched = mapOf("imp-1" to imported)))

        assertEquals(edited, result.garage.cars.first().records.single())
        assertEquals(0, result.undone)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `undo puts completed reminders back to pending`() {
        val current = garage(records = emptyList(), reminders = listOf(reminder("r-1", done = true)))

        val result = undoImport(
            current,
            ImportUndo(
                carId = "car-1",
                remindersAfter = mapOf("r-1" to reminder("r-1", done = true)),
                remindersBefore = mapOf("r-1" to reminder("r-1", done = false)),
            ),
        )

        val restored = result.garage.cars.first().reminders.single()
        assertFalse(restored.done == true)
        assertNull(restored.recordId)
        assertEquals(1, result.undone)
    }

    @Test
    fun `undo on a missing vehicle changes nothing and counts the skips`() {
        val current = garage(listOf(record("keep")))
        val undo = ImportUndo(carId = "gone", touched = mapOf("imp-1" to record("imp-1")))

        val result = undoImport(current, undo)

        assertEquals(current, result.garage)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `buildImportUndo tracks added overwritten and completed items only`() {
        val before = Ledger(
            id = "car-1",
            records = listOf(record("old-1"), record("imp-1", amount = 200.0)),
            reminders = listOf(reminder("r-1", done = false), reminder("r-2", done = true)),
        )
        val after = Ledger(
            id = "car-1",
            records = listOf(record("old-1"), record("imp-1", amount = 120.0), record("imp-2")),
            reminders = listOf(reminder("r-1", done = true), reminder("r-2", done = true)),
        )

        val undo = buildImportUndo("car-1", before, after)

        assertFalse(undo.isEmpty)
        assertEquals(setOf("imp-1", "imp-2"), undo.touched.keys)
        assertEquals(setOf("imp-1"), undo.previous.keys)
        assertEquals(setOf("r-1"), undo.remindersAfter.keys)
        assertTrue(undo.remindersBefore["r-1"]?.done == false)
    }
}
