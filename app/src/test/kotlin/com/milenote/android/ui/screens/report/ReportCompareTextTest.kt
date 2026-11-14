package com.milenote.android.ui.screens.report

import com.milenote.android.domain.calc.ReportDelta
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 报告页环比 / 同比文案（[categoryDeltaChip] / [metricCompareChip]）。
 *
 * 重点：上期 0 不能画成 ∞%、差额过小写「≈」、记录数与里程按绝对值 + 单位而不是百分比。
 */
class ReportCompareTextTest {

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    // ---------------------------------------------------------------- 分类

    @Test
    fun `category chip shows percent up and down`() {
        assertEquals("环比 ↑ 20%", categoryDeltaChip("环比", 120.0, 100.0))
        assertEquals("环比 ↓ 20%", categoryDeltaChip("环比", 80.0, 100.0))
        assertEquals("同比 ↑ 150%", categoryDeltaChip("同比", 5.0, 2.0))
    }

    @Test
    fun `category chip uses new when previous period had none`() {
        assertEquals("环比 新增", categoryDeltaChip("环比", 35.2, 0.0))
        assertEquals("同比 新增", categoryDeltaChip("同比", 35.2, 0.0))
    }

    @Test
    fun `category chip flattens sub half yuan difference`() {
        assertEquals("环比 ≈", categoryDeltaChip("环比", 100.2, 100.0))
        assertEquals("环比 ≈", categoryDeltaChip("环比", 99.6, 100.0))
    }

    // ---------------------------------------------------------------- 指标

    @Test
    fun `metric chip uses percent for money metrics`() {
        assertEquals("环比 ↑ 12.3%", metricCompareChip("环比", ReportDelta(112.3, 100.0)))
        assertEquals("同比 ↓ 4.1%", metricCompareChip("同比", ReportDelta(95.9, 100.0)))
    }

    @Test
    fun `metric chip avoids infinity when previous is zero`() {
        assertEquals("环比 上期无", metricCompareChip("环比", ReportDelta(12.0, 0.0)))
        assertEquals("同比 上期无", metricCompareChip("同比", ReportDelta(12.0, 0.0)))
    }

    @Test
    fun `metric chip flats out negligible percent change`() {
        assertEquals("环比 ≈", metricCompareChip("环比", ReportDelta(100.01, 100.0)))
    }

    @Test
    fun `huge increases switch to multiples instead of five digit percents`() {
        // 演示数据里基期很小的时候会出现 12,486.8% 这种数字，改画倍数更好读。
        assertEquals("同比 ↑ 125.9×", metricCompareChip("同比", ReportDelta(1258.68, 10.0)))
        assertEquals("环比 ↑ 90×", categoryDeltaChip("环比", 45.0, 0.5))
        // 跌幅有下限（本期不为负），不会触发倍数分支。
        assertEquals("环比 ↓ 100%", metricCompareChip("环比", ReportDelta(0.0, 10.0)))
    }

    @Test
    fun `count metrics show absolute difference with unit`() {
        assertEquals("环比 +1 笔", metricCompareChip("环比", ReportDelta(31.0, 30.0), "笔"))
        assertEquals("环比 −2 笔", metricCompareChip("环比", ReportDelta(28.0, 30.0), "笔"))
        assertEquals("同比 +20 km", metricCompareChip("同比", ReportDelta(320.0, 300.0), "km"))
        assertEquals("同比 ≈", metricCompareChip("同比", ReportDelta(300.2, 300.0), "km"))
    }

    // ---------------------------------------------------------------- 英文

    @Test
    fun `chips switch to english labels and words`() {
        I18n.applyEnglish(true)
        assertEquals("MoM ↑ 20%", categoryDeltaChip("MoM", 120.0, 100.0))
        assertEquals("MoM new", categoryDeltaChip("MoM", 35.2, 0.0))
        assertEquals("YoY ≈", metricCompareChip("YoY", ReportDelta(100.01, 100.0)))
        assertEquals("MoM no data", metricCompareChip("MoM", ReportDelta(12.0, 0.0)))
        assertEquals("MoM +3 entries", metricCompareChip("MoM", ReportDelta(33.0, 30.0), "entries"))
    }
}
