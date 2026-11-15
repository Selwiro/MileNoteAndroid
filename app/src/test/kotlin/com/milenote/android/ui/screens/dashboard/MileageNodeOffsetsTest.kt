package com.milenote.android.ui.screens.dashboard

import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 里程折线图的逐月点位与读屏文案（B39）。
 *
 * 覆盖层的读屏节点和 Canvas 画线共用 [mileageNodeOffsets]，这里锁住几何公式，
 * 避免以后只改一边导致「读屏报的位置和视觉对不上」。
 */
class MileageNodeOffsetsTest {

    @Before
    fun setUp() = I18n.applyEnglish(false)

    @After
    fun tearDown() = I18n.applyEnglish(false)

    private fun nodes(
        values: List<Double>,
        maxValue: Double = 200.0,
        reveal: Float = 1f,
    ) = mileageNodeOffsets(
        values = values,
        maxValue = maxValue,
        widthPx = 1000f,
        heightPx = 400f,
        leftPadPx = 20f,
        rightPadPx = 20f,
        topPadPx = 30f,
        axisRowPx = 40f,
        axisGapPx = 10f,
        reveal = reveal,
    )

    @Test
    fun `points spread across the plot area and scale by value`() {
        val points = nodes(listOf(0.0, 100.0, 200.0))
        assertEquals(3, points.size)
        // 横向等距：左 20 / 中 500 / 右 980
        assertEquals(20f, points[0].first, 0.01f)
        assertEquals(500f, points[1].first, 0.01f)
        assertEquals(980f, points[2].first, 0.01f)
        // 纵向：基线 bottom = 400 - 40 - 10 = 350，us able = 320
        assertEquals(350f, points[0].second, 0.01f)
        assertEquals(190f, points[1].second, 0.01f)
        assertEquals(30f, points[2].second, 0.01f)
    }

    @Test
    fun `single month sits at the middle of the plot area`() {
        val points = nodes(listOf(123.0))
        assertEquals(1, points.size)
        assertEquals(500f, points[0].first, 0.01f)
        // 单月没有可比对象：固定在绘图区垂直中间偏下
        assertEquals(30f + 320f * 0.55f, points[0].second, 0.01f)
    }

    @Test
    fun `reveal scales the vertical growth`() {
        // 两个月时按 reveal 生长：生长到一半，最高点从基线 350 向上走 320*0.5
        val half = nodes(listOf(0.0, 200.0), reveal = 0.5f)
        assertEquals(350f, half[0].second, 0.01f)
        assertEquals(190f, half[1].second, 0.01f)
    }

    @Test
    fun `empty input has no nodes`() {
        assertTrue(nodes(emptyList()).isEmpty())
    }

    @Test
    fun `label reads month and distance`() {
        val text = mileagePointAccessibilityLabel("11 月", 1380.0, Units.distanceUnit)
        assertTrue(text, text.contains("11 月"))
        assertTrue(text, text.contains("1,380"))
        assertTrue(text, text.contains(Units.distanceUnit))
    }

    @Test
    fun `english label is fully translated`() {
        I18n.applyEnglish(true)
        val text = mileagePointAccessibilityLabel("Nov", 1380.0, Units.distanceUnit)
        assertFalse("英文界面不能落中文：$text", text.any { it.code in 0x4E00..0x9FFF })
        assertTrue(text, text.contains("Nov"))
        assertTrue(text, text.contains("1,380"))
    }
}
