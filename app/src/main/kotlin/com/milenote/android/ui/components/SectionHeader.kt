package com.milenote.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 区块标题（页面内一级/二级区块的小标题 + 可选尾部动作）。
 *
 * 与 [SectionCard] 的区别：无容器背景，用于页面直接分区；`title` 使用 titleLarge。
 *
 * @param title 区块标题。
 * @param subtitle 标题下说明。
 * @param actionLabel 右侧文字动作，如「全部记录 ↗」的纯文字版（不加下划线）。
 * @param actionIcon 右侧动作图标，默认 [MileNoteIcons.ChevronRight]。
 * @param onAction 点击动作；为 null 时不显示动作。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = MileNoteIcons.ChevronRight,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!eyebrow.isNullOrBlank()) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.6.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onAction != null && actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                if (actionIcon != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SectionHeaderPreview() {
    MileNoteTheme {
        SectionHeader(
            title = "爱车待办",
            subtitle = "未来一年内 3 项",
            actionLabel = "管理提醒",
            onAction = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
