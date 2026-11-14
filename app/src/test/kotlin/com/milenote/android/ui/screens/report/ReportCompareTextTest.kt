package com.milenote.android.ui.screens.report

import com.milenote.android.domain.calc.ReportDelta
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 报告页图表上的增减标注（[categoryDeltaLabel] / [deltaArrowText] / [metricCompareChip]）与归一函数。
 *
 * 重点：上期 0 不能画成 ∞%、差额过小写「≈」、记录数与里程按绝对值 + 单位而不是百分比；
 * 条长归一遇到 0 / 负数 / NaN 不能让 Canvas 画出负宽。
 */
class ReportCompareTextTest {

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    // ---------------------------------------------------------------- 分类

    @Test
    fun `category label shows percent up and down`() {
        assertEquals("↑ 20%", categoryDeltaLabel(120.0, 100.0))
        assertEquals("↓ 20%", categoryDeltaLabel(80.0, 100.0))
        assertEquals("↑ 150%", categoryDeltaLabel(5.0, 2.0))
    }

    @Test
    fun `category label uses new when previous period had none`() {
        assertEquals("新增", categoryDeltaLabel(35.2, 0.0))
    }

    @Test
    fun `category label flattens sub half yuan difference`() {
        assertEquals("≈", categoryDeltaLabel(100.2, 100.0))
        assertEquals("≈", categoryDeltaLabel(99.6, 100.0))
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
        assertEquals("↑ 90×", categoryDeltaLabel(45.0, 0.5))
        assertEquals("↑ 125.9×", deltaArrowText(1258.68, 10.0))
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
        assertEquals("↑ 20%", categoryDeltaLabel(120.0, 100.0))
        assertEquals("new", categoryDeltaLabel(35.2, 0.0))
        assertEquals("YoY ≈", metricCompareChip("YoY", ReportDelta(100.01, 100.0)))
        assertEquals("MoM no data", metricCompareChip("MoM", ReportDelta(12.0, 0.0)))
        assertEquals("MoM +3 entries", metricCompareChip("MoM", ReportDelta(33.0, 30.0), "entries"))
    }

    // ---------------------------------------------------------------- 条长归一

    @Test
    fun `bar fraction normalises and guards bad input`() {
        assertEquals(0.5f, barFraction(50.0, 100.0))
        assertEquals(1f, barFraction(120.0, 100.0))
        assertEquals(0f, barFraction(0.0, 100.0))
        assertEquals(0f, barFraction(-5.0, 100.0))
        assertEquals(0f, barFraction(50.0, 0.0))
        assertEquals(0f, barFraction(Double.NaN, 100.0))
    }

    @Test
    fun `diverging fraction uses absolute delta and guards missing comparison`() {
        assertEquals(0.5f, divergingFraction(-50.0, 100.0))
        assertEquals(1f, divergingFraction(300.0, 100.0))
        assertEquals(0f, divergingFraction(null, 100.0))
        assertEquals(0f, divergingFraction(10.0, 0.0))
    }
}
