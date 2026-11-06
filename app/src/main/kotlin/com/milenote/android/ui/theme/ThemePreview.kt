package com.milenote.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * 主题预览：5 套皮肤 × 浅/深 = 10 张。
 *
 * 打开 Android Studio 的 Compose Preview 即可逐张检查：
 * 卡片是否为中性表面色（不是 Material 默认紫）、Hero 渐变、语义色、字阶。
 */
private class ThemePreviewParameters : PreviewParameterProvider<Pair<AppTheme, Boolean>> {
    override val values: Sequence<Pair<AppTheme, Boolean>> = sequence {
        AppTheme.entries.forEach { theme ->
            yield(theme to false)
            yield(theme to true)
        }
    }
}

@Preview(name = "5 皮肤 × 浅/深", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ThemePreview(
    @PreviewParameter(ThemePreviewParameters::class) parameters: Pair<AppTheme, Boolean>,
) {
    val (appTheme, dark) = parameters
    MileNoteTheme(appTheme = appTheme, darkTheme = dark) {
        ThemeShowcase()
    }
}

@Composable
private fun ThemeShowcase() {
    val scheme = MaterialTheme.colorScheme
    val gradient = LocalMileNoteHeroGradient.current
    val semantic = LocalMileNoteSemanticColors.current
    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hero 渐变 + 文字色
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MileNoteShapes.extraLarge)
                    .background(Brush.linearGradient(gradient.colors))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = MileNoteThemeTokens.appTheme.label,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = gradient.contentColor,
                )
                Text(
                    text = "¥ 10,727.22 · 86 笔费用",
                    style = MaterialTheme.typography.moneyLarge,
                    color = gradient.contentColor,
                )
                Text(
                    text = "品牌色",
                    style = MaterialTheme.typography.labelMedium,
                    color = gradient.contentColor.copy(alpha = 0.8f),
                )
            }

            // 关键角色色板
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(scheme.primary, "primary")
                Swatch(scheme.primaryContainer, "priC")
                Swatch(scheme.secondaryContainer, "secC")
                Swatch(scheme.tertiaryContainer, "terC")
                Swatch(scheme.surfaceContainerLow, "surfLow")
                Swatch(scheme.errorContainer, "errC")
            }

            // 卡片：必须与背景有层级差且为中性色
            Surface(
                color = scheme.surfaceContainerLow,
                shape = MileNoteShapes.large,
                border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("卡片标题", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "卡片正文 bodyMedium 14/20，中文不做 letterSpacing。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        "2026-11-04 · 12,345 km · 7.38 L/100km",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            // 语义色
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SemanticSwatch(semantic.warning.container, semantic.warning.content, "即将到期")
                SemanticSwatch(semantic.danger.container, semantic.danger.content, "已过期")
                SemanticSwatch(semantic.success.container, semantic.success.content, "已完成")
            }

            // 分类色环（9 类固定顺序）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MileNoteThemeTokens.categoryColors.forEach { color ->
                    Surface(
                        color = color,
                        shape = CircleShape,
                        modifier = Modifier.size(24.dp),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(36.dp)) {}
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
private fun SemanticSwatch(container: Color, content: Color, label: String) {
    Surface(color = container, shape = MileNoteShapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
