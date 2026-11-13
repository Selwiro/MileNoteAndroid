package com.milenote.android.ui.screens.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milenote.android.domain.format.Format
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr

/** 趋势图的一根柱：[key] 用来高亮当前期（`YYYY-MM` / `YYYY`）。 */
internal data class ReportBar(
    val key: String,
    val label: String,
    val value: Double,
)

private val ReportChartHeight = 132.dp
private val ReportAxisHeight = 18.dp

/**
 * 报告页的迷你柱图（单序列：每月 / 每年的支出合计）。
 *
 * 只画总额，不堆分类——报告回答「哪个月花得多」，要看构成请回到上面的费用构成卡。
 * 高亮的柱（当前期）满色，其余 45% 透明；柱顶不标数值，避免 12 个数字打架。
 *
 * @param onBarClick 点某根柱把它的 key（`YYYY-MM` / `YYYY`）交给页面切期；null 时不可点。
 */
@Composable
internal fun ReportTrendChart(
    bars: List<ReportBar>,
    highlightKey: String?,
    modifier: Modifier = Modifier,
    onBarClick: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = remember(scheme) {
        TextStyle(fontSize = 9.sp, color = scheme.onSurfaceVariant)
    }
    val max = remember(bars) { maxOf(bars.maxOfOrNull { it.value } ?: 0.0, 1.0) }
    val total = remember(bars) { bars.sumOf { it.value } }
    val summary = tr(
        "支出趋势图，共 %d 个柱，合计 %s %s%s",
        "Spending trend, %d bars, %s %s in total%s",
        bars.size,
        Format.money(total),
        Units.money,
        if (onBarClick != null) tr("。点按柱子可切换到该期", ". Tap a bar to open that period") else "",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReportChartHeight)
                .pointerInput(bars, onBarClick) {
                    if (onBarClick == null) return@pointerInput
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / bars.size.coerceAtLeast(1)
                        val index = (offset.x / slot).toInt().coerceIn(0, bars.lastIndex)
                        onBarClick(bars[index].key)
                    }
                }
                .semantics { contentDescription = summary },
        ) {
            val axisHeight = ReportAxisHeight.toPx()
            val plotHeight = size.height - axisHeight
            val slot = size.width / bars.size.coerceAtLeast(1)
            val barWidth = (slot * 0.52f).coerceAtLeast(3.dp.toPx())
            bars.forEachIndexed { index, bar ->
                val ratio = (bar.value / max).toFloat().coerceIn(0f, 1f)
                val height = plotHeight * ratio
                val left = index * slot + (slot - barWidth) / 2f
                val highlighted = highlightKey == null || bar.key == highlightKey
                val color = if (highlighted) scheme.primary else scheme.primary.copy(alpha = 0.45f)
                if (height > 0.5f) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, plotHeight - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    )
                } else {
                    // 空月给一条 2dp 底纹，柱子为 0 时也看得出「这里有这个月」。
                    drawRoundRect(
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        topLeft = Offset(left, plotHeight - 2.dp.toPx()),
                        size = Size(barWidth, 2.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                    )
                }
            }
            // 月份 / 年份轴：柱多时隔一个标一个，避免 1.3× 下挤成一团。
            val step = if (bars.size > 8) 2 else 1
            bars.forEachIndexed { index, bar ->
                // 柱多时抽稀，但最后一个永远标（否则高亮的「本月」没有轴标签）。
                if (index % step != 0 && index != bars.lastIndex) return@forEachIndexed
                val layout = textMeasurer.measure(AnnotatedString(bar.label), axisStyle)
                val x = index * slot + (slot - layout.size.width) / 2f
                drawText(layout, topLeft = Offset(x, plotHeight + 2.dp.toPx()))
            }
        }
    }
}
