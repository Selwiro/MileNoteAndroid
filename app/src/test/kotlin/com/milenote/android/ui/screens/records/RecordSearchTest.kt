package com.milenote.android.ui.screens.records

import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明细搜索（P2）：除备注/日期/来源外，支持按**金额**与**里程**搜索。
 */
class RecordSearchTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val ledger = blankLedger().copy(
        records = listOf(
            Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 680.0, note = "商场"),
            Record(id = "r2", date = "2026-11-02", category = "油费", amount = 300.0, odometer = 42000.0, liters = 40.0),
            Record(id = "r3", date = "2026-11-03", category = "罚单", amount = 200.0, note = "高速超速"),
        ),
    )

    private fun search(query: String): List<String> =
        filterRecords(ledger, query, category = null, period = Period.ALL, today = today).map { it.id }

    @Test
    fun `searches by amount`() {
        assertEquals(listOf("r1"), search("680"))
        assertEquals(listOf("r1"), search("680.00"))
    }

    @Test
    fun `searches by odometer`() {
        assertEquals(listOf("r2"), search("42000"))
        assertEquals(listOf("r2"), search("42,000"))
    }

    @Test
    fun `keeps searching notes dates and sources`() {
        assertEquals(listOf("r3"), search("超速"))
        assertEquals(listOf("r1"), search("2026-11-01"))
        assertTrue(search("").size == 3)
    }

    @Test
    fun `no hit returns empty`() {
        assertTrue(search("999999").isEmpty())
    }
}
