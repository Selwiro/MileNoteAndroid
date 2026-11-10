package com.milenote.android.ui.screens.fuel

import com.milenote.android.domain.calc.EnergyInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 走势图几何：同日合并、单调插值（不折返/不过冲）、纵轴留白。
 *
 * 背景：横轴按日期比例定位后采样间隔极不均匀（同一天 0 px、跨月上百 px），原来的均匀
 * Catmull-Rom 控制点会越过段端点，曲线在 x 上回摆、自交成环（真机截图里的「打结」）。
 */
class FuelTrendGeometryTest {

    private fun interval(date: String, km: Double, liters: Double, source: String = "满箱法") =
        EnergyInterval(
            date = date,
            distance = km,
            amount = liters,
            rate = liters / km * 100.0,
            source = source,
        )

    @Test
    fun `same-day intervals merge by amount over distance`() {
        val merged = FuelTrendGeometry.mergeSameDate(
            listOf(
                interval("2026-07-13", 159.0, 15.66),
                interval("2026-07-13", 501.0, 36.47),
                interval("2026-07-27", 238.0, 19.40),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("2026-07-13", merged[0].date)
        assertEquals(660.0, merged[0].distance, 1e-9)
        assertEquals(52.13, merged[0].amount, 1e-9)
        // (15.66 + 36.47) / (159 + 501) * 100 —— 不是两点的算术平均
        assertEquals(7.898, merged[0].rate, 1e-3)
        assertEquals("2026-07-27", merged[1].date)
        assertEquals(8.15, merged[1].rate, 1e-2)
    }

    @Test
    fun `merge keeps one point per date and preserves date order`() {
        val merged = FuelTrendGeometry.mergeSameDate(
            listOf(
                interval("2026-08-18", 451.0, 29.58),
                interval("2026-08-18", 234.0, 20.70),
                interval("2026-09-01", 100.0, 10.40),
            ),
        )
        assertEquals(listOf("2026-08-18", "2026-09-01"), merged.map { it.date })
        assertEquals(merged.map { it.date }.distinct(), merged.map { it.date })
    }

    /** 真实病态输入：9/01→9/03 段只有 8.5 px，两端邻居却隔了 60~90 px。 */
    private fun pathologicalPoints() = listOf(
        ChartPoint(672.6f, 478.2f), // 8/18
        ChartPoint(732.0f, 318.0f), // 9/01（全曲线最高点）
        ChartPoint(740.5f, 478.2f), // 9/03
        ChartPoint(749.0f, 470.0f), // 9/05
    )

    @Test
    fun `monotone segments keep x inside the segment (no fold back)`() {
        val segments = FuelTrendGeometry.monotoneSegments(pathologicalPoints())
        assertEquals(3, segments.size)
        segments.forEach { segment ->
            val low = minOf(segment.from.x, segment.to.x)
            val high = maxOf(segment.from.x, segment.to.x)
            assertTrue("c1.x 越过段端点：${segment.c1.x} not in [$low, $high]", segment.c1.x in low..high)
            assertTrue("c2.x 越过段端点：${segment.c2.x} not in [$low, $high]", segment.c2.x in low..high)
        }
    }

    @Test
    fun `monotone curve never moves backwards in x`() {
        val sample = sampleCurve(FuelTrendGeometry.monotoneSegments(pathologicalPoints()))
        for (i in 1 until sample.size) {
            assertTrue(
                "x 在 t 处回摆：${sample[i - 1].x} -> ${sample[i].x}",
                sample[i].x >= sample[i - 1].x - 1e-3f,
            )
        }
    }

    @Test
    fun `monotone curve never overshoots the point range in y`() {
        val segments = FuelTrendGeometry.monotoneSegments(pathologicalPoints())
        segments.forEach { segment ->
            val low = minOf(segment.from.y, segment.to.y) - 1e-3f
            val high = maxOf(segment.from.y, segment.to.y) + 1e-3f
            assertTrue(segment.c1.y in low..high)
            assertTrue(segment.c2.y in low..high)
        }
        sampleCurve(segments).forEach { point -> assertTrue(point.y >= 317f && point.y <= 479f) }
    }

    /** 回归对照：原来的均匀 Catmull-Rom 在同一份输入上确实会折返（这就是要修的那个 bug）。 */
    @Test
    fun `uniform catmull-rom folds back on the same input`() {
        val sample = sampleCurve(catmullRomSegments(pathologicalPoints()))
        val folds = (1 until sample.size).count { sample[it].x < sample[it - 1].x - 1e-3f }
        assertTrue("预期旧算法出现 x 回摆，实际 $folds 次", folds > 0)
    }

    @Test
    fun `same-x neighbours degrade to a straight line`() {
        val segments = FuelTrendGeometry.monotoneSegments(
            listOf(ChartPoint(520f, 340f), ChartPoint(520f, 444f)),
        )
        assertEquals(1, segments.size)
        assertEquals(segments[0].from, segments[0].c1)
        assertEquals(segments[0].to, segments[0].c2)
    }

    @Test
    fun `y axis keeps headroom above the max rate`() {
        val topPad = 30f
        val plotHeight = 400f
        val maxRate = 10.4
        val headroom = plotHeight * FuelTrendGeometry.TopHeadroomRatio
        assertEquals(topPad + headroom, FuelTrendGeometry.yOf(maxRate, maxRate, topPad, plotHeight), 1e-3f)
        assertTrue(FuelTrendGeometry.yOf(maxRate, maxRate, topPad, plotHeight) > topPad)
        assertEquals(
            topPad + plotHeight,
            FuelTrendGeometry.yOf(0.0, maxRate, topPad, plotHeight),
            1e-3f,
        )
        // 值越大越靠上，且严格单调
        var previous = Float.MAX_VALUE
        listOf(0.0, 2.6, 5.2, 7.8, 10.4).forEach { rate ->
            val y = FuelTrendGeometry.yOf(rate, maxRate, topPad, plotHeight)
            assertTrue("y 应随 rate 单调递减", y < previous)
            previous = y
        }
    }

    @Test
    fun `single point and empty input are safe`() {
        assertTrue(FuelTrendGeometry.monotoneSegments(emptyList()).isEmpty())
        assertTrue(FuelTrendGeometry.monotoneSegments(listOf(ChartPoint(1f, 2f))).isEmpty())
        // maxRate 兜底不为 0：满值落在留白线下，即 0 + 100 * 0.06
        assertEquals(6.0f, FuelTrendGeometry.yOf(1.0, 0.0, 0f, 100f), 1e-3f)
    }

    private fun sampleCurve(segments: List<CubicSegment>, steps: Int = 40): List<ChartPoint> =
        segments.flatMap { segment ->
            (0..steps).map { step -> bezier(segment, step / steps.toFloat()) }
        }

    private fun bezier(segment: CubicSegment, t: Float): ChartPoint {
        val u = 1f - t
        fun blend(from: Float, c1: Float, c2: Float, to: Float) =
            u * u * u * from + 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t * to
        return ChartPoint(
            x = blend(segment.from.x, segment.c1.x, segment.c2.x, segment.to.x),
            y = blend(segment.from.y, segment.c1.y, segment.c2.y, segment.to.y),
        )
    }

    /** 修复前的实现（均匀 Catmull-Rom，控制点 /4.2），只用于回归对照。 */
    private fun catmullRomSegments(points: List<ChartPoint>): List<CubicSegment> =
        (0 until points.size - 1).map { index ->
            val p0 = if (index == 0) points[0] else points[index - 1]
            val p1 = points[index]
            val p2 = points[index + 1]
            val p3 = if (index + 2 < points.size) points[index + 2] else points[index + 1]
            CubicSegment(
                from = p1,
                to = p2,
                c1 = ChartPoint(p1.x + (p2.x - p0.x) / 4.2f, p1.y + (p2.y - p0.y) / 4.2f),
                c2 = ChartPoint(p2.x - (p3.x - p1.x) / 4.2f, p2.y - (p3.y - p1.y) / 4.2f),
            )
        }
}
