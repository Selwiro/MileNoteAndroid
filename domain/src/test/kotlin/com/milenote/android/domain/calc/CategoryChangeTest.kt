package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批量改分类的预检（1.0 审查 #7）。
 *
 * 真实事故：把 0 元保养记录改成「停车费」，换分类后金额规则不满足，写盘前的账本校验
 * 抛 BackupException，界面提交协程没接住 → 闪退。预检必须在提交前给出条数。
 */
class CategoryChangeTest {

    private fun record(id: String, category: String, amount: Double) = Record(
        id = id,
        date = "2026-11-01",
        category = category,
        amount = amount,
        note = "合成测试记录",
    )

    @Test
    fun `zero amount service record rejects a paid category`() {
        val records = listOf(record("a", "维修保养", 0.0), record("b", "维修保养", 0.0))

        assertEquals(2, categoryChangeRejects(records, "停车费"))
    }

    @Test
    fun `zero amount stays valid for the service categories`() {
        val records = listOf(record("a", "维修保养", 0.0))

        assertEquals(0, categoryChangeRejects(records, "维修保养"))
    }

    @Test
    fun `paid records can move to any category`() {
        val records = listOf(record("a", "维修保养", 120.0), record("b", "油费", 200.0))

        assertEquals(0, categoryChangeRejects(records, "停车费"))
    }

    @Test
    fun `only the offending records are counted`() {
        val records = listOf(record("a", "维修保养", 0.0), record("b", "油费", 200.0))

        assertEquals(1, categoryChangeRejects(records, "停车费"))
    }

    @Test
    fun `blank target category rejects the whole batch`() {
        val records = listOf(record("a", "油费", 200.0))

        assertEquals(1, categoryChangeRejects(records, "  "))
    }
}
