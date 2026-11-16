package com.milenote.android.ui.screens.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 油价卡「下次调价」的换行阈值。
 *
 * 1.0× 时「下次调价：11月26日24时」能挤在价格行右侧；1.3× 起同一行放不下，
 * 必须折到第二行，否则 2.0× 下只剩「下次调价：」、日期被卡片右边缘裁掉。
 */
class FuelPriceCardLayoutTest {

    @Test
    fun `normal font keeps the label on the price row`() {
        assertFalse(fuelCardStacksNextAdjustment(1.0f))
        assertFalse(fuelCardStacksNextAdjustment(1.15f))
    }

    @Test
    fun `enlarged font moves the label to its own line`() {
        assertTrue(fuelCardStacksNextAdjustment(1.3f))
        assertTrue(fuelCardStacksNextAdjustment(2.0f))
        assertTrue(fuelCardStacksNextAdjustment(2.5f))
    }

    @Test
    fun `broken font scale falls back to the normal row`() {
        assertFalse(fuelCardStacksNextAdjustment(Float.NaN))
    }
}

/**
 * 调价方向归一：数据源缺失方向时必须落到 [FuelTrend.UNKNOWN]，
 * 界面据此显示「预计待公布」，而不是整块不渲染（2026-11-16 用户反馈）。
 */
class FuelTrendTest {

    @Test
    fun `missing or blank direction is unknown`() {
        assertEquals(FuelTrend.UNKNOWN, fuelTrendOf(null))
        assertEquals(FuelTrend.UNKNOWN, fuelTrendOf(""))
        assertEquals(FuelTrend.UNKNOWN, fuelTrendOf("   "))
    }

    @Test
    fun `direction words map to up down and unchanged`() {
        assertEquals(FuelTrend.DOWN, fuelTrendOf("预计下调"))
        assertEquals(FuelTrend.DOWN, fuelTrendOf("下调"))
        assertEquals(FuelTrend.UP, fuelTrendOf("预计上调"))
        assertEquals(FuelTrend.UP, fuelTrendOf("上涨"))
        assertEquals(FuelTrend.UNCHANGED, fuelTrendOf("搁浅"))
        assertEquals(FuelTrend.UNCHANGED, fuelTrendOf("不作调整"))
    }

    @Test
    fun `unrecognized direction is unknown instead of guessed`() {
        assertEquals(FuelTrend.UNKNOWN, fuelTrendOf("待公布"))
    }
}
