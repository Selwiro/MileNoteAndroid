package com.milenote.android.ui

import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Period
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表筛选状态：计数口径、自定义范围优先、重置清空全部条件。 */
class ListFilterStateTest {

    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun `active count includes time and each selected value`() {
        assertEquals(0, ListFilterState().activeCount)
        assertEquals(
            1,
            ListFilterState(period = Period.MONTH).activeCount,
        )
        assertEquals(
            3,
            ListFilterState(
                period = Period.MONTH,
                categories = setOf("油费", "停车费"),
            ).activeCount,
        )
        assertEquals(
            2,
            ListFilterState(types = setOf("fuel", "charge")).activeCount,
        )
    }

    @Test
    fun `custom range overrides preset`() {
        val filter = ListFilterState(
            period = Period.YEAR,
            customRange = DateRange("2026-11-01", "2026-11-02"),
        )
        val range = filter.range(today)
        assertEquals("2026-11-01", range.from)
        assertEquals("2026-11-02", range.to)
        assertTrue(filter.hasTimeFilter)
    }

    @Test
    fun `cleared resets everything including query`() {
        val filter = ListFilterState(
            period = Period.MONTH,
            categories = setOf("油费"),
            types = setOf("fuel"),
            query = "加油",
        )
        val cleared = filter.cleared()
        assertEquals(ListFilterState(), cleared)
        assertEquals(Period.ALL, cleared.period)
        assertFalse(cleared.hasTimeFilter)
    }
}
