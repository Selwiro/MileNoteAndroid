package com.milenote.android.ui.screens.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对比卡 / 分享图共用的条长归一 [barFraction]。
 *
 * 重点：0 与负值画空轨道、超过最大值截到 1、坏输入（0 / NaN / Infinity）不能让 Canvas 画出负宽。
 */
class ReportCompareBarTest {

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
    fun `bar fraction guards infinite and negative max`() {
        assertEquals(0f, barFraction(10.0, Double.POSITIVE_INFINITY))
        assertEquals(0f, barFraction(Double.POSITIVE_INFINITY, 100.0))
        assertEquals(0f, barFraction(10.0, -100.0))
    }
}
