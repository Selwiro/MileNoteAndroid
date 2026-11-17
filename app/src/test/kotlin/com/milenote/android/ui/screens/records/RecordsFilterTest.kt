package com.milenote.android.ui.screens.records

import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 费用明细筛选的两条补充规则：
 * - 空日期端点 = 不过滤（累计报告下钻，#20）；
 * - onlyIds = 只看「本次导入」的记录（导入结果页入口）。
 */
class RecordsFilterTest {

    private val today = LocalDate.of(2026, 11, 17)

    private fun record(id: String, date: String, category: String = "停车费") = Record(
        id = id,
        date = date,
        category = category,
        amount = 20.0,
        note = "合成测试记录",
    )

    private val ledger = Ledger(
        id = "car-1",
        records = listOf(
            record("a", "2026-11-01"),
            record("b", "2026-12-20"),
            record("c", "2026-10-05", category = "油费"),
        ),
    )

    @Test
    fun `empty range endpoints mean no time filter`() {
        val result = filterRecords(ledger, query = "", period = Period.ALL, today = today)

        assertEquals(3, result.size)
    }

    @Test
    fun `only ids limits the list to the imported records`() {
        val result = filterRecords(
            ledger = ledger,
            query = "",
            period = Period.ALL,
            today = today,
            onlyIds = setOf("a", "c"),
        )

        assertEquals(listOf("a", "c"), result.map { it.id }.sorted())
    }

    @Test
    fun `a future record is reachable when the range is unbounded`() {
        val result = filterRecords(
            ledger = ledger,
            query = "",
            categories = setOf("停车费"),
            period = Period.ALL,
            customRange = com.milenote.android.domain.calc.DateRange("", ""),
            today = today,
        )

        assertEquals(setOf("a", "b"), result.map { it.id }.toSet())
    }
}
