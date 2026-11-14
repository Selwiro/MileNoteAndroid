package com.milenote.android.ui.screens.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 用车报告里的两根对比条：本期实色、上期中性色，每行按 max(本期, 上期) 归一。
 *
 * 数字永远标在条旁边：图只负责「哪边更长」，精确值由调用方给文字。
 */

/**
 * 一条横条的占比：0..1；[max] 为 0 / 非法 / 负数时返回 0（画空轨道）。
 *
 * 对比卡与分享图共用，单测直接覆盖边界。
 */
internal fun barFraction(value: Double, max: Double): Float {
    if (!value.isFinite() || !max.isFinite() || max <= 0.0 || value <= 0.0) return 0f
    return (value / max).toFloat().coerceIn(0f, 1f)
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
        horizontalArrangement = Arrangement.spacedBy(0.dp),
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
