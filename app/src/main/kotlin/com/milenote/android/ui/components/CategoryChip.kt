package com.milenote.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteCategoryNames
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteTheme
import com.milenote.android.ui.theme.categoryColorOf

/**
 * 分类芯片（9 类费用单选/多选）。
 *
 * 选中态：分类色 16% 容器底 + 分类色描边与图标，文字保持 `onSurface` 保证对比度；
 * 未选中态：`surfaceContainerLow` 底 + `outlineVariant` 描边。
 *
 * @param name 分类名（固定 9 类之一或自定义分类）。
 * @param selected 是否选中。
 * @param onClick 点按回调。
 * @param icon 分类图标，默认由调用方从 [MileNoteIcons.Category] 传入。
 * @param accentColor 分类强调色，默认按 [name] 取 [categoryColorOf]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: androidx.compose.ui.graphics.Color = categoryColorOf(name),
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MileNoteShapeTokens.full,
        label = {
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        },
        leadingIcon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surfaceContainerLow,
            labelColor = scheme.onSurface,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = accentColor.copy(alpha = 0.16f),
            selectedLabelColor = scheme.onSurface,
            selectedLeadingIconColor = accentColor,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) accentColor else scheme.outlineVariant,
        ),
    )
}

/**
 * 9 类费用芯片网格（FlowRow，可换行；顺序固定，不按金额排序）。
 *
 * 用于「记一笔」表单的「分类」区块与记录页筛选行。
 *
 * @param selected 当前选中的分类名；null 表示未选择。
 * @param onSelect 选择回调。
 * @param categories 分类名列表，默认 9 类固定顺序 [MileNoteCategoryNames]。
 * @param icons 与 [categories] 对齐的图标列表。
 * @param onAddCategory 可选「＋ 新增分类」入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChipGrid(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    categories: List<String> = MileNoteCategoryNames,
    icons: List<ImageVector> = MileNoteIcons.Category,
    onAddCategory: (() -> Unit)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEachIndexed { index, category ->
            CategoryChip(
                name = category,
                selected = category == selected,
                onClick = { onSelect(category) },
                icon = icons.getOrNull(index),
            )
        }
        if (onAddCategory != null) {
            AssistChip(
                onClick = onAddCategory,
                shape = MileNoteShapeTokens.full,
                label = { Text("新增分类", style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        imageVector = MileNoteIcons.Add,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CategoryChipGridPreview() {
    MileNoteTheme {
        CategoryChipGrid(
            selected = "油费",
            onSelect = {},
            onAddCategory = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
