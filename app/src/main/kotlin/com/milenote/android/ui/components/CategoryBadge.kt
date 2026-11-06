package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteCategoryNames
import com.milenote.android.ui.theme.MileNoteTheme
import com.milenote.android.ui.theme.categoryColorOf

/**
 * 分类圆标：分类色 12% 容器底 + 24dp 分类图标（记录行 leading、筛选结果、导入预览）。
 *
 * 颜色来自当前主题的 9 类固定图表色（[com.milenote.android.ui.theme.LocalMileNoteCategoryColors]），
 * 与图表/图例同义。
 *
 * ```kotlin
 * CategoryBadge(categoryName = record.category)        // 自动匹配图标与颜色
 * CategoryBadge(categoryName = "自定义分类", icon = MileNoteIcons.CategoryGoods)
 * ```
 *
 * @param categoryName 分类名（9 类固定顺序或自定义分类）。
 * @param icon 分类图标；null 时按 [categoryName] 从 [MileNoteIcons.Category] 自动匹配。
 * @param size 圆标直径，默认 40dp。
 * @param accentColor 分类强调色，默认 [categoryColorOf]。
 */
@Composable
fun CategoryBadge(
    categoryName: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: Dp = 40.dp,
    accentColor: Color = categoryColorOf(categoryName),
) {
    val normalized = if (categoryName == "维修保养") "保养" else categoryName
    val index = MileNoteCategoryNames.indexOf(normalized)
    val resolvedIcon = icon ?: if (index >= 0) {
        MileNoteIcons.Category[if (index >= MileNoteIcons.Category.size) 0 else index]
    } else {
        MileNoteIcons.CategoryGoods
    }
    Box(
        modifier = modifier
            .size(size)
            .background(accentColor.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resolvedIcon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.55f),
            tint = accentColor,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CategoryBadgePreview() {
    MileNoteTheme {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            MileNoteCategoryNames.forEach { name ->
                CategoryBadge(categoryName = name)
            }
        }
    }
}
