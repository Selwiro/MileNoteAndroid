package com.milenote.android.ui.screens.fuel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.FuelInterval
import com.milenote.android.domain.format.Format
import com.milenote.android.ui.theme.MileNoteThemeTokens
import java.time.LocalDate
import kotlin.math.abs

/**
 * 油耗走势图（Canvas 自绘，对应 Web `fuelPage` 的 `.fuel-chart` 折线）。
 *
 * 视觉规格（本轮增强）：
 * - 平滑曲线（Catmull-Rom → 三次贝塞尔），下方主色 0.25 → 透明的面积渐变；
 * - 圆点 + 最近一次油耗的 pill（`最近 7.38`），点按数据点显示「数值 · 日期」气泡；
 * - 网格线弱化到 `outlineVariant × 0.5`；
 * - 横轴[monthAxis]：筛选范围 > 31 天显示月份（6月 / 7月…），≤ 31 天显示 5 天一个的 MM-DD 刻度；
 * - 纵轴下限 10 L/100km（与 Web 一致，避免小波动被放大）；
 * - 线/点使用主题分类色中的「油费」色，深色与 5 套皮肤自动适配；
 * - 数据为空时不应调用（由页面渲染 `EmptyState`）。
 *
 * @param intervals 已按筛选范围过滤的有效油耗区间（建议 ≤ 12）。
 * @param rangeStartIso 筛选范围起始日（ISO）；null 时取数据首/末区间日期作为横轴范围。
 * @param rangeEndIso 筛选范围结束日（ISO）；null 时取数据首/末区间日期作为横轴范围。
 */
@Composable
fun FuelTrendChart(
    intervals: List<FuelInterval>,
    modifier: Modifier = Modifier,
    rangeStartIso: String? = null,
    rangeEndIso: String? = null,
) {
    if (intervals.isEmpty()) return
    // 展示筛选范围内的全部区间（不再截断为最近 12 个），按日期排序保证曲线顺序。
    val points = remember(intervals) { intervals.sortedBy { it.date } }
    val maxRate = remember(points) { maxOf(10.0, points.maxOf { it.rate }) }
    val latest = points.last()

    val lineColor = MileNoteThemeTokens.categoryColors.first()
    val scheme = MaterialTheme.colorScheme
    val gridColor = scheme.outlineVariant.copy(alpha = 0.5f)
    val labelColor = scheme.onSurfaceVariant
    val pillTextColor = scheme.surface
    val bubbleContainer = scheme.inverseSurface
    val bubbleContent = scheme.inverseOnSurface
    val pointHalo = scheme.surface

    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        color = labelColor,
        fontSize = 9.sp,
        lineHeight = 11.sp,
    )
    val bubbleStyle = MaterialTheme.typography.labelMedium.copy(
        color = bubbleContent,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontFeatureSettings = "tnum",
    )
    val pillStyle = TextStyle(
        color = pillTextColor,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val textMeasurer = rememberTextMeasurer()

    var selected by remember(points) { mutableIntStateOf(-1) }

    val density = LocalDensity.current
    val description = "油耗走势：最近 ${points.size} 个有效区间，最近一次 " +
        "${Format.money(latest.rate)} 升/百公里。点按数据点查看数值。"

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .semantics { contentDescription = description },
    ) {
        val leftPad = with(density) { 40.dp.toPx() }
        val rightPad = with(density) { 18.dp.toPx() }
        val topPad = with(density) { 30.dp.toPx() }
        val bottomPad = with(density) { 28.dp.toPx() }
        val totalWidth = with(density) { maxWidth.toPx() }
        val totalHeight = with(density) { maxHeight.toPx() }
        val plotWidth = (totalWidth - leftPad - rightPad).coerceAtLeast(1f)
        val plotHeight = (totalHeight - topPad - bottomPad).coerceAtLeast(1f)
        val count = points.size
        val pillGap = with(density) { 8.dp.toPx() }

        // 横轴按日期比例定位（不是按点序号），使「5 天刻度 / 月份刻度」与数据日期一致。
        val rangeStart = remember(rangeStartIso, points) {
            Dates.parse(rangeStartIso) ?: points.firstOrNull()?.date?.let(Dates::parse) ?: Dates.today()
        }
        val rangeEnd = remember(rangeEndIso, points) {
            Dates.parse(rangeEndIso) ?: points.lastOrNull()?.date?.let(Dates::parse) ?: rangeStart
        }
        val spanDays = (Dates.daysBetween(rangeStart, rangeEnd) + 1).coerceAtLeast(1)
        val monthAxis = spanDays > 31
        val startEpochDay = rangeStart.toEpochDay().toFloat()
        val domainDays = (rangeEnd.toEpochDay().toFloat() - startEpochDay).coerceAtLeast(1f)

        fun xOf(index: Int): Float {
            if (count <= 1) return leftPad + plotWidth / 2f
            val date = Dates.parse(points[index].date) ?: return leftPad + plotWidth / 2f
            val fraction = ((date.toEpochDay().toFloat() - startEpochDay) / domainDays).coerceIn(0f, 1f)
            return leftPad + plotWidth * fraction
        }

        fun yOf(rate: Double): Float {
            val ratio = (rate / maxRate).toFloat().coerceIn(0f, 1f)
            return topPad + plotHeight * (1f - ratio)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points, maxRate, plotWidth, plotHeight) {
                    detectTapGestures { offset ->
                        val inside = offset.x >= leftPad - 16f &&
                            offset.x <= leftPad + plotWidth + 16f &&
                            offset.y >= topPad - 20f &&
                            offset.y <= topPad + plotHeight + 28f
                        if (!inside) {
                            selected = -1
                            return@detectTapGestures
                        }
                        val nearest = points.indices.minByOrNull { abs(xOf(it) - offset.x) }
                        selected = when {
                            nearest == null -> -1
                            selected == nearest -> -1
                            else -> nearest
                        }
                    }
                },
        ) {
            // 网格线（弱化）+ 纵轴刻度（max / max÷2 / 0）
            val gridWidth = 1.dp.toPx()
            val axisValues = listOf(maxRate, maxRate / 2.0, 0.0)
            axisValues.forEachIndexed { index, value ->
                val gy = topPad + plotHeight * (index * 0.5f)
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, gy),
                    end = Offset(leftPad + plotWidth, gy),
                    strokeWidth = gridWidth,
                )
                val layout = textMeasurer.measure(Format.fmt(value), axisStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        leftPad - layout.size.width - 6.dp.toPx(),
                        gy - layout.size.height / 2f,
                    ),
                )
            }

            val plotPoints = points.mapIndexed { index, interval ->
                Offset(xOf(index), yOf(interval.rate))
            }
            val smooth = smoothPath(plotPoints)

            // 面积渐变（主色 0.25 → 透明）
            if (count > 1) {
                val fillPath = Path().apply {
                    addPath(smooth)
                    lineTo(xOf(count - 1), topPad + plotHeight)
                    lineTo(xOf(0), topPad + plotHeight)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent),
                        startY = topPad,
                        endY = topPad + plotHeight,
                    ),
                )
            }

            // 平滑曲线
            if (count > 1) {
                drawPath(
                    path = smooth,
                    color = lineColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // 选中点竖直辅助线
            val selectedIndex = selected
            if (selectedIndex in points.indices) {
                val x = xOf(selectedIndex)
                drawLine(
                    color = lineColor.copy(alpha = 0.35f),
                    start = Offset(x, topPad),
                    end = Offset(x, topPad + plotHeight),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // 数据点 + 日期标签
            points.forEachIndexed { index, interval ->
                val x = xOf(index)
                val y = yOf(interval.rate)
                val isSelected = index == selectedIndex
                if (isSelected) {
                    drawCircle(color = pointHalo, radius = 7.dp.toPx(), center = Offset(x, y))
                }
                drawCircle(
                    color = lineColor,
                    radius = if (isSelected) 5.dp.toPx() else 3.5.dp.toPx(),
                    center = Offset(x, y),
                )

            }

            // 横轴刻度：>31 天按月份（过多时抽稀），≤31 天按 5 天一个 MM-DD。
            val axisLabelStyle = axisStyle
            val axisY = topPad + plotHeight + 6.dp.toPx()
            fun drawAxisLabel(text: String, centerX: Float) {
                val layout = textMeasurer.measure(text, axisLabelStyle)
                val labelX = (centerX - layout.size.width / 2f)
                    .coerceIn(leftPad, leftPad + plotWidth - layout.size.width)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(labelX, axisY),
                )
            }
            if (monthAxis) {
                val monthStarts = mutableListOf<LocalDate>()
                var cursor = rangeStart.withDayOfMonth(1)
                val lastMonth = rangeEnd.withDayOfMonth(1)
                while (!cursor.isAfter(lastMonth)) {
                    monthStarts += cursor
                    cursor = cursor.plusMonths(1)
                }
                val sampleStep = if (monthStarts.size > 6) 2 else 1
                monthStarts.forEachIndexed { order, month ->
                    if (order % sampleStep != 0 && order != monthStarts.lastIndex) return@forEachIndexed
                    val fraction = ((month.toEpochDay().toFloat() - startEpochDay) / domainDays)
                        .coerceIn(0f, 1f)
                    drawAxisLabel("${month.monthValue}月", leftPad + plotWidth * fraction)
                }
            } else {
                var tick = rangeStart
                while (!tick.isAfter(rangeEnd)) {
                    val fraction = ((tick.toEpochDay().toFloat() - startEpochDay) / domainDays)
                        .coerceIn(0f, 1f)
                    val label = "${tick.monthValue.toString().padStart(2, '0')}-" +
                        tick.dayOfMonth.toString().padStart(2, '0')
                    drawAxisLabel(label, leftPad + plotWidth * fraction)
                    tick = tick.plusDays(5)
                }
            }

            // 最近一次油耗 pill（选中最后一点时由气泡替代，避免重复）
            if (selectedIndex != count - 1) {
                val lastX = xOf(count - 1)
                val lastY = yOf(latest.rate)
                drawPill(
                    textMeasurer = textMeasurer,
                    text = "最近 ${Format.money(latest.rate)}",
                    style = pillStyle,
                    centerX = lastX,
                    anchorY = lastY,
                    placeAbove = true,
                    gap = pillGap,
                    horizontalPad = 8.dp.toPx(),
                    verticalPad = 4.dp.toPx(),
                    corner = 8.dp.toPx(),
                    minX = leftPad,
                    maxX = leftPad + plotWidth,
                    minY = 0f,
                    maxY = totalHeight,
                    containerColor = lineColor,
                )
            }

            // 点选数值气泡
            if (selectedIndex in points.indices) {
                val interval = points[selectedIndex]
                val x = xOf(selectedIndex)
                val y = yOf(interval.rate)
                drawPill(
                    textMeasurer = textMeasurer,
                    text = "${Format.money(interval.rate)} · ${interval.date.takeLast(5)}",
                    style = bubbleStyle,
                    centerX = x,
                    anchorY = y,
                    placeAbove = true,
                    gap = pillGap,
                    horizontalPad = 10.dp.toPx(),
                    verticalPad = 6.dp.toPx(),
                    corner = 8.dp.toPx(),
                    minX = leftPad,
                    maxX = leftPad + plotWidth,
                    minY = 0f,
                    maxY = totalHeight,
                    containerColor = bubbleContainer,
                )
            }
        }
    }
}

/** Catmull-Rom → 三次贝塞尔，得到过所有数据点的平滑曲线。 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size < 2) return path
    for (index in 0 until points.size - 1) {
        val p0 = if (index == 0) points[0] else points[index - 1]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = if (index + 2 < points.size) points[index + 2] else points[index + 1]
        // 控制点偏移更大 → 曲线更圆滑（仍过所有数据点）。
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 4.2f,
            p1.y + (p2.y - p0.y) / 4.2f,
            p2.x - (p3.x - p1.x) / 4.2f,
            p2.y - (p3.y - p1.y) / 4.2f,
            p2.x,
            p2.y,
        )
    }
    return path
}

/** 在数据点上方（空间不足时自动夹到画布内）绘制圆角文字 pill。 */
private fun DrawScope.drawPill(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    centerX: Float,
    anchorY: Float,
    placeAbove: Boolean,
    gap: Float,
    horizontalPad: Float,
    verticalPad: Float,
    corner: Float,
    minX: Float,
    maxX: Float,
    minY: Float,
    maxY: Float,
    containerColor: Color,
) {
    val layout = textMeasurer.measure(text, style)
    val pillWidth = layout.size.width + horizontalPad * 2
    val pillHeight = layout.size.height + verticalPad * 2
    val left = (centerX - pillWidth / 2f)
        .coerceIn(minX, (maxX - pillWidth).coerceAtLeast(minX))
    val preferredTop = if (placeAbove) anchorY - pillHeight - gap else anchorY + gap
    val top = preferredTop.coerceIn(minY, (maxY - pillHeight).coerceAtLeast(minY))
    drawRoundRect(
        color = containerColor,
        topLeft = Offset(left, top),
        size = Size(pillWidth, pillHeight),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            left + horizontalPad,
            top + (pillHeight - layout.size.height) / 2f,
        ),
    )
}
