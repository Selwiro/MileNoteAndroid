package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 空状态：插画/图标 + 标题 + 说明 + 一个明确的下一步动作（每个空态只给一个主动作）。
 *
 * 插画规范（设计规范 §9.4）：线描 2dp、双色（primary 60% + tertiary 40%）、12% 容器底、160×120dp、不含文字。
 * 通过 [illustration] 传入 res 里的线描 vector（如 `R.drawable.ill_empty_records`）；
 * 也支持只传 [icon] 的轻量场景（96dp 圆形容器 + 40dp 图标）。
 *
 * ```kotlin
 * EmptyState(
 *     title = "这里还没有费用记录",
 *     description = "记下一笔支出，让用车成本一目了然。",
 *     illustration = painterResource(R.drawable.ill_empty_records),
 *     actionLabel = "记一笔",
 *     onAction = { ... },
 *     secondaryLabel = "从文件导入",
 *     onSecondaryAction = { ... },
 * )
 * ```
 *
 * @param title 标题（如「这本账还空着」）。
 * @param description 说明（最多 3 行）。
 * @param icon 轻量图标（与 [illustration] 二选一，优先 illustration）。
 * @param illustration 线描插画（160×120dp 比例）。
 * @param actionLabel 主动作文字；null 时无按钮。
 * @param onAction 主动作回调。
 * @param secondaryLabel 次动作文字（TextButton）。
 * @param onSecondaryAction 次动作回调。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    illustration: Painter? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MileNoteDimens.PageHorizontal, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            illustration != null -> {
                Icon(
                    painter = illustration,
                    contentDescription = null,
                    modifier = Modifier
                        .width(160.dp)
                        .height(120.dp),
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
            icon != null -> {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = MileNoteShapeTokens.full,
                    modifier = Modifier.height(MileNoteDimens.ButtonMinHeight),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
                if (secondaryLabel != null && onSecondaryAction != null) {
                    TextButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.height(MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(secondaryLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun EmptyStatePreview() {
    MileNoteTheme {
        EmptyState(
            title = "这里还没有费用记录",
            description = "记下一笔支出，让用车成本一目了然。",
            icon = MileNoteIcons.Records,
            actionLabel = "记一笔",
            onAction = {},
            secondaryLabel = "从文件导入",
            onSecondaryAction = {},
        )
    }
}
