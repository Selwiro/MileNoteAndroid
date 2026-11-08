package com.milenote.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.i18n.Units
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 列表行卡片（记录 / 加油明细 / 保养记录 / 车辆列表）。
 *
 * 结构：可选 leading（40dp 分类圆标 / 车辆缩略图）+ 主内容（标题 + 副标题 + 支持文字）+ 可选 trailing（金额/状态）。
 * 行高 ≥ 72dp（双行），触控目标覆盖整行 48dp+；可选底部分隔线。
 *
 * ```kotlin
 * ListRowCard(
 *     title = "油费 · 加满",
 *     subtitle = "11-02 · 中石化 · 12,345 ${Units.distanceUnit}",
 *     leading = { CategoryBadge("油费") },
 *     trailing = { MoneyText("266.00", unit = Units.money) },
 *     onClick = { ... },
 * )
 * ```
 *
 * @param title 主文案（分类 · 项目）。
 * @param subtitle 次要信息（日期 · 地点 · 里程）。
 * @param supporting 第三行补充（备注/口径）。
 * @param leading 行首内容（分类图标底 / 缩略图）。
 * @param trailing 行尾内容（金额 / 状态 / 箭头）。
 * @param onClick 点按整行。
 * @param showDivider 是否显示底部分隔线（用于同一分组内的行）。
 */
@Composable
fun ListRowCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MileNoteShapes.large,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MileNoteDimens.RowMinHeightTwoLine)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leading?.invoke()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (supporting != null) {
                        Text(
                            text = supporting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trailing?.invoke()
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ListRowCardPreview() {
    MileNoteTheme {
        ListRowCard(
            title = "油费 · 加满",
            subtitle = "11-02 · 中石化 · 12,345 ${Units.distanceUnit}",
            leading = {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(2.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = MileNoteIcons.CategoryFuel,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            trailing = { MoneyText(amount = "266.00", unit = Units.money) },
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
