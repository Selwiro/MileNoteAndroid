package com.milenote.android.ui.screens.fuel

import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.Units
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 能耗走势图数据点的读屏文案（B39）：每个点要能单独聚焦并读出「日期 + 数值 + 单位」。
 */
class FuelPointAccessibilityLabelTest {

    @Before
    fun setUp() = I18n.applyEnglish(false)

    @After
    fun tearDown() = I18n.applyEnglish(false)

    @Test
    fun `label reads date value and unit`() {
        val text = fuelPointAccessibilityLabel("2026-11-14", 8.9, Units.consumption)
        assertTrue(text, text.contains("2026.11.14"))
        assertTrue(text, text.contains("8.9"))
        assertTrue(text, text.contains(Units.consumption))
    }

    @Test
    fun `english label is fully translated`() {
        I18n.applyEnglish(true)
        val text = fuelPointAccessibilityLabel("2026-11-14", 18.2, Units.energyConsumption)
        assertFalse("英文界面不能落中文：$text", text.any { it.code in 0x4E00..0x9FFF })
        assertTrue(text, text.contains("2026.11.14"))
        assertTrue(text, text.contains("18.2"))
    }

    @Test
    fun `unparsable date falls back to the raw value`() {
        val text = fuelPointAccessibilityLabel("", 7.5, Units.consumption)
        assertTrue(text, text.contains("7.5"))
    }
}
