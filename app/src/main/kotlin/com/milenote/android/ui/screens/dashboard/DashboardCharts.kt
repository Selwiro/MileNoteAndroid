package com.milenote.android.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.CategoryValue
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.theme.LocalMileNoteCategoryColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteCategoryNames
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.moneySmall
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 总览页图表（自绘 Canvas，不引入第三方图表库）。
 *
 * 设计规范 §2.7 / §6.2：
 * - 支出趋势：按所选月份区间（半年 / 一年 / 自定义）绘制分类堆叠柱，点击柱段显示
 *   「YYYY-MM · 分类：金额（x%）」；
 * - 费用构成：环图 + 下方分类图例（色点 + 名称），点击扇区显示金额，中心为总支出。
 * - 分类色一律来自 [LocalMileNoteCategoryColors]（9 类固定顺序、深浅双版本、色觉缺陷安全）。
 */

/** 图表中的一段分类数据。 */
internal data class ChartCategory(
    val name: String,
    val value: Double,
    val color: Color,
)

/** 某个月的堆叠柱数据。 */
internal data class MonthBar(
    /** `YYYY-MM`。 */
    val key: String,
    /** 底部月份标签，如 `11 月`。 */
    val label: String,
    /** 该月用车支出合计。 */
    val total: Double,
    /** 有金额的分类段（顺序与 9 类固定顺序一致）。 */
    val categories: List<ChartCategory>,
)

/** 一个自然月的行驶里程。 */
internal data class MileageBar(
    /** `YYYY-MM`。 */
    val key: String,
    /** 横轴标签：`11 月`。 */
    val label: String,
    /** 该月行驶里程 km。 */
    val km: Double,
)

/**
 * 里程趋势数据：在所选统计范围内按自然月计算每月行驶里程。
 *
 * 月里程 = 该月最大 odometer − 上月最大 odometer；上月无读数时用「该月 max − min」；
 * 无有效里程的点置 0（整段都无数据时由图表显示空态）。
 * 「全部」= 最近 12 个月；本月 / 今年 / 自定义 = 范围覆盖的自然月。
 */
internal fun buildMileageBars(
    ledger: Ledger,
    range: DateRange,
    today: LocalDate = Dates.today(),
): List<MileageBar> {
    data class Reading(val date: LocalDate, val km: Double)

    val readings = ledger.records.mapNotNull { record ->
        val date = Dates.parse(record.date) ?: return@mapNotNull null
        val km = record.odometer ?: return@mapNotNull null
        if (!km.isFinite()) return@mapNotNull null
        Reading(date, km)
    }

    fun mileageOf(month: YearMonth): Double {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        val previous = month.minusMonths(1)
        val inPeriod = readings.filter { it.date in start..end }
        val currentMax = inPeriod.maxOfOrNull { it.km } ?: return 0.0
        val previousMax = readings
            .filter { it.date in previous.atDay(1)..previous.atEndOfMonth() }
            .maxOfOrNull { it.km }
        val baseline = previousMax ?: inPeriod.minOf { it.km }
        return (currentMax - baseline).coerceAtLeast(0.0)
    }

    val months = if (range.from.isBlank()) {
        val end = YearMonth.from(today)
        (11 downTo 0).map { end.minusMonths(it.toLong()) }
    } else {
        val start = Dates.parse(range.from)
        val end = Dates.parse(range.to)
        if (start == null || end == null) {
            val fallback = YearMonth.from(today)
            (11 downTo 0).map { fallback.minusMonths(it.toLong()) }
        } else {
            monthsCovered(YearMonth.from(start), YearMonth.from(end))
        }
    }

    return months.map { month ->
        MileageBar(
            key = monthKey(month),
            label = monthAxisLabel(month),
            km = mileageOf(month),
        )
    }
}

/** 起止月（含）覆盖的自然月；防御性上限 120 个点。 */
private fun monthsCovered(start: YearMonth, end: YearMonth): List<YearMonth> {
    if (end < start) return listOf(start)
    val months = mutableListOf<YearMonth>()
    var current = start
    while (current <= end && months.size < 120) {
        months += current
        current = current.plusMonths(1)
    }
    return months
}

/**
 * 里程趋势折线图：平滑曲线 + 面积渐变 + 数据点，数据点上方标注里程、底部月份标签。
 *
 * 点数较多（如一年 12 个月以上）时只标注首尾/末点和隔月标签，避免文字重叠。
 */
@Composable
internal fun MileageTrendChart(bars: List<MileageBar>, modifier: Modifier = Modifier) {
    if (bars.isEmpty() || bars.all { it.km <= 0.0 }) {
        EmptyChartHint(text = tr("暂无里程数据", "No mileage data yet"), modifier = modifier)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val maxKm = remember(bars) { maxOf(1.0, bars.maxOfOrNull { it.km } ?: 0.0) }
    var started by remember(bars) { mutableStateOf(false) }
    LaunchedEffect(bars) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "mileageReveal",
    )
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val dense = bars.size > 8
    val description = tr(
        "里程趋势，共 %d 个月，合计 %s %s",
        "Mileage trend, %d months, %s %s in total",
        bars.size, Format.fmt(bars.sumOf { it.km }), Units.distanceUnit,
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = description },
    ) {
        val left = 14.dp.toPx()
        val right = size.width - 14.dp.toPx()
        val top = 24.dp.toPx()
        val bottom = size.height - 26.dp.toPx()
        val usableW = (right - left).coerceAtLeast(1f)
        val usableH = (bottom - top).coerceAtLeast(1f)
        val count = bars.size

        fun xAt(index: Int): Float =
            if (count == 1) size.width / 2f else left + usableW * index / (count - 1)

        fun yAt(km: Double): Float = bottom - usableH * (km / maxKm).toFloat() * reveal

        // 基线
        drawLine(
            color = scheme.outlineVariant.copy(alpha = 0.55f),
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1.dp.toPx(),
        )

        val points = bars.mapIndexed { index, bar -> Offset(xAt(index), yAt(bar.km)) }

        if (points.size == 1) {
            val point = points.first()
            drawCircle(color = primary, radius = 4.dp.toPx())
            drawCircle(color = scheme.surface, radius = 1.8.dp.toPx())
        } else {
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (index in 0 until points.size - 1) {
                    val current = points[index]
                    val next = points[index + 1]
                    val midX = (current.x + next.x) / 2f
                    cubicTo(midX, current.y, midX, next.y, next.x, next.y)
                }
            }
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, bottom)
                lineTo(points.first().x, bottom)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(primary.copy(alpha = 0.28f), primary.copy(alpha = 0.02f)),
                    startY = top,
                    endY = bottom,
                ),
            )
            drawPath(
                path = linePath,
                color = primary,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
            // 点数多时不再逐点画圆心，避免出现孤立的“小圆圈”；数值由峰谷标签给出。
            if (!dense) {
                points.forEachIndexed { index, point ->
                    if (bars[index].km <= 0.0) return@forEachIndexed
                    drawCircle(color = primary, radius = 4.dp.toPx())
                    drawCircle(color = scheme.surface, radius = 1.8.dp.toPx())
                }
            }
        }

        // 数值标签：只标峰/谷与最后一个月，避免密集文字。
        val extremumIndices = bars.indices.filter { index ->
            val km = bars[index].km
            if (km <= 0.0) return@filter false
            val prev = bars.getOrNull(index - 1)?.km ?: km
            val next = bars.getOrNull(index + 1)?.km ?: km
            val isPeak = km >= prev && km >= next && (km > prev || km > next)
            val isTrough = km <= prev && km <= next && (km < prev || km < next)
            isPeak || isTrough
        }.toSet()
        bars.forEachIndexed { index, bar ->
            val show = bar.km > 0.0 && (index in extremumIndices || index == bars.lastIndex)
            if (show) {
                val layout = textMeasurer.measure(Format.fmt(bar.km), labelStyle)
                val x = (points[index].x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                val y = (points[index].y - layout.size.height - 6.dp.toPx()).coerceAtLeast(0f)
                drawText(textLayoutResult = layout, topLeft = Offset(x, y))
            }
        }

        // 横轴月份标签：点多时隔月显示
        bars.forEachIndexed { index, bar ->
            val show = when {
                count <= 8 -> true
                count <= 12 -> index % 2 == 0 || index == bars.lastIndex
                else -> index == 0 || index == bars.lastIndex ||
                    index % ceil(count / 6.0).toInt() == 0
            }
            if (show) {
                val layout = textMeasurer.measure(bar.label, labelStyle)
                val x = (points[index].x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, bottom + 5.dp.toPx()),
                )
            }
        }
    }
}

/** 堆叠柱中被选中的段。 */
private data class BarSelection(val monthIndex: Int, val categoryIndex: Int)

/** 环图 / 图例的选中分类名。 */
private typealias DonutSelection = String

/** 环图直径与柱图绘图区高度（设计规范 §4.2：环图 200dp、柱状 180dp；手机内卡片再收一档）。 */
private val DonutSize = 150.dp
private val BarPlotHeight = 140.dp

/** 单根柱占列宽的比例（月数 6–24 时自适应，避免固定宽度在 12/24 列时互相重叠）。 */
private const val BarWidthFraction = 0.62f
private val DonutStroke = 18.dp

/**
 * 任意月份区间的堆叠柱数据（半年 6 个 / 一年 12 个 / 自定义月份最多 24 个）。
 *
 * 每个月单独跑一次 `stats(ledger, "YYYY-MM-01", "YYYY-MM-31")`，分类堆叠顺序与
 * [MileNoteCategoryNames] 固定顺序一致，不按金额排序。
 */
internal fun buildMonthBars(
    ledger: Ledger,
    palette: List<Color>,
    darkTheme: Boolean,
    months: List<YearMonth>,
): List<MonthBar> = months.map { month ->
    val key = monthKey(month)
    val result = stats(ledger, "$key-01", "$key-31")
    MonthBar(
        key = key,
        label = monthAxisLabel(month),
        total = result.total,
        categories = result.categories
            .filter { it.value > 0.0 }
            .map { ChartCategory(it.name, it.value, chartColorOf(it.name, palette, darkTheme)) },
    )
}

/** `YearMonth` → `YYYY-MM`。 */
internal fun monthKey(month: YearMonth): String =
    "${month.year}-${month.monthValue.toString().padStart(2, '0')}"

/** 图表横轴月份标签：中文 `11 月`，英文 `Nov`。 */
private fun monthAxisLabel(month: YearMonth): String =
    tr("${month.monthValue} 月", month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))

/**
 * 分类图表色：9 类走主题固定色板；自定义分类沿用 Web 的 31 进制 hash → HSL 规则生成，
 * 保证同一分类在所有图表中颜色一致。
 */
internal fun chartColorOf(name: String, palette: List<Color>, darkTheme: Boolean): Color {
    val normalized = if (name == "维修保养") "保养" else name
    val index = MileNoteCategoryNames.indexOf(normalized)
    if (index >= 0 && index < palette.size) return palette[index]
    var hash = 0u
    for (char in name) hash = hash * 31u + char.code.toUInt()
    val hue = (hash % 360u).toFloat()
    return Color.hsl(hue, saturation = 0.42f, lightness = if (darkTheme) 0.66f else 0.48f)
}

/** 占比文案（一位小数，无 Locale 依赖）：`42.0%`、`7.5%`。 */
internal fun percentText(value: Double, total: Double): String {
    if (total <= 0.0 || !value.isFinite()) return "—"
    val percent = BigDecimal.valueOf(value / total * 100.0).setScale(1, RoundingMode.HALF_UP)
    return if (percent.stripTrailingZeros().scale() <= 0) {
        "${percent.toInt()}%"
    } else {
        "${percent.toPlainString()}%"
    }
}

/**
 * 支出趋势卡内容：按所选月份区间的分类堆叠柱。
 *
 * 点击柱段后，不显示底部整宽信息条，而是在该段旁边弹出气泡（分类 / 金额 / 占比），
 * 气泡水平位置随柱子夹取在卡片内；点击其它段切换、点空白关闭。柱段高亮保持不变。
 *
 * @param bars [buildMonthBars] 的结果。
 */
@Composable
internal fun ExpenseTrendChart(
    bars: List<MonthBar>,
    modifier: Modifier = Modifier,
    ev: Boolean = false,
) {
    if (bars.isEmpty() || bars.all { it.total <= 0.0 }) {
        EmptyChartHint(text = tr("当前范围还没有支出", "No expenses in this range"), modifier = modifier)
        return
    }
    val scheme = MaterialTheme.colorScheme
    var selection by remember(bars) { mutableStateOf<BarSelection?>(null) }
    var bubbleSize by remember(bars) { mutableStateOf(IntSize.Zero) }
    val maxTotal = remember(bars) { maxOf(1.0, bars.maxOfOrNull { it.total } ?: 0.0) }
    // 月数较多（一年 / 自定义）时隐藏柱顶总额与隔月标签，避免 12–24 列时文字互相重叠。
    val dense = bars.size > 8

    // 入场：柱从 0 生长（300ms，设计规范 §7.2）。
    var started by remember(bars) { mutableStateOf(false) }
    LaunchedEffect(bars) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trendReveal",
    )

    val trendDescription = tr(
        "支出趋势，合计 %s %s",
        "Expense trend, %s %s in total",
        Format.money(bars.sumOf { it.total }), Units.money,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = trendDescription },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val spacingPx = with(density) { 4.dp.toPx() }
        val columnWidthPx = (widthPx - spacingPx * (bars.size - 1)) / bars.size
        val rowHeightPx = with(density) { 184.dp.toPx() }
        // 行内纵向布局：总额标签 20dp + 间距 4 + 绘图区 140 + 间距 4 + 月份标签 16。
        val plotTopPx = with(density) { 24.dp.toPx() }
        val plotHeightPx = with(density) { BarPlotHeight.toPx() }

        Box(modifier = Modifier.fillMaxWidth()) {
            // 点空白关闭气泡（柱段自身的点击会优先消费事件）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(bars) {
                        detectTapGestures { selection = null }
                    },
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    bars.forEachIndexed { index, bar ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.height(20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!dense) {
                                    Text(
                                        text = if (bar.total > 0.0) Format.fmt(bar.total) else "—",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(BarPlotHeight),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                if (bar.total > 0.0) {
                                    val fraction = (bar.total / maxTotal).toFloat()
                                    val barHeight = (BarPlotHeight * fraction * reveal).coerceAtLeast(2.dp)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(BarWidthFraction)
                                            .height(barHeight)
                                            .pointerInput(bar, bars[index]) {
                                                detectTapGestures { offset ->
                                                    val categoryIndex = resolveBarSlice(
                                                        categories = bar.categories,
                                                        total = bar.total,
                                                        touchY = offset.y,
                                                        height = size.height.toFloat(),
                                                    )
                                                    selection = if (categoryIndex == null) {
                                                        null
                                                    } else {
                                                        val current = selection
                                                        if (current?.monthIndex == index &&
                                                            current.categoryIndex == categoryIndex
                                                        ) {
                                                            null
                                                        } else {
                                                            BarSelection(index, categoryIndex)
                                                        }
                                                    }
                                                }
                                            },
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            drawStackedBar(
                                                categories = bar.categories,
                                                total = bar.total,
                                                selectedIndex = selection
                                                    ?.takeIf { it.monthIndex == index }
                                                    ?.categoryIndex,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier.height(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (dense && index % 2 == 1) "" else bar.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                val legend = remember(bars) { barLegendEntries(bars) }
                if (legend.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CategoryLegend(entries = legend, ev = ev)
                }
            }

            val current = selection
            val bar = current?.let { bars.getOrNull(it.monthIndex) }
            val category = current?.let { bar?.categories?.getOrNull(it.categoryIndex) }
            if (current != null && bar != null && category != null) {
                val barHeightPx = plotHeightPx * (bar.total / maxTotal).toFloat() * reveal
                val barTopPx = plotTopPx + plotHeightPx - barHeightPx
                val before = bar.categories.take(current.categoryIndex).sumOf { it.value }
                val segmentCenterY = barTopPx +
                    ((before + category.value / 2.0) / bar.total).toFloat() * barHeightPx
                val barCenterX = current.monthIndex * (columnWidthPx + spacingPx) + columnWidthPx / 2f
                val barWidthPx = columnWidthPx * BarWidthFraction
                val gapPx = with(density) { 8.dp.toPx() }
                val bubbleW = bubbleSize.width.toFloat()
                val bubbleH = bubbleSize.height.toFloat()
                val rightX = barCenterX + barWidthPx / 2f + gapPx
                val leftX = barCenterX - barWidthPx / 2f - gapPx - bubbleW
                val rawX = if (bubbleW > 0f && rightX + bubbleW <= widthPx) rightX else leftX
                val x = rawX.coerceIn(0f, (widthPx - bubbleW).coerceAtLeast(0f))
                val y = (segmentCenterY - bubbleH / 2f)
                    .coerceIn(0f, (rowHeightPx - bubbleH).coerceAtLeast(0f))
                ChartBubble(
                    text = "${DataLabels.category(category.name, ev)} · ${Units.currencySymbol}${Format.money(category.value)}\n" +
                        percentText(category.value, bar.total),
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .onSizeChanged { bubbleSize = it },
                )
            }
        }
    }
}

/**
 * 费用构成卡内容：环图 + 图例。
 *
 * 点击扇区或图例后，在对应扇区旁边（按扇区中心角计算）弹出气泡（分类 / 金额 / 占比），
 * 不再使用底部整宽信息条；再次点击取消、点空白关闭。
 *
 * @param categories 当前期间的分类聚合（含 0 值分类）。
 * @param total 当前期间支出合计。
 */
@Composable
internal fun CategoryBreakdown(
    categories: List<CategoryValue>,
    total: Double,
    modifier: Modifier = Modifier,
    ev: Boolean = false,
) {
    val palette = LocalMileNoteCategoryColors.current
    val darkTheme = LocalMileNoteDarkTheme.current
    val slices = remember(categories, palette, darkTheme) {
        categories
            .filter { it.value > 0.0 }
            .map { ChartCategory(it.name, it.value, chartColorOf(it.name, palette, darkTheme)) }
    }
    if (slices.isEmpty() || total <= 0.0) {
        EmptyChartHint(text = tr("等待你的第一笔记录", "Waiting for your first entry"), modifier = modifier)
        return
    }
    var selected by remember(slices) { mutableStateOf<DonutSelection?>(null) }
    var bubbleSize by remember(slices) { mutableStateOf(IntSize.Zero) }

    val breakdownDescription = tr(
        "费用构成，共 %d 个分类，合计 %s %s",
        "Expense breakdown, %d categories, %s %s in total",
        slices.size, Format.money(total), Units.money,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = breakdownDescription },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val donutPx = with(density) { DonutSize.toPx() }
        val toggle: (String) -> Unit = { name -> selected = if (selected == name) null else name }

        Box(modifier = Modifier.fillMaxWidth()) {
            // 点空白关闭气泡。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(slices) {
                        detectTapGestures { selected = null }
                    },
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DonutChart(
                    slices = slices,
                    total = total,
                    selected = selected,
                    onSelect = toggle,
                    modifier = Modifier.size(DonutSize),
                )
                Spacer(modifier = Modifier.height(14.dp))
                // 分类列表在环图下方横排：色点 + 分类名；点击图例同样弹出气泡。
                CategoryLegend(
                    entries = slices.map { it.name to it.color },
                    modifier = Modifier.fillMaxWidth(),
                    selected = selected,
                    onSelect = toggle,
                    ev = ev,
                )
            }

            val current = selected
            val index = current?.let { name -> slices.indexOfFirst { it.name == name } } ?: -1
            if (current != null && index >= 0) {
                val slice = slices[index]
                val startFraction = slices.take(index).sumOf { it.value } / total
                val midAngle = -90.0 + (startFraction + slice.value / total / 2.0) * 360.0
                val radians = Math.toRadians(midAngle)
                val radiusPx = donutPx / 2f + with(density) { 10.dp.toPx() }
                val pointX = widthPx / 2f + cos(radians).toFloat() * radiusPx
                val pointY = donutPx / 2f + sin(radians).toFloat() * radiusPx
                val gapPx = with(density) { 8.dp.toPx() }
                val bubbleW = bubbleSize.width.toFloat()
                val bubbleH = bubbleSize.height.toFloat()
                val rawX = if (cos(radians) >= 0) pointX + gapPx else pointX - gapPx - bubbleW
                val x = rawX.coerceIn(0f, (widthPx - bubbleW).coerceAtLeast(0f))
                val y = (pointY - bubbleH / 2f).coerceIn(0f, (donutPx - bubbleH).coerceAtLeast(0f))
                ChartBubble(
                    text = "${DataLabels.category(slice.name, ev)} · ${Units.currencySymbol}${Format.money(slice.value)}\n" +
                        percentText(slice.value, total),
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .onSizeChanged { bubbleSize = it },
                )
            }
        }
    }
}

/** 自绘环图：`sweep` 从 0 生长，选中扇区加粗、其余降到 50% 不透明度。 */
@Composable
private fun DonutChart(
    slices: List<ChartCategory>,
    total: Double,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var started by remember(slices) { mutableStateOf(false) }
    LaunchedEffect(slices) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "donutReveal",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, total) {
                    detectTapGestures { offset ->
                        val hit = resolveDonutSlice(
                            slices = slices,
                            total = total,
                            center = Offset(size.width / 2f, size.height / 2f),
                            touch = offset,
                            outerRadius = minOf(size.width, size.height) / 2f,
                            strokeWidth = DonutStroke.toPx(),
                        )
                        if (hit != null) onSelect(hit)
                    }
                },
        ) {
            val stroke = DonutStroke.toPx()
            val radius = (size.minDimension - stroke) / 2f
            drawCircle(
                color = scheme.surfaceContainerHigh,
                radius = radius,
                style = Stroke(width = stroke),
            )
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = (slice.value / total * 360.0).toFloat() * reveal
                val isSelected = selected == slice.name
                drawArc(
                    color = slice.color.copy(alpha = if (selected == null || isSelected) 1f else 0.5f),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(
                        width = if (isSelected) stroke + 3.dp.toPx() else stroke,
                        cap = StrokeCap.Butt,
                    ),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = tr("总支出", "Total"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = "${Units.currencySymbol}${Format.money(total)}",
                style = MaterialTheme.typography.moneySmall,
                color = scheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = tr("%d 个分类", "%d categories", slices.size),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 分类图例（横排，色点 + 分类名）；可选点击选中（费用构成联动气泡）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onSelect: ((String) -> Unit)? = null,
    ev: Boolean = false,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { (name, color) ->
            val isSelected = selected == name
            val rowModifier = if (onSelect != null) {
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onSelect(name) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            } else {
                Modifier
            }
            Row(
                modifier = rowModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color, CircleShape),
                )
                Text(
                    text = DataLabels.category(name, ev),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 图表气泡提示：surfaceContainerHigh 底、medium 圆角、1dp 轻阴影、bodySmall、最多两行。
 *
 * 由调用方用 `offset` 定位到柱段 / 扇区旁边，并夹取在卡片范围内。
 */
@Composable
private fun ChartBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.shadow(elevation = 1.dp, shape = MileNoteShapes.medium),
        shape = MileNoteShapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .widthIn(max = 200.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 图表空态占位（保持卡片有固定视觉高度，避免切换期间跳动）。 */
@Composable
private fun EmptyChartHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 柱图图例：近 6 个月出现过的分类，按 9 类固定顺序排列。 */
private fun barLegendEntries(bars: List<MonthBar>): List<Pair<String, Color>> {
    val present = linkedMapOf<String, Color>()
    bars.forEach { bar ->
        bar.categories.forEach { category -> present.putIfAbsent(category.name, category.color) }
    }
    return present.entries.sortedBy { (name, _) ->
        val index = MileNoteCategoryNames.indexOf(if (name == "维修保养") "保养" else name)
        if (index >= 0) index else MileNoteCategoryNames.size
    }.map { it.toPair() }
}

/**
 * 堆叠柱自绘：顶部 6dp 圆角，每段用分类色做纵向渐变（0.95 → 0.65），
 * 选中段保持原亮度、其余降透明度（设计规范 §2.7 选中态）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStackedBar(
    categories: List<ChartCategory>,
    total: Double,
    selectedIndex: Int?,
) {
    if (total <= 0.0) return
    val radius = 6.dp.toPx()
    val clip = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(0f, 0f, size.width, size.height),
                topLeft = CornerRadius(radius, radius),
                topRight = CornerRadius(radius, radius),
                bottomRight = CornerRadius.Zero,
                bottomLeft = CornerRadius.Zero,
            ),
        )
    }
    clipPath(clip) {
        // 与 Web 一致：第一个分类在最上方，后续向下堆叠。
        var top = 0f
        categories.forEachIndexed { index, category ->
            val height = (category.value / total * size.height).toFloat()
            val dimmed = selectedIndex != null && selectedIndex != index
            val startAlpha = if (dimmed) 0.5f else 0.95f
            val endAlpha = if (dimmed) 0.35f else 0.65f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        category.color.copy(alpha = startAlpha),
                        category.color.copy(alpha = endAlpha),
                    ),
                    startY = top,
                    endY = top + height,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, height),
            )
            top += height
        }
    }
}

/** 由触点纵坐标反查堆叠段（自底向上累计）。 */
private fun resolveBarSlice(
    categories: List<ChartCategory>,
    total: Double,
    touchY: Float,
    height: Float,
): Int? {
    if (total <= 0.0 || height <= 0f) return null
    var fromTop = touchY
    categories.forEachIndexed { index, category ->
        val segmentHeight = (category.value / total).toFloat() * height
        if (fromTop <= segmentHeight) return index
        fromTop -= segmentHeight
    }
    return categories.lastIndex.takeIf { it >= 0 }
}

/** 由触点反查环图扇区（角度区间 + 环形半径校验）。 */
private fun resolveDonutSlice(
    slices: List<ChartCategory>,
    total: Double,
    center: Offset,
    touch: Offset,
    outerRadius: Float,
    strokeWidth: Float,
): String? {
    if (total <= 0.0 || slices.isEmpty()) return null
    val dx = touch.x - center.x
    val dy = touch.y - center.y
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    val innerRadius = outerRadius - strokeWidth
    if (distance < innerRadius || distance > outerRadius) return null
    val raw = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 90.0
    val angle = ((raw % 360.0) + 360.0) % 360.0
    var cursor = 0.0
    slices.forEach { slice ->
        val sweep = slice.value / total * 360.0
        if (angle >= cursor && angle < cursor + sweep) return slice.name
        cursor += sweep
    }
    return slices.lastOrNull()?.name
}
