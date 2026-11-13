package com.milenote.android.ui.components

import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Period
import com.milenote.android.i18n.I18n
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 范围胶囊的紧凑文案：中文保持「本月 / 上月 / 近 3 个月 / 今年」，
 * 英文改用月份简称（Nov / Oct / Sep–Nov / 2026），避免 32dp 胶囊把卡头文字 tab 挤到截断。
 */
class RangePillLabelTest {

    private val today = LocalDate.of(2026, 11, 13)

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `chinese pill labels keep the preset wording`() {
        assertEquals("全部", rangePillLabel(Period.ALL, null, today))
        assertEquals("本月", rangePillLabel(Period.MONTH, null, today))
        assertEquals("上月", rangePillLabel(Period.LAST_MONTH, null, today))
        assertEquals("近 3 个月", rangePillLabel(Period.LAST_3_MONTHS, null, today))
        assertEquals("今年", rangePillLabel(Period.YEAR, null, today))
    }

    @Test
    fun `english pill labels use short month names`() {
        I18n.applyEnglish(true)
        assertEquals("All", rangePillLabel(Period.ALL, null, today))
        assertEquals("Nov", rangePillLabel(Period.MONTH, null, today))
        assertEquals("Oct", rangePillLabel(Period.LAST_MONTH, null, today))
        assertEquals("Sep–Nov", rangePillLabel(Period.LAST_3_MONTHS, null, today))
        assertEquals("2026", rangePillLabel(Period.YEAR, null, today))
    }

    @Test
    fun `custom range keeps the date label in both languages`() {
        val custom = DateRange("2026-10-01", "2026-10-18")
        assertEquals("10.01–10.18", rangePillLabel(Period.MONTH, custom, today))
        I18n.applyEnglish(true)
        assertEquals("10.01–10.18", rangePillLabel(Period.MONTH, custom, today))
    }
}
