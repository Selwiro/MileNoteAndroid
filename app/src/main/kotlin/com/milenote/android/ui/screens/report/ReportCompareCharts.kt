package com.milenote.android.ui.screens.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 用车报告里的对比图表（自绘，不引第三方库）。
 *
 * 三类图共用一套语义：**本期实色、上期半透明主色、同比中性色**；数值永远标在条旁边，
 * 图表只负责「量级与方向」，不靠颜色单独表意（色觉缺陷也能读）。
 */

/** 对比图的颜色：本期实、上期淡、同比中性。 */
internal data class CompareColors(val current: Color, val previous: Color, val yearAgo: Color)

@Composable
internal fun reportCompareColors(): CompareColors {
    val scheme = MaterialTheme.colorScheme
    return CompareColors(
        current = scheme.primary,
        previous = scheme.primary.copy(alpha = 0.5f),
        yearAgo = scheme.onSurfaceVariant.copy(alpha = 0.45f),
    )
}

/**
 * 一条横条的占比：0..1；[max] 为 0 / 非法 / 负数时返回 0（画空轨道）。
 *
 * 报告页的支出对比与油耗对比都用它，单测直接覆盖边界。
 */
internal fun barFraction(value: Double, max: Double): Float {
    if (!value.isFinite() || !max.isFinite() || max <= 0.0 || value <= 0.0) return 0f
    return (value / max).toFloat().coerceIn(0f, 1f)
}

/** 增减条的幅度：|delta| / maxAbs，0..1；delta 或 maxAbs 缺失时为 0。 */
internal fun divergingFraction(delta: Double?, maxAbs: Double): Float {
    if (delta == null || !delta.isFinite() || !maxAbs.isFinite() || maxAbs <= 0.0) return 0f
    return (abs(delta) / maxAbs).toFloat().coerceIn(0f, 1f)
}

/** 一组对比条（支出、油耗这类单一指标）。 */
internal data class CompareBar(val label: String, val value: Double, val color: Color)

/**
 * 横向对比条：每行一个系列（本期 / 上期 / 同比），条长按组内最大值归一。
 *
 * @param valueText 数值文案（金额带货币符、油耗带单位），由调用方决定。
 */
@Composable
internal fun CompareBars(
    bars: List<CompareBar>,
    valueText: (Double) -> String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 72.dp,
    valueWidth: Dp = 80.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val max = bars.maxOfOrNull { it.value } ?: 0.0
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bars.forEach { bar ->
            val text = valueText(bar.value)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${bar.label} $text"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(labelWidth),
                )
                BarTrack(
                    fraction = barFraction(bar.value, max),
                    color = bar.color,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(valueWidth),
                )
            }
        }
    }
}

/** 空轨道 + 实心条；无数据时只留一道浅轨道。 */
@Composable
internal fun BarTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2f))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

/** 分组柱图的一个系列（本期 / 上期 / 同比）。 */
internal data class MetricSeries(val label: String, val color: Color)

/**
 * 分组柱图的一行：一个指标，[values] 与系列一一对应（缺数据用 null 保留空轨道）。
 *
 * [deltaText] 是主对比（月报=环比、年报=同比）的简短文案，放在当前值下面。
 */
internal data class MetricBarRow(
    val label: String,
    val valueText: String,
    val values: List<Double?>,
    val deltaText: String?,
)

/**
 * 指标分组柱图：日均 / 每公里 / 记录 / 里程各一行，行内三根条分别是本期 / 上期 / 同比。
 *
 * 每行按该行自己的最大值归一，所以「每公里」与「记录数」这种不同量纲也能并排。
 */
@Composable
internal fun MetricBars(
    series: List<MetricSeries>,
    rows: List<MetricBarRow>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            series.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(item.color),
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        rows.forEach { row ->
            val max = row.values.filterNotNull().maxOrNull() ?: 0.0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(row.label).append(' ').append(row.valueText)
                            row.deltaText?.let { append("，").append(it) }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(64.dp),
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.values.forEachIndexed { index, value ->
                        BarTrack(
                            fraction = value?.let { barFraction(it, max) } ?: 0f,
                            color = series.getOrNull(index)?.color ?: scheme.outlineVariant,
                            modifier = Modifier.weight(1f),
                            height = 8.dp,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier.width(110.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = row.valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.deltaText?.let { delta ->
                        Text(
                            text = delta,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

/** 分类增减条的一行（金额 / 占比 / 与对比期的差值）。 */
internal data class CategoryDeltaBar(
    val label: String,
    /** 金额数值：没有对比期时用它画占比条。 */
    val value: Double,
    val valueText: String,
    val shareText: String,
    val color: Color,
    /** 与对比期的差值；null = 这一视角没有对比期（累计），不画增减条。 */
    val delta: Double?,
    val deltaText: String,
)

/**
 * 分类增减条形图：以中线为零点，向右=增加、向左=减少，条长按组内最大 |差值| 归一。
 *
 * 条色沿用分类色（与环图一致），百分比只是刻度标注，不靠颜色区分正负。
 */
@Composable
internal fun CategoryDeltaBars(
    rows: List<CategoryDeltaBar>,
    total: Double,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val maxAbs = rows.mapNotNull { it.delta?.let(::abs) }.maxOrNull() ?: 0.0
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { row ->
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(row.color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 占比在上、增减幅度在下：两行都靠右，读起来是「这一项占多少 / 比上期变了多少」。
                    Column(
                        modifier = Modifier.width(56.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = row.shareText,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                        )
                        if (row.delta != null) {
                            Text(
                                text = row.deltaText,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                if (row.delta != null) {
                    // 满宽、以卡片中线为零点：右边=增加、左边=减少，最大 |差值| 占满半宽。
                    DivergingBar(
                        delta = row.delta,
                        maxAbs = maxAbs,
                        color = row.color,
                        trackColor = scheme.outlineVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${row.label} ${row.deltaText}"
                            },
                    )
                } else {
                    // 没有对比期（累计）：画占比条，至少让每一行仍有图形。
                    BarTrack(
                        fraction = barFraction(row.value, total),
                        color = row.color.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${row.label} ${row.shareText}"
                            },
                        height = 8.dp,
                    )
                }
            }
        }
    }
}

/** 单根增减条：中线 + 向左 / 向右的圆角条。 */
@Composable
private fun DivergingBar(
    delta: Double,
    maxAbs: Double,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        drawLine(
            color = trackColor,
            start = Offset(centerX, 1.dp.toPx()),
            end = Offset(centerX, size.height - 1.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        val fraction = divergingFraction(delta, maxAbs)
        val length = fraction * (size.width / 2f - 2.dp.toPx())
        if (length <= 0f) return@Canvas
        val barHeight = 7.dp.toPx()
        val left = if (delta >= 0.0) centerX else centerX - length
        drawRoundRect(
            color = color,
            topLeft = Offset(left, (size.height - barHeight) / 2f),
            size = Size(length, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
    }
}

/** 卡片内的小切换器（环比 / 同比）：胶囊底 + 选中实底。 */
@Composable
internal fun ChartModeToggle(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active) scheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
