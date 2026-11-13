package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 费用明细批量操作：批量删除 / 删除撤销 / 批量改分类。 */
class RecordBatchTest {

    private fun record(
        id: String,
        date: String = "2026-10-01",
        category: String = "停车费",
        amount: Double = 10.0,
    ) = Record(id = id, date = date, category = category, amount = amount)

    private fun fourRecords() = testLedger(
        records = listOf(
            record("a", date = "2026-10-01"),
            record("b", date = "2026-10-02"),
            record("c", date = "2026-10-03"),
            record("d", date = "2026-10-04"),
        ),
    )

    @Test
    fun `batch delete removes only selected records and keeps ledger order`() {
        val ledger = fourRecords()

        val result = removeRecords(ledger, setOf("b", "d"))

        assertEquals(listOf("a", "c"), result.ledger.records.map { it.id })
        assertEquals(listOf("b", "d"), result.undo.records.map { it.id })
        assertEquals(listOf(1, 3), result.undo.indices)
        assertEquals(setOf("b", "d"), result.undo.ids)
    }

    @Test
    fun `undo inserts deleted records back at their original positions`() {
        val ledger = fourRecords()

        val result = removeRecords(ledger, setOf("b", "d"))
        val restored = restoreRemovedRecords(result.ledger, result.undo)

        assertEquals(ledger, restored)
    }

    @Test
    fun `undo is idempotent`() {
        val result = removeRecords(fourRecords(), setOf("a", "c"))
        val once = restoreRemovedRecords(result.ledger, result.undo)

        assertEquals(once, restoreRemovedRecords(once, result.undo))
    }

    @Test
    fun `unknown ids are a no-op`() {
        val ledger = fourRecords()

        val result = removeRecords(ledger, setOf("missing"))

        assertEquals(ledger, result.ledger)
        assertTrue(result.undo.isEmpty)
    }

    @Test
    fun `delete restores completed reminder and undo brings back the generated next cycle`() {
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
        val air = Reminder(id = "air", title = "空气滤芯", done = false)
        val ledger = testLedger(
            records = listOf(record("r1", category = "保养")),
            reminders = listOf(completed, generatedNext, air),
        )

        val result = removeRecords(ledger, setOf("r1"))

        assertEquals(listOf("oil", "air"), result.ledger.reminders.map { it.id })
        val oil = result.ledger.reminders.first()
        assertFalse(oil.done)
        assertNull(oil.completedDate)
        assertNull(oil.recordId)
        // 撤销要连「下一轮提醒」一起回来，且顺序与删除前一致。
        assertEquals(ledger, restoreRemovedRecords(result.ledger, result.undo))
    }

    @Test
    fun `delete without restoring reminders keeps them done and undo restores the link`() {
        val completed = Reminder(id = "oil", title = "机油机滤", done = true, recordId = "r1")
        val ledger = testLedger(
            records = listOf(record("r1", category = "保养")),
            reminders = listOf(completed),
        )

        val result = removeRecords(ledger, setOf("r1"), restoreReminders = false)

        val kept = result.ledger.reminders.single()
        assertTrue(kept.done)
        assertNull(kept.recordId)
        assertEquals(ledger, restoreRemovedRecords(result.ledger, result.undo))
    }

    @Test
    fun `batch category change touches only selected records`() {
        val ledger = fourRecords()

        val next = applyCategoryToRecords(ledger, setOf("a", "c"), "保养")

        assertEquals(
            listOf("保养", "停车费", "保养", "停车费"),
            next.records.map { it.category },
        )
        // 金额、日期等字段不动。
        assertEquals(ledger.records[0].amount, next.records[0].amount, 1e-9)
        assertEquals(ledger.records[2].date, next.records[2].date)
    }

    @Test
    fun `batch category change normalizes legacy category names`() {
        val ledger = fourRecords()

        val next = applyCategoryToRecords(ledger, setOf("a"), "维修保养")

        assertEquals("保养", next.records.first().category)
    }

    @Test
    fun `batch category change ignores empty ids or blank category`() {
        val ledger = fourRecords()

        assertEquals(ledger, applyCategoryToRecords(ledger, emptySet(), "保养"))
        assertEquals(ledger, applyCategoryToRecords(ledger, setOf("a"), ""))
    }
}
