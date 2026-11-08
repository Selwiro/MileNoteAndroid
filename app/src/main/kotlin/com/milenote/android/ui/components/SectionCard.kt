package com.milenote.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 标准卡片容器（M3 tonal surface，16dp 圆角）。
 *
 * - 浅色：白色底 + 1dp 轻阴影，不描边（卡片比页面背景更亮，靠浅阴影分层）。
 * - 深色：使用 `surfaceContainerLow`，无阴影、不描边。
 * - 可选标题 / 副标题 / 尾部动作；内容通过 [content] 传入。
 *
 * ```kotlin
 * SectionCard(title = "最近记录", action = { TextButton(onClick) { Text("全部记录") } }) {
 *     RecordRow(...)
 * }
 * ```
 *
 * @param title 卡片标题，null 时不显示标题区。
 * @param subtitle 标题下的说明文字（bodySmall）。
 * @param action 标题行右侧的尾部动作（文字按钮 / 图标按钮）。
 * @param contentPadding 卡片内边距，默认 16dp。
 * @param containerColor 卡片底色，默认 `surfaceContainerLow`。
 * @param borderColor 浅色模式描边颜色，null 时使用 `outlineVariant`。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(MileNoteDimens.CardPadding),
    containerColor: Color = Color.Unspecified,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalMileNoteDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val actualContainer = if (containerColor == Color.Unspecified) {
        if (dark) scheme.surfaceContainerLow else Color.White
    } else {
        containerColor
    }
    val actualBorder = null as BorderStroke?
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MileNoteShapes.large,
        color = actualContainer,
        contentColor = scheme.onSurface,
        border = actualBorder,
        shadowElevation = if (dark) 0.dp else 1.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    action?.invoke()
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SectionCardPreview() {
    MileNoteTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SectionCard(
                title = "最近记录",
                subtitle = "近 5 笔费用",
                modifier = Modifier.padding(16.dp),
                action = { Text("全部记录") },
            ) {
                Text("列表内容", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
