package com.milenote.android.ui.screens.fuel

import com.milenote.android.domain.calc.EnergyInterval

/** 图表几何里的一个采样点（像素坐标）。 */
internal data class ChartPoint(val x: Float, val y: Float)

/** 一段三次贝塞尔：起点 [from]、终点 [to]、两个控制点。 */
internal data class CubicSegment(
    val from: ChartPoint,
    val to: ChartPoint,
    val c1: ChartPoint,
    val c2: ChartPoint,
)

/**
 * 走势图的纯几何逻辑（不依赖 Compose，便于 JVM 单测）。
 *
 * 单独成文件的原因：横轴按日期比例定位后采样间隔极不均匀（同日 0 px、跨月上百 px），
 * 而均匀 Catmull-Rom 的控制点偏移取自相邻点间距，会越过段端点让曲线在 x 上回摆、自交成环。
 * 这段数学必须能被单测钉住，所以不写在 Composable 里。
 */
internal object FuelTrendGeometry {

    /** 绘图区顶部留白占绘图区高度的比例：峰值不贴顶，也给平滑留余量。 */
    const val TopHeadroomRatio = 0.06f

    /**
     * 纵轴映射：沿用设计稿的 0 基线（不做小波动放大），但顶部留 [headroomRatio] 的空白。
     *
     * 现状是 `rate / maxRate` 直接铺满，数据最大值等于轴顶时会零留白、峰值永远贴着顶边。
     */
    fun yOf(
        rate: Double,
        maxRate: Double,
        topPad: Float,
        plotHeight: Float,
        headroomRatio: Float = TopHeadroomRatio,
    ): Float {
        val safeMax = if (maxRate > 0.0) maxRate else 1.0
        val headroom = plotHeight * headroomRatio.coerceIn(0f, 0.5f)
        val ratio = (rate / safeMax).toFloat().coerceIn(0f, 1f)
        return topPad + headroom + (plotHeight - headroom) * (1f - ratio)
    }

    /**
     * 单调三次 Hermite（Fritsch–Carlson）分段，替代原来的均匀 Catmull-Rom。
     *
     * 两条硬保证（单测钉住）：
     * 1. 每段 x 单调递增（控制点落在两端点 x 之间）→ 曲线不会折返自交；
     * 2. 每段 y 不超出两端点的 y 范围（极值点导数强制 0）→ 不会过冲。
     *
     * 同 x 的相邻点（数据侧应已合并）退化成直线，不插值也不回摆。
     */
    fun monotoneSegments(points: List<ChartPoint>): List<CubicSegment> {
        val n = points.size
        if (n < 2) return emptyList()
        val h = FloatArray(n - 1) { points[it + 1].x - points[it].x }
        val slope = FloatArray(n - 1) { i ->
            if (h[i] <= 0f) 0f else (points[i + 1].y - points[i].y) / h[i]
        }
        val tangent = FloatArray(n)
        tangent[0] = slope[0]
        tangent[n - 1] = slope[n - 2]
        for (i in 1 until n - 1) {
            if (slope[i - 1] * slope[i] <= 0f) {
                // 局部极值：导数为 0，避免曲线越过顶点再折回来（过冲的根源）。
                tangent[i] = 0f
            } else {
                val w1 = 2f * h[i] + h[i - 1]
                val w2 = h[i] + 2f * h[i - 1]
                tangent[i] = (w1 + w2) / (w1 / slope[i - 1] + w2 / slope[i])
            }
        }
        return (0 until n - 1).map { i ->
            val from = points[i]
            val to = points[i + 1]
            if (h[i] <= 0f) {
                CubicSegment(from = from, to = to, c1 = from, c2 = to)
            } else {
                CubicSegment(
                    from = from,
                    to = to,
                    c1 = ChartPoint(from.x + h[i] / 3f, from.y + tangent[i] * h[i] / 3f),
                    c2 = ChartPoint(to.x - h[i] / 3f, to.y - tangent[i + 1] * h[i] / 3f),
                )
            }
        }
    }

    /**
     * 同一天多次加油合并成一个点（按 Σ油量 / Σ里程 加权）。
     *
     * 横轴按日期比例定位，同一天的区间会落在**完全相同的 x** 上：不合并就只能画成竖线，
     * 且平滑会在竖线两端各绕一个环。只影响走势图的点，不改 `stats`/概览/明细的任何口径
     * （总计油耗走的是各自的 Σ油量 / Σ里程）。
     */
    fun mergeSameDate(intervals: List<EnergyInterval>): List<EnergyInterval> =
        intervals.sortedBy { it.date }
            .groupBy { it.date }
            .map { (date, sameDay) ->
                if (sameDay.size == 1) {
                    sameDay.first()
                } else {
                    val distance = sameDay.sumOf { it.distance }
                    val amount = sameDay.sumOf { it.amount }
                    EnergyInterval(
                        date = date,
                        distance = distance,
                        amount = amount,
                        rate = if (distance > 0) amount / distance * 100.0 else sameDay.last().rate,
                        source = sameDay.first().source,
                    )
                }
            }
}
