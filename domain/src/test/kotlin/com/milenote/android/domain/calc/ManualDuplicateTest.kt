package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手动记账的「疑似重复」提醒规则：同日 + 同归一分类 + 同金额（±0.005）才提醒，
 * 且不参与导入判重 / 合并；同一 id（编辑既有记录）不算重复。
 */
class ManualDuplicateTest {

    private fun record(
        id: String = "r1",
        date: String = "2026-11-01",
        category: String = "停车费",
        amount: Double = 25.0,
    ) = Record(id = id, date = date, category = category, amount = amount)

    @Test
    fun `same date category and amount is reported`() {
        val existing = listOf(record(id = "old"))
        val candidate = record(id = "new")
        assertEquals("old", manualDuplicateOf(existing, candidate)?.id)
    }

    @Test
    fun `legacy maintenance category normalizes before comparing`() {
        val existing = listOf(record(id = "old", category = "维修保养"))
        val candidate = record(id = "new", category = "保养")
        assertEquals("old", manualDuplicateOf(existing, candidate)?.id)
    }

    @Test
    fun `different date or amount or category is not a duplicate`() {
        val existing = listOf(record(id = "old"))
        assertNull(manualDuplicateOf(existing, record(id = "new", date = "2026-11-02")))
        assertNull(manualDuplicateOf(existing, record(id = "new", amount = 26.0)))
        assertNull(manualDuplicateOf(existing, record(id = "new", category = "油费")))
    }

    @Test
    fun `editing the same record is never a duplicate of itself`() {
        val existing = listOf(record(id = "same"))
        assertNull(manualDuplicateOf(existing, record(id = "same", amount = 25.0)))
    }

    @Test
    fun `amount tolerance is half a cent`() {
        val existing = listOf(record(id = "old", amount = 25.0))
        assertEquals("old", manualDuplicateOf(existing, record(id = "new", amount = 25.004))?.id)
        assertNull(manualDuplicateOf(existing, record(id = "new", amount = 25.01)))
    }
}
