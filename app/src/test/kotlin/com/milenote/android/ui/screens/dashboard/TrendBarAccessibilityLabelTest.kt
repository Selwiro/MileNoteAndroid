package com.milenote.android.ui.screens.dashboard

import androidx.compose.ui.graphics.Color
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 趋势柱的读屏文案（B39）：读屏要能逐柱听到「月份 / 合计 / 占比 / 主要分类」。
 */
class TrendBarAccessibilityLabelTest {

    @Before
    fun setUp() = I18n.applyEnglish(false)

    @After
    fun tearDown() = I18n.applyEnglish(false)

    private fun bar(
        label: String = "11 月",
        total: Double = 300.0,
        categories: List<Pair<String, Double>> = listOf("油费" to 200.0, "保险" to 100.0),
    ): MonthBar = MonthBar(
        key = "2026-11",
        label = label,
        total = total,
        categories = categories.map { ChartCategory(it.first, it.second, Color.Red) },
    )

    @Test
    fun `label reads month total share and categories`() {
        val text = trendBarAccessibilityLabel(bar(), rangeTotal = 1200.0)
        assertTrue(text, text.contains("11 月"))
        assertTrue(text, text.contains("300.00"))
        assertTrue(text, text.contains("25%"))
        assertTrue(text, text.contains("油费 200.00"))
        assertTrue(text, text.contains("保险 100.00"))
        assertFalse("不能出现 null 之类的脏值：$text", text.contains("null"))
    }

    @Test
    fun `label trims long category lists with a remainder`() {
        val text = trendBarAccessibilityLabel(
            bar(
                categories = listOf(
                    "油费" to 100.0, "保险" to 80.0, "保养" to 60.0, "维修" to 40.0, "停车费" to 20.0,
                ),
            ),
            rangeTotal = 300.0,
            categoryLimit = 3,
        )
        assertTrue(text, text.contains("油费 100.00"))
        assertTrue(text, text.contains("等 2 项"))
    }

    @Test
    fun `zero month says no expenses`() {
        val text = trendBarAccessibilityLabel(bar(total = 0.0, categories = emptyList()), rangeTotal = 0.0)
        assertEquals("11 月，无支出", text)
    }

    @Test
    fun `english label is fully translated`() {
        I18n.applyEnglish(true)
        val text = trendBarAccessibilityLabel(bar(label = "Nov"), rangeTotal = 1200.0)
        assertFalse("英文界面不能落中文：$text", text.any { it.code in 0x4E00..0x9FFF })
        assertTrue(text, text.contains("Nov"))
        assertTrue(text, text.contains("25%"))
        assertTrue(text, text.contains("CNY"))
    }
}
