package com.milenote.android.ui.screens.report

import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.domain.calc.ReportPeriod
import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.calc.report
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.ui.screens.records.filterRecords
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告构成与下钻明细同口径（1.0 审查 #20）。
 *
 * 真实事故：累计报告把预录（未来日期）也算进总额，但点进分类明细时日期被截到今天，
 * 报告显示 100 元、明细只剩 20 元。
 */
class ReportDrillDownTest {

    private val today = LocalDate.of(2026, 11, 17)

    private fun record(id: String, date: String, amount: Double, category: String = "停车费") = Record(
        id = id,
        date = date,
        category = category,
        amount = amount,
        note = "合成测试记录",
    )

    private fun ledger(): Ledger = Ledger(
        id = "car-1",
        records = listOf(
            record("r-1", "2026-11-01", 20.0),
            // 预录：日期在未来。
            record("r-2", "2026-12-20", 80.0),
        ),
    )

    @Test
    fun `all-time report counts future records and carries an unfiltered window`() {
        val data = report(ledger(), ReportPeriod(ReportKind.ALL), today)

        assertEquals(100.0, data.total, 1e-9)
        assertTrue(data.includesFuture)
        // 空窗口 = 下钻不过滤，看的还是全部记录。
        assertEquals("", data.statsRange.from)
        assertEquals("", data.statsRange.to)
    }

    @Test
    fun `all-time drill down shows the same total as the breakdown`() {
        val ledger = ledger()
        val data = report(ledger, ReportPeriod(ReportKind.ALL), today)

        val records = filterRecords(
            ledger = ledger,
            query = "",
            categories = setOf("停车费"),
            period = Period.ALL,
            customRange = DateRange(data.statsRange.from, data.statsRange.to),
            today = today,
        )

        assertEquals(data.total, records.sumOf { costOf(it, ledger) }, 1e-9)
    }

    @Test
    fun `monthly report still ends its drill down at the period end`() {
        val ledger = ledger()
        val data = report(ledger, ReportPeriod(ReportKind.MONTH, 2026, 11), today)

        // 月报本来就排除未来预录，窗口与报告期一致。
        assertEquals(20.0, data.total, 1e-9)
        assertEquals("2026-11-01", data.statsRange.from)
        assertEquals("2026-11-17", data.statsRange.to)
    }
}
