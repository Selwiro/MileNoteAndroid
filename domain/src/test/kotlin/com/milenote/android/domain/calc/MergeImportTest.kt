package com.milenote.android.domain.calc

import com.milenote.android.domain.fuelRecord
import com.milenote.android.domain.importedItem
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web core.test：判重、导入合并、跨源油费冲突、整批口径。 */
class MergeImportTest {

    @Test
    fun `cross source merge keeps liters and is idempotent`() {
        val qianji = Record(
            id = "q",
            date = "2026-01-01",
            category = "油费",
            amount = 300.0,
            source = "钱迹",
            sourceId = "k1",
        )
        val bear = fuelRecord("2026-01-01", 1000.0, 40.0, id = "b", source = "小熊油耗")

        var result = mergeImported(listOf(qianji), listOf(importedItem(bear)))
        assertEquals(1, result.records.size)
        assertEquals(40.0, result.records[0].liters!!, 1e-9)
        assertEquals(1, result.merged)

        result = mergeImported(result.records, listOf(importedItem(qianji), importedItem(bear)))
        assertEquals(1, result.records.size)
        assertEquals(0, result.added)
    }

    @Test
    fun `same day same amount with different qianji ids stays two records`() {
        val a = Record(
            id = "1", date = "2026-01-01", category = "过路费", amount = 25.0,
            source = "钱迹", sourceId = "a",
        )
        val b = a.copy(id = "2", sourceId = "b")
        assertNull(duplicateOf(listOf(a), b))
        assertEquals(2, mergeImported(listOf(a), listOf(importedItem(b))).records.size)
        assertEquals(2, mergeImported(listOf(a), listOf(importedItem(a, force = true))).records.size)
    }

    @Test
    fun `duplicate detection by imported keys source id and signature`() {
        val existing = Record(
            id = "b", date = "2026-01-01", category = "油费", amount = 255.0,
            source = "小熊油耗", importedKeys = listOf("钱迹:k1"),
        )
        val incoming = Record(
            id = "q", date = "2026-01-01", category = "油费", amount = 255.0,
            source = "钱迹", sourceId = "k1",
        )
        assertNotNull(duplicateOf(listOf(existing), incoming))

        val signatureA = Record(id = "x", date = "2026-02-01", category = "停车费", amount = 10.0, projects = "A")
        val signatureB = Record(id = "y", date = "2026-02-01", category = "停车费", amount = 10.0, projects = "A")
        assertNotNull(duplicateOf(listOf(signatureA), signatureB))
        assertNull(duplicateOf(listOf(signatureA), signatureB.copy(projects = "B")))
    }

    @Test
    fun `fuel conflicts require choice per date and support split payments`() {
        val ledger = testLedger()
        val bear = Record(
            id = "b", source = "小熊油耗", sourceId = "1", category = "油费",
            date = "2026-05-01", amount = 255.0, liters = 40.0,
        )
        fun qianji(id: String, amount: Double, date: String = "2026-05-01") = importedItem(
            Record(id = id, source = "钱迹", sourceId = id, category = "油费", date = date, amount = amount),
        )
        val items = listOf(qianji("q1", 200.0), qianji("q2", 100.0))

        val conflicts = fuelImportConflicts(listOf(bear), items)
        assertEquals(1, conflicts.size)
        assertEquals("2026-05-01", conflicts[0].date)
        assertEquals(255.0, conflicts[0].bear, 1e-9)
        assertEquals(300.0, conflicts[0].qianji, 1e-9)
        assertThrows { mergeWithFuelChoices(listOf(bear), items) }

        for ((choice, expected) in listOf("bear" to 255.0, "qianji" to 300.0, "both" to 555.0)) {
            val result = mergeWithFuelChoices(listOf(bear), items, mapOf("2026-05-01" to choice))
            assertEquals(expected, result.records.sumOf { costOf(it, ledger) }, 1e-9)
            assertEquals(3, result.records.size)
            assertEquals(40.0, result.records[0].liters!!, 1e-9)
            assertEquals(0, fuelImportConflicts(result.records, items).size)
            assertEquals(0, mergeWithFuelChoices(result.records, items).added)
        }

        val noConflict = mergeWithFuelChoices(listOf(bear), listOf(qianji("q3", 300.0, "2026-05-02")))
        assertEquals(555.0, noConflict.records.sumOf { costOf(it, ledger) }, 1e-9)
        assertNull(bear.fuelCounted)

        val reverse = mergeWithFuelChoices(
            items.map { it.record },
            listOf(importedItem(bear)),
            mapOf("2026-05-01" to "bear"),
        )
        assertEquals(255.0, reverse.records.sumOf { costOf(it, ledger) }, 1e-9)
        assertEquals(0, fuelImportConflicts(listOf(bear), listOf(qianji("skip", 500.0).copy(exclude = true))).size)
    }

    @Test
    fun `fuel batch choice does not require same date and defaults to bear`() {
        val ledger = testLedger()
        val bear = Record(
            id = "b", category = "油费", source = "小熊油耗", sourceId = "b",
            date = "2026-10-01", amount = 255.0,
        )
        val qianji = importedItem(
            Record(
                id = "q", category = "油费", source = "钱迹", sourceId = "q",
                date = "2026-11-01", amount = 300.0,
            ),
        )
        assertNotNull(fuelBatchConflict(listOf(bear), listOf(qianji)))
        for ((choice, total) in listOf("bear" to 255.0, "qianji" to 300.0, "both" to 555.0)) {
            val merged = mergeFuelBatch(listOf(bear), listOf(qianji), choice)
            assertEquals(2, merged.records.size)
            assertEquals(total, merged.records.sumOf { costOf(it, ledger) }, 1e-9)
        }
        assertEquals(false, mergeFuelBatch(listOf(bear), listOf(qianji)).records.first { it.id == "q" }.fuelCounted)
        assertNull(fuelBatchConflict(listOf(bear), listOf(qianji.copy(exclude = true))))
        assertThrows { mergeFuelBatch(listOf(bear), listOf(qianji), "unknown") }
    }

    @Test
    fun `merge imported counts errors and skips`() {
        val result = mergeImported(
            emptyList(),
            listOf(
                importedItem(Record(id = "a", date = "2026-01-01", category = "停车费", amount = 1.0), error = "bad"),
                importedItem(Record(id = "b", date = "2026-01-01", category = "停车费", amount = 1.0), exclude = true),
                importedItem(Record(id = "c", date = "2026-01-01", category = "停车费", amount = 1.0)),
            ),
        )
        assertEquals(1, result.records.size)
        assertEquals(1, result.added)
        assertEquals(2, result.skipped)
        assertEquals(0, result.merged)
        assertTrue(result.records[0].id == "c")
    }

    @Test
    fun `apply import policy skips qianji fuel under bear basis`() {
        val items = listOf(
            importedItem(Record(id = "1", date = "2026-01-01", category = "油费", amount = 1.0, source = "钱迹")),
            importedItem(Record(id = "2", date = "2026-01-01", category = "停车费", amount = 1.0, source = "钱迹")),
            importedItem(Record(id = "3", date = "2026-01-01", category = "油费", amount = 1.0, source = "小熊油耗")),
        )
        val policy = applyImportPolicy(items, "bear")
        assertTrue(policy[0].error.isNotEmpty())
        assertEquals("", policy[1].error)
        assertEquals("", policy[2].error)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
