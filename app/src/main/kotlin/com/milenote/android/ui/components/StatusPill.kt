package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 状态徽章状态（与 Web `reminderState` 的 `normal/soon/overdue/done` 一一对应）。
 */
enum class StatusKind {
    /** 计划中 / 中性。 */
    NORMAL,

    /** 即将到期（30 天内或 1000km 内）。 */
    SOON,

    /** 已过期。 */
    OVERDUE,

    /** 已完成。 */
    DONE,
}

/**
 * 状态徽章：文字 + 圆点，**不只靠颜色**表达状态（色觉缺陷友好）。
 *
 * 颜色来自语义色 [LocalMileNoteSemanticColors]：
 * `NORMAL → surfaceContainerHighest`、`SOON → warning`、`OVERDUE → danger`、`DONE → success`。
 * 全皮肤下语义保持一致。
 *
 * @param text 状态文案（必须有文字，不能只有色点）。
 * @param status 状态等级。
 * @param icon 可选前置图标（如铃铛、勾）。
 */
@Composable
fun StatusPill(
    text: String,
    status: StatusKind,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMileNoteSemanticColors.current
    val container: Color
    val content: Color
    when (status) {
        StatusKind.NORMAL -> {
            container = scheme.surfaceContainerHighest
            content = scheme.onSurfaceVariant
        }
        StatusKind.SOON -> {
            container = semantic.warning.container
            content = semantic.warning.content
        }
        StatusKind.OVERDUE -> {
            container = semantic.danger.container
            content = semantic.danger.content
        }
        StatusKind.DONE -> {
            container = semantic.success.container
            content = semantic.success.content
        }
    }
    Surface(
        modifier = modifier,
        shape = MileNoteShapeTokens.full,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(content, CircleShape),
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StatusPillPreview() {
    MileNoteTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("计划中", StatusKind.NORMAL)
            StatusPill("即将到期", StatusKind.SOON)
            StatusPill("已过期", StatusKind.OVERDUE)
            StatusPill("已完成", StatusKind.DONE)
        }
    }
}
