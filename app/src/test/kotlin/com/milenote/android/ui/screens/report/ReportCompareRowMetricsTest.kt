package com.milenote.android.ui.screens.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 报告对比卡的固定列宽放大倍数（B43）。
 *
 * 旧实现行高/列宽都是固定值：系统字号 2.0× 时金额与标签被裁。
 * 这里只夹取倍数（1.0–2.0×），异常字号不能让列宽变成 0 或负。
 */
class ReportCompareRowMetricsTest {

    @Test
    fun `normal fonts keep the original column widths`() {
        assertEquals(1f, compareColumnScale(1f))
        assertEquals(1f, compareColumnScale(0.85f))
        assertEquals(1f, compareColumnScale(0f))
        assertEquals(1f, compareColumnScale(-2f))
    }

    @Test
    fun `column widths grow with the system font scale`() {
        assertEquals(1.3f, compareColumnScale(1.3f))
        assertEquals(1.5f, compareColumnScale(1.5f))
        assertEquals(2f, compareColumnScale(2f))
    }

    @Test
    fun `huge or broken font scales are clamped`() {
        // 再大也不无限挤压条形列；NaN 不能变成 0/NaN 列宽。
        assertEquals(2f, compareColumnScale(3f))
        assertEquals(2f, compareColumnScale(Float.POSITIVE_INFINITY))
        assertEquals(1f, compareColumnScale(Float.NaN))
    }
}
