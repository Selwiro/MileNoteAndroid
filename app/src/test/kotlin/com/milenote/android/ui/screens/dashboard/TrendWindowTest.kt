package com.milenote.android.ui.screens.dashboard

import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 趋势图窗口：全部 = 最早记录起算，超过 24 个月取最近 24 个月（不是最早 24 个月）；单月补上下文。 */
class TrendWindowTest {

    private val today = LocalDate.of(2026, 11, 12)

    private fun ledgerWith(vararg dates: String) = blankLedger().copy(
        records = dates.mapIndexed { index, date ->
            Record(id = "r$index", date = date, category = "油费", amount = 100.0)
        },
    )

    @Test
    fun `all starts at the earliest record`() {
        val window = trendMonthWindow(ledgerWith("2026-08-03", "2026-10-01"), Dates.range(Period.ALL, today), today)
        assertEquals(YearMonth.of(2026, 8), window.months.first())
        assertEquals(YearMonth.of(2026, 11), window.months.last())
        assertEquals(4, window.totalMonths)
        assertFalse(window.truncated)
    }

    @Test
    fun `all keeps the latest 24 months when data is older`() {
        val window = trendMonthWindow(ledgerWith("2024-01-05", "2026-11-01"), Dates.range(Period.ALL, today), today)
        assertEquals(24, window.months.size)
        assertEquals(YearMonth.of(2026, 11), window.months.last())
        assertEquals(YearMonth.of(2024, 12), window.months.first())
        assertEquals(35, window.totalMonths)
        assertTrue(window.truncated)
    }

    @Test
    fun `long custom range keeps the latest 24 months`() {
        val window = trendMonthWindow(
            ledgerWith("2026-01-01"),
            DateRange("2020-01-01", "2026-11-12"),
            today,
        )
        assertEquals(24, window.months.size)
        assertEquals(YearMonth.of(2024, 12), window.months.first())
        assertEquals(YearMonth.of(2026, 11), window.months.last())
    }

    @Test
    fun `very long custom range still keeps the latest 24 months`() {
        val window = trendMonthWindow(
            ledgerWith("2026-01-01"),
            DateRange("2005-01-01", "2026-11-12"),
            today,
        )
        assertEquals(24, window.months.size)
        assertEquals(YearMonth.of(2024, 12), window.months.first())
        assertEquals(YearMonth.of(2026, 11), window.months.last())
        assertEquals(263, window.totalMonths)
    }

    @Test
    fun `preset ranges cover only their months`() {
        val ledger = ledgerWith("2026-01-01")
        assertEquals(1, trendMonthWindow(ledger, Dates.range(Period.MONTH, today), today).totalMonths)
        assertEquals(1, trendMonthWindow(ledger, Dates.range(Period.LAST_MONTH, today), today).totalMonths)
        assertEquals(3, trendMonthWindow(ledger, Dates.range(Period.LAST_3_MONTHS, today), today).totalMonths)
        assertEquals(11, trendMonthWindow(ledger, Dates.range(Period.YEAR, today), today).totalMonths)
    }

    @Test
    fun `single month range is padded with prior months for context`() {
        val ledger = ledgerWith("2026-06-03", "2026-11-01")
        val display = trendDisplayWindow(ledger, Dates.range(Period.MONTH, today), today)
        assertEquals(6, display.months.size)
        assertEquals(YearMonth.of(2026, 6), display.months.first())
        assertEquals(YearMonth.of(2026, 11), display.months.last())
        assertEquals(setOf(YearMonth.of(2026, 11)), display.focus)
    }

    @Test
    fun `padding stops at the earliest record month`() {
        val ledger = ledgerWith("2026-09-03", "2026-11-01")
        val display = trendDisplayWindow(ledger, Dates.range(Period.MONTH, today), today)
        assertEquals(YearMonth.of(2026, 9), display.months.first())
        assertEquals(3, display.months.size)
        assertEquals(setOf(YearMonth.of(2026, 11)), display.focus)
    }

    @Test
    fun `single month without history is not padded`() {
        val ledger = ledgerWith("2026-11-02")
        val display = trendDisplayWindow(ledger, Dates.range(Period.MONTH, today), today)
        assertEquals(listOf(YearMonth.of(2026, 11)), display.months)
        assertEquals(setOf(YearMonth.of(2026, 11)), display.focus)
    }

    @Test
    fun `multi month range is not padded`() {
        val ledger = ledgerWith("2026-01-01")
        val display = trendDisplayWindow(ledger, Dates.range(Period.LAST_3_MONTHS, today), today)
        assertEquals(3, display.months.size)
        assertEquals(display.months.toSet(), display.focus)
    }
}
