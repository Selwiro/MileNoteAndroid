package com.milenote.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.MileNoteTheme
import com.milenote.android.ui.theme.SemanticColorPair

/**
 * 提示条语义类型。
 */
enum class MessageKind {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,

    /** 演示账本横幅（唯一紫色语义）。 */
    DEMO,
}

/**
 * 行内提示条（成功 / 警告 / 错误 / 信息 / 演示横幅）。
 *
 * 颜色一律来自语义色（[LocalMileNoteSemanticColors]），全皮肤下含义不变；
 * 图标 + 文字 + 容器色三重表达，不只靠颜色。错误提示可用 `liveRegion` 播报（页面侧设置）。
 *
 * ```kotlin
 * MessageBar(message = "保存失败，请先导出备份", kind = MessageKind.ERROR, actionLabel = "重试", onAction = ...)
 * ```
 *
 * @param message 提示正文（≤ 2 行）。
 * @param kind 语义类型。
 * @param actionLabel 可选动作文字（如「重试」「撤销」）。
 * @param onAction 动作回调。
 * @param onDismiss 关闭回调；为 null 时不显示关闭按钮。
 * @param icon 覆盖默认图标。
 */
@Composable
fun MessageBar(
    message: String,
    kind: MessageKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val semantic = LocalMileNoteSemanticColors.current
    val pair: SemanticColorPair = when (kind) {
        MessageKind.SUCCESS -> semantic.success
        MessageKind.WARNING -> semantic.warning
        MessageKind.ERROR -> semantic.danger
        MessageKind.INFO -> semantic.info
        MessageKind.DEMO -> semantic.demo
    }
    val resolvedIcon = icon ?: when (kind) {
        MessageKind.SUCCESS -> MileNoteIcons.CheckCircle
        MessageKind.WARNING -> MileNoteIcons.Warning
        MessageKind.ERROR -> MileNoteIcons.Error
        MessageKind.INFO -> MileNoteIcons.Info
        MessageKind.DEMO -> MileNoteIcons.Sparkle
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MileNoteShapes.medium,
        color = pair.container,
        contentColor = pair.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = pair.content,
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("关闭提示", "Dismiss message"),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 里程志 Snackbar Host：统一圆角与主题色，替换页面里的默认 `SnackbarHost`。
 *
 * ```kotlin
 * val snackbarHostState = remember { SnackbarHostState() }
 * Scaffold(snackbarHost = { MileNoteSnackbarHost(snackbarHostState) }) { ... }
 * snackbarHostState.showSnackbar("已记录 ¥266.00", actionLabel = "撤销", duration = SnackbarDuration.Short)
 * ```
 */
@Composable
fun MileNoteSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MileNoteShapes.medium,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MessageBarPreview() {
    MileNoteTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MessageBar("已保存", MessageKind.SUCCESS, actionLabel = "撤销", onAction = {})
            MessageBar("30 天内到期：机油机滤", MessageKind.WARNING)
            MessageBar("本地账本暂时无法读取", MessageKind.ERROR, actionLabel = "重试", onAction = {})
            MessageBar("演示账本 · 修改不会保留", MessageKind.DEMO, onDismiss = {})
        }
    }
}
