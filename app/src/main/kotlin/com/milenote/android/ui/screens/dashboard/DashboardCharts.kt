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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
import com.milenote.android.ui.theme.moneyLarge
import com.milenote.android.ui.theme.moneySmall
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 总览页图表（自绘 Canvas，不引入第三方图表库）。
 *
 * 设计规范 §2.7 / §6.2：
 * - 支出趋势：按趋势卡统一月份列表绘制分类堆叠柱，点击柱段显示
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
 * 趋势卡图表窗口（里程折线 / 支出堆叠柱共用同一批月份，切 tab 时横轴不跳动）。
 *
 * [months] 是实际绘制的月份，[totalMonths] 是范围覆盖的自然月总数；两者不等时
 * 卡头显示「近 N / M 个月」，不再用「全部」偷换语义。
 */
internal data class TrendWindow(
    val months: List<YearMonth>,
    val totalMonths: Int,
) {
    val truncated: Boolean get() = totalMonths > months.size
}

/**
 * 按筛选范围计算图表窗口：
 * - 「全部」→ 从最早一条记录所在月到本月；
 * - 本月 / 上月 / 近 3 个月 / 今年 / 自定义 → 范围覆盖的自然月；
 * - 超过 [maxMonths]（默认 24）时取**最近** 24 个月，而不是最早 24 个月。
 *
 * 无记录且无下界时退回最近 [maxMonths] 个月，保证空图表也有横轴。
 */
internal fun trendMonthWindow(
    ledger: Ledger,
    range: DateRange,
    today: LocalDate = Dates.today(),
    maxMonths: Int = 24,
): TrendWindow {
    // 「全部」的 range.to 是 9999-12-31（表示无上界），图表窗口必须收在今天，而不是画到 9999 年。
    val end = if (range.from.isEmpty()) today else Dates.parse(range.to) ?: today
    val endMonth = YearMonth.from(end)
    val start = Dates.parse(range.from)
    if (start != null) {
        return monthWindowBetween(YearMonth.from(start), endMonth, maxMonths)
    }
    val earliest = ledger.records.mapNotNull { Dates.parse(it.date) }.minOrNull()
    if (earliest != null) {
        return monthWindowBetween(YearMonth.from(earliest), endMonth, maxMonths)
    }
    return monthWindowBetween(endMonth.minusMonths((maxMonths - 1).toLong()), endMonth, maxMonths)
}

/**
 * 从 [start] 到 [end] 的月份窗口，只保留最近 [maxMonths] 个月。
 *
 * 用月份差算总数，不把 10 年以上的月份全物化；这样自定义长区间取到的始终是**最近** 24 个月。
 */
private fun monthWindowBetween(
    start: YearMonth,
    end: YearMonth,
    maxMonths: Int,
): TrendWindow {
    if (end < start) return TrendWindow(months = listOf(start), totalMonths = 1)
    val total = ChronoUnit.MONTHS.between(start, end).toInt() + 1
    val shown = minOf(total, maxMonths)
    val months = (shown - 1 downTo 0).map { end.minusMonths(it.toLong()) }
    return TrendWindow(months = months, totalMonths = total)
}

/**
 * 画布窗口：在选中范围之外再带一段上下文月份，让「本月 / 上月 / 自定义单月」
 * 不再只剩一个孤点或孤柱。
 *
 * [months] 是实际绘制的月份，[focus] 是其中属于选中范围的月份；两者不等时
 * 图表把上下文画淡、选中月保持满色，卡头说明行给出「附前 N 个月对比」。
 */
internal data class TrendDisplay(
    val months: List<YearMonth>,
    val focus: Set<YearMonth>,
)

/**
 * 单月范围自动向前补足上下文（默认总共 [contextMonths] 个月），但**不早于账本
 * 最早记录所在月**：新用户只有本月数据时仍只画这一个月，由调用方退回单月摘要。
 *
 * 多个月的范围原样返回（focus = months），不改变「近 3 个月 / 今年 / 全部」的窗口语义。
 */
internal fun trendDisplayWindow(
    ledger: Ledger,
    range: DateRange,
    today: LocalDate = Dates.today(),
    contextMonths: Int = 6,
): TrendDisplay {
    val window = trendMonthWindow(ledger, range, today)
    if (window.months.size != 1) {
        return TrendDisplay(months = window.months, focus = window.months.toSet())
    }
    val only = window.months.single()
    val earliestMonth = ledger.records
        .mapNotNull { Dates.parse(it.date) }
        .minOrNull()
        ?.let { YearMonth.from(it) }
    val desiredStart = only.minusMonths((contextMonths - 1).coerceAtLeast(0).toLong())
    val start = if (earliestMonth != null && earliestMonth > desiredStart) earliestMonth else desiredStart
    if (start >= only) {
        return TrendDisplay(months = listOf(only), focus = setOf(only))
    }
    val count = ChronoUnit.MONTHS.between(start, only).toInt()
    val months = (count downTo 0).map { only.minusMonths(it.toLong()) }
    return TrendDisplay(months = months, focus = setOf(only))
}

/**
 * 里程趋势数据：按 [months] 逐月计算每月行驶里程。
 *
 * 月里程 = 该月最大 odometer − 上月最大 odometer；上月无读数时用「该月 max − min」；
 * 无有效里程的点置 0（整段都无数据时由图表显示空态）。
 */
internal fun buildMileageBars(
    ledger: Ledger,
    months: List<YearMonth>,
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

    return months.map { month ->
        MileageBar(
            key = monthKey(month),
            label = monthAxisLabel(month),
            km = mileageOf(month),
        )
    }
}

/**
 * 里程趋势折线图：平滑曲线 + 面积渐变，峰/谷与最后一个月上方标注里程、底部月份标签。
 *
 * 曲线**不画逐点圆圈**（2026-11-13 用户反馈短区间「里程中间有小圆点」）；只有 1 个月时
 * 用一小段横线标记这个值。点数较多（如一年 12 个月以上）时只标注峰/谷与隔月标签，避免文字重叠。
 */
@Composable
internal fun MileageTrendChart(
    bars: List<MileageBar>,
    modifier: Modifier = Modifier,
    focusKeys: Set<String>? = null,
) {
    if (bars.isEmpty() || bars.all { it.km <= 0.0 }) {
        EmptyChartHint(
            text = tr("暂无里程数据", "No mileage data yet"),
            modifier = modifier,
            height = TrendChartBlockHeight,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val focused = focusKeys ?: bars.map { it.key }.toSet()
    val hasContext = bars.any { it.key !in focused }
    val focusKm = bars.filter { it.key in focused }.sumOf { it.km }
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
    val focusSummary = bars.filter { it.key in focused }
        .joinToString(tr("，", ", ")) { "${it.label} ${Format.fmt(it.km)}" }
    val description = if (hasContext) {
        if (focusKm <= 0.0) {
            tr(
                "里程趋势，近 %d 个月，选中月暂无里程记录",
                "Mileage trend, last %d months, no mileage recorded in the selected month",
                bars.size,
            )
        } else {
            tr(
                "里程趋势，近 %d 个月，其中 %s %s",
                "Mileage trend, last %d months, %s %s highlighted",
                bars.size, focusSummary, Units.distanceUnit,
            )
        }
    } else {
        tr(
            "里程趋势，共 %d 个月，合计 %s %s",
            "Mileage trend, %d months, %s %s in total",
            bars.size, Format.fmt(bars.sumOf { it.km }), Units.distanceUnit,
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartBlockHeight)
            .semantics { contentDescription = description },
    ) {
        val left = 14.dp.toPx()
        val right = size.width - 14.dp.toPx()
        val top = 24.dp.toPx()
        val axisRow = TrendAxisRowHeight.toPx()
        // 基线抬高到月份轴上方，轴行与支出图共用同一段高度。
        val bottom = size.height - axisRow - TrendAxisGap.toPx()
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

        // 单月窗口没有可比对象，纵轴归一化没意义：唯一的数据点固定在绘图区垂直中间偏下，
        // 用一小段横线标记（不是圆点），数值标签在它上方。
        val singlePoint = bars.size == 1
        val points = bars.mapIndexed { index, bar ->
            if (singlePoint) {
                Offset(size.width / 2f, top + usableH * 0.55f)
            } else {
                Offset(xAt(index), yAt(bar.km))
            }
        }

        // 有上下文时选中月只靠主色 / 加粗轴标签区分，不再垫整列色块：
        // 6% 的主色在深色底上会变成一块「幽灵柱」，比柱子本身还显眼。

        // 折线 / 面积始终只画曲线，不画逐点圆圈：
        // 原先「只有 1 个月」画一颗空心圆点、「≤8 个月」逐点画空心圆点 —— 短区间（上月 / 近 3 个月）
        // 切到里程 tab 时，空白里会浮出几颗小圆圈（2026-11-13 用户反馈「里程有时候中间会有小圆点」）。
        val focusHasValue = bars.any { it.key in focused && it.km > 0.0 }
        val missingFocus = hasContext && !focusHasValue && bars.lastOrNull()?.key in focused
        if (singlePoint) {
            val point = points.first()
            val half = (right - left) * 0.16f
            drawLine(
                color = primary,
                start = Offset(point.x - half, point.y),
                end = Offset(point.x + half, point.y),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        } else if (hasContext) {
            // 上下文月：整条线淡成 onSurfaceVariant；只有选中月那一段用主色。
            // 选中月没有里程读数时不要连到 0（那会像「这个月没开车」）：线停在最后一个有读数的月，
            // 选中月的轴标签下方单独画一个「—」。
            val linePoints = if (missingFocus) points.dropLast(1) else points
            if (linePoints.size >= 2) {
                val contextPath = Path().apply {
                    moveTo(linePoints.first().x, linePoints.first().y)
                    for (index in 0 until linePoints.size - 1) {
                        val current = linePoints[index]
                        val next = linePoints[index + 1]
                        val midX = (current.x + next.x) / 2f
                        cubicTo(midX, current.y, midX, next.y, next.x, next.y)
                    }
                }
                drawPath(
                    path = contextPath,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.48f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            if (focusHasValue) {
                for (index in 0 until count - 1) {
                    if (bars[index].key !in focused && bars[index + 1].key !in focused) continue
                    val current = points[index]
                    val next = points[index + 1]
                    val midX = (current.x + next.x) / 2f
                    val segment = Path().apply {
                        moveTo(current.x, current.y)
                        cubicTo(midX, current.y, midX, next.y, next.x, next.y)
                    }
                    drawPath(
                        path = segment,
                        color = primary,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
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
            val show = bar.km > 0.0 && if (hasContext) {
                bar.key in focused
            } else {
                index in extremumIndices || index == bars.lastIndex
            }
            if (show) {
                val layout = textMeasurer.measure(Format.fmt(bar.km), labelStyle)
                val x = (points[index].x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                val y = (points[index].y - layout.size.height - 6.dp.toPx()).coerceAtLeast(0f)
                drawText(textLayoutResult = layout, topLeft = Offset(x, y))
            } else if (hasContext && bar.key in focused) {
                // 选中月没有里程读数：在基线附近画「—」，避免整张图看起来断了。
                val layout = textMeasurer.measure("—", labelStyle)
                val x = (points[index].x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                val y = (bottom - layout.size.height - 6.dp.toPx()).coerceAtLeast(0f)
                drawText(textLayoutResult = layout, topLeft = Offset(x, y))
            }
        }

        // 横轴月份标签：点多时隔月显示；有上下文时选中月加重。
        bars.forEachIndexed { index, bar ->
            val show = when {
                count <= 8 -> true
                count <= 12 -> index % 2 == 0 || index == bars.lastIndex
                else -> index == 0 || index == bars.lastIndex ||
                    index % ceil(count / 6.0).toInt() == 0
            }
            if (show) {
                val style = if (hasContext && bar.key in focused) {
                    labelStyle.copy(color = primary, fontWeight = FontWeight.SemiBold)
                } else {
                    labelStyle
                }
                val layout = textMeasurer.measure(bar.label, style)
                val x = (points[index].x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                val labelTop = size.height - axisRow + (axisRow - layout.size.height) / 2f
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, labelTop),
                )
            }
        }
    }
}

/** 堆叠柱中被选中的段。 */
private data class BarSelection(val monthIndex: Int, val categoryIndex: Int)

/** 环图 / 图例的选中分类名。 */
private typealias DonutSelection = String

/** 环图直径（设计规范 §4.2：环图 200dp；手机内卡片再收一档）。 */
private val DonutSize = 150.dp

/**
 * 总览「趋势」卡「图表区」高度：绘图区 + 底部月份轴。里程 / 支出两张图共用。
 *
 * 横轴对齐依赖这里：两张图都把月份标签放在本区块最后 [TrendAxisRowHeight] 里，
 * 轴下不放任何内容，所以切 tab 时横轴、卡高都不动。
 *
 * 2026-11-13 起支出的「分类」视图也钉在这个高度里（合计行 + 最多 7 行排行），
 * 「月度 / 分类」来回切换时卡片高度不变。
 */
private val TrendChartBlockHeight = 210.dp

/** 图表区底部月份轴行高：两张图都把标签居中画在这一行。 */
private val TrendAxisRowHeight = 20.dp

/** 轴行与绘图区之间的呼吸位。 */
private val TrendAxisGap = 4.dp

/** 分类视图：最多显示几行，其余折成「其他 N 类」。 */
private const val CategoryRowLimit = 7

/** 分类排行单行高度：合计行 20dp + 7 行 × 27dp = 209dp，正好铺满 [TrendChartBlockHeight]。 */
private val CategoryRowHeight = 27.dp

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
internal fun monthAxisLabel(month: YearMonth): String =
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
 * 支出趋势卡「月度」视图：按所选月份区间的分类堆叠柱。
 *
 * 点击柱段后，不显示底部整宽信息条，而是在该段旁边弹出气泡（分类 / 金额 / 占比），
 * 气泡水平位置随柱子夹取在卡片内；点击其它段切换、点空白关闭。柱段高亮保持不变。
 *
 * @param bars [buildMonthBars] 的结果。
 * @param highlight 从「分类」视图点选回来的分类名：只保留该分类的色块，其余降到 22%；
 *   null 表示不高亮。点空白会通过 [onClearHighlight] 清掉。
 */
@Composable
internal fun ExpenseTrendChart(
    bars: List<MonthBar>,
    modifier: Modifier = Modifier,
    ev: Boolean = false,
    highlight: String? = null,
    onClearHighlight: () -> Unit = {},
    focusKeys: Set<String>? = null,
) {
    if (bars.isEmpty() || bars.all { it.total <= 0.0 }) {
        EmptyChartHint(
            text = tr("当前范围还没有支出", "No expenses in this range"),
            modifier = modifier,
            height = TrendChartBlockHeight,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val focused = focusKeys ?: bars.map { it.key }.toSet()
    val hasContext = bars.any { it.key !in focused }
    var selection by remember(bars) { mutableStateOf<BarSelection?>(null) }
    var bubbleSize by remember(bars) { mutableStateOf(IntSize.Zero) }
    val maxTotal = remember(bars) { maxOf(1.0, bars.maxOfOrNull { it.total } ?: 0.0) }
    // 月数较多（一年 / 自定义）时隐藏柱顶总额，避免 12–24 列时文字互相重叠。
    val dense = bars.size > 8
    val axisTextMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    // 本月 / 近两个月只有 1–2 根柱，按 0.62 列宽会变成巨柱，收窄到 0.28 列宽。
    val barFill = if (bars.size <= 2) 0.28f else BarWidthFraction

    // 入场：柱从 0 生长（300ms，设计规范 §7.2）。
    var started by remember(bars) { mutableStateOf(false) }
    LaunchedEffect(bars) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trendReveal",
    )

    val focusTotal = bars.filter { it.key in focused }.sumOf { it.total }
    val trendDescription = if (hasContext) {
        tr(
            "支出趋势，近 %d 个月，选中范围合计 %s %s",
            "Expense trend, last %d months, %s %s in the selected range",
            bars.size, Format.money(focusTotal), Units.money,
        )
    } else {
        tr(
            "支出趋势，合计 %s %s",
            "Expense trend, %s %s in total",
            Format.money(focusTotal), Units.money,
        )
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartBlockHeight)
            .semantics { contentDescription = trendDescription },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val spacingPx = with(density) { 4.dp.toPx() }
        val columnWidthPx = (widthPx - spacingPx * (bars.size - 1)) / bars.size
        // 绘图行高度 = 图表区高度（轴下信息区已挪到卡片层面）。
        var plotRowHeightPx by remember(bars) { mutableStateOf(0f) }
        // 行内纵向布局：总额标签 20dp + 间距 4 + 绘图区（weight）+ 间距 4 + 月份轴 20dp。
        val plotTopPx = with(density) { 24.dp.toPx() }

        Box(modifier = Modifier.fillMaxSize()) {
            // 点空白关闭气泡（柱段自身的点击会优先消费事件）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(bars, highlight) {
                        detectTapGestures {
                            selection = null
                            onClearHighlight()
                        }
                    },
            )
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onSizeChanged { plotRowHeightPx = it.height.toFloat() },
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
                                if (!dense && (!hasContext || bar.key in focused)) {
                                    Text(
                                        text = if (bar.total > 0.0) Format.fmt(bar.total) else "—",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasContext && bar.key in focused) {
                                            scheme.onSurface
                                        } else {
                                            scheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                if (bar.total > 0.0) {
                                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                        val fraction = (bar.total / maxTotal).toFloat()
                                        val barHeight = (maxHeight * fraction * reveal).coerceAtLeast(2.dp)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth(barFill)
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
                                                    highlight = highlight?.takeIf { bar.key in focused },
                                                    muted = hasContext && bar.key !in focused,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 月份轴单独一层 Canvas：24 个月时按列放标签只能挤成单字母，这里按整行宽度定位并抽稀。
                Spacer(modifier = Modifier.height(TrendAxisGap))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TrendAxisRowHeight),
                ) {
                    val count = bars.size
                    if (count > 0) {
                        val step = maxOf(1, (count + 5) / 6)
                        val cell = size.width / count
                        val indices = (0 until count step step).toMutableSet()
                        indices.add(count - 1)
                        var lastRight = -Float.MAX_VALUE
                        indices.sorted().forEach { monthIndex ->
                            val style = if (hasContext && bars[monthIndex].key in focused) {
                                axisStyle.copy(color = scheme.primary, fontWeight = FontWeight.SemiBold)
                            } else {
                                axisStyle
                            }
                            val layout = axisTextMeasurer.measure(bars[monthIndex].label, style)
                            val x = ((monthIndex + 0.5f) * cell - layout.size.width / 2f)
                                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                            if (x < lastRight + 2.dp.toPx()) return@forEach
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(x, (size.height - layout.size.height) / 2f),
                            )
                            lastRight = x + layout.size.width
                        }
                    }
                }
            }

            val current = selection
            val bar = current?.let { bars.getOrNull(it.monthIndex) }
            val category = current?.let { bar?.categories?.getOrNull(it.categoryIndex) }
            if (current != null && bar != null && category != null) {
                val plotHeightPx = (plotRowHeightPx - with(density) { 24.dp.toPx() }).coerceAtLeast(1f)
                val barHeightPx = plotHeightPx * (bar.total / maxTotal).toFloat() * reveal
                val barTopPx = plotTopPx + plotHeightPx - barHeightPx
                val before = bar.categories.take(current.categoryIndex).sumOf { it.value }
                val segmentCenterY = barTopPx +
                    ((before + category.value / 2.0) / bar.total).toFloat() * barHeightPx
                val barCenterX = current.monthIndex * (columnWidthPx + spacingPx) + columnWidthPx / 2f
                val barWidthPx = columnWidthPx * barFill
                val gapPx = with(density) { 8.dp.toPx() }
                val bubbleW = bubbleSize.width.toFloat()
                val bubbleH = bubbleSize.height.toFloat()
                val rightX = barCenterX + barWidthPx / 2f + gapPx
                val leftX = barCenterX - barWidthPx / 2f - gapPx - bubbleW
                val rawX = if (bubbleW > 0f && rightX + bubbleW <= widthPx) rightX else leftX
                val x = rawX.coerceIn(0f, (widthPx - bubbleW).coerceAtLeast(0f))
                val y = (segmentCenterY - bubbleH / 2f)
                    .coerceIn(0f, (plotRowHeightPx - bubbleH).coerceAtLeast(0f))
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
 * 「分类」视图的聚合：把趋势窗口里各月的分类段按名称合并，金额从大到小。
 *
 * 金额相同的分类按 9 类固定顺序（自定义分类排在最后、再按名称）稳定排序，
 * 避免每次重组都换位置。金额为 0 的分类不出现（与堆叠柱的口径一致）。
 */
internal fun categoryTotalsOf(bars: List<MonthBar>): List<CategoryValue> {
    val totals = linkedMapOf<String, Double>()
    bars.forEach { bar ->
        bar.categories.forEach { category ->
            if (category.value > 0.0) {
                totals[category.name] = (totals[category.name] ?: 0.0) + category.value
            }
        }
    }
    if (totals.isEmpty()) return emptyList()
    val order: (String) -> Int = { name ->
        val normalized = if (name == "维修保养") "保养" else name
        MileNoteCategoryNames.indexOf(normalized).takeIf { it >= 0 } ?: MileNoteCategoryNames.size
    }
    return totals.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
            .thenBy { order(it.key) }
            .thenBy { it.key })
        .map { CategoryValue(it.key, it.value) }
}

/**
 * 支出趋势卡「分类」视图：当前范围按分类聚合的排行（色点 + 名称 + 占比条 + 金额 + 百分比）。
 *
 * 与「月度」视图共用 [TrendChartBlockHeight]：合计行 20dp + 最多 [CategoryRowLimit] 行 30dp，
 * 所以卡内切换视图时卡片高度不变。最多 6 类 + 「其他 N 类」，避免自定义分类把列表撑破；
 * 点某一行交给调用方切回月度并高亮该类柱段。
 *
 * @param bars [buildMonthBars] 的结果。
 * @param onPick 点分类行（「其他」行不可点）。
 */
@Composable
internal fun ExpenseCategoryBreakdown(
    bars: List<MonthBar>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    ev: Boolean = false,
) {
    val totals = remember(bars) { categoryTotalsOf(bars) }
    val total = remember(totals) { totals.sumOf { it.value } }
    if (totals.isEmpty() || total <= 0.0) {
        EmptyChartHint(
            text = tr("当前范围还没有支出", "No expenses in this range"),
            modifier = modifier,
            height = TrendChartBlockHeight,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val palette = LocalMileNoteCategoryColors.current
    val darkTheme = LocalMileNoteDarkTheme.current
    val maxValue = totals.first().value
    val shown = if (totals.size <= CategoryRowLimit) totals else totals.take(CategoryRowLimit - 1)
    val otherCount = totals.size - shown.size
    val otherValue = totals.drop(shown.size).sumOf { it.value }
    val summary = tr(
        "支出分类，合计 %s %s，共 %d 类",
        "Expenses by category, %s %s in %d categories",
        Format.money(total), Units.money, totals.size,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartBlockHeight)
            .semantics { contentDescription = summary },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("合计", "Total"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${Units.currencySymbol}${Format.money(total)}",
                style = MaterialTheme.typography.moneySmall,
                color = scheme.onSurface,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = tr("%d 类", "%d categories", totals.size),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        shown.forEach { item ->
            CategoryRankRow(
                label = DataLabels.category(item.name, ev),
                value = item.value,
                total = total,
                maxValue = maxValue,
                color = chartColorOf(item.name, palette, darkTheme),
                onClick = { onPick(item.name) },
            )
        }
        if (otherCount > 0) {
            CategoryRankRow(
                label = tr("其他 %d 类", "Other %d", otherCount),
                value = otherValue,
                total = total,
                maxValue = maxValue,
                color = scheme.onSurfaceVariant,
                onClick = null,
            )
        }
    }
}

/**
 * 「分类」视图的一行：色点 + 名称 + 占比条 + 金额 + 百分比。
 *
 * [onClick] 为 null 时不可点（「其他 N 类」）。整行合成一个无障碍节点，
 * 读屏读「油费，8,830 元，占 65.2%」，不用逐个数字去听。
 */
@Composable
private fun CategoryRankRow(
    label: String,
    value: Double,
    total: Double,
    maxValue: Double,
    color: Color,
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val percent = percentText(value, total)
    val amount = "${Units.currencySymbol}${Format.money(value)}"
    val description = tr(
        "%s，%s %s，占 %s",
        "%s, %s %s, %s",
        label, Format.money(value), Units.money, percent,
    )
    val clickable = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CategoryRowHeight)
            .then(clickable)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.1f),
        )
        Box(
            modifier = Modifier
                .weight(0.9f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(scheme.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((value / maxValue).toFloat().coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            text = amount,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = percent,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
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

/**
 * 单月且没有历史可比时（新账本只有一个月记录）的摘要版式。
 *
 * 不再把唯一值画成孤点 / 孤柱；高度仍钉在 [TrendChartBlockHeight]，
 * 与图表、分类视图来回切换时卡片高度不变。
 *
 * @param stats label → value 的辅助指标，最多 2–3 条。
 */
@Composable
internal fun SingleMonthTrendSummary(
    title: String,
    value: String,
    unit: String?,
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val description = buildString {
        append(title)
        append(' ')
        append(value)
        if (unit != null) {
            append(' ')
            append(unit)
        }
        stats.forEach { (label, statValue) ->
            append(' ')
            append(label)
            append(' ')
            append(statValue)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TrendChartBlockHeight)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.moneyLarge,
                color = scheme.onSurface,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stats.forEach { (label, statValue) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = statValue,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 图表空态占位（保持卡片有固定视觉高度，避免切换期间跳动）。 */
@Composable
private fun EmptyChartHint(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 堆叠柱自绘：顶部 6dp 圆角，每段用分类色做纵向渐变（0.95 → 0.65），
 * 选中段保持原亮度、其余降透明度（设计规范 §2.7 选中态）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStackedBar(
    categories: List<ChartCategory>,
    total: Double,
    selectedIndex: Int?,
    highlight: String? = null,
    muted: Boolean = false,
) {
    if (total <= 0.0) return
    val radius = 6.dp.toPx()
    val mute = if (muted) 0.45f else 1f
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
            // 分类高亮优先：不匹配的分类整个压到 22%；否则只压暗非选中段（点柱气泡态）。
            val mismatch = highlight != null && category.name != highlight
            val dimmed = selectedIndex != null && selectedIndex != index
            val startAlpha = when {
                mismatch -> 0.22f
                dimmed -> 0.5f
                else -> 0.95f
            } * mute
            val endAlpha = when {
                mismatch -> 0.15f
                dimmed -> 0.35f
                else -> 0.65f
            } * mute
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
