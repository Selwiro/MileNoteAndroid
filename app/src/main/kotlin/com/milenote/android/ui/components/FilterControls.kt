package com.milenote.android.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons

/*
 * 紧凑筛选控件（明细页与卡片右上角共用）。
 *
 * 明细页的「搜索框 + 筛选胶囊」以及卡片右上角的筛选胶囊原来各页各写一套，高度也不一致
 * （搜索框 56dp、筛选胶囊 48dp / 32dp）。这里统一成一组 32dp 高的紧凑控件，供
 * 「费用统计-明细」「能耗分析-明细」「能耗走势 / 概览」复用：
 * - CompactSearchField：BasicTextField + 圆形描边（聚焦时描边变主色），高度 [CompactControlHeight]；
 *   底色保持透明（与原来的 OutlinedTextField 一样，浅色下就是页面白底），只压高度、不改配色；
 * - FilterPill：圆形 surfaceContainerHigh 胶囊 + DropdownMenu，高度同 [CompactControlHeight]；
 * - FilterMenuGroupLabel / FilterMenuCheckIcon / FilterMenuDivider：多分组菜单的标题、勾选与分隔线。
 *
 * 32dp 是「全部」时间胶囊的既有视觉高度（420dpi = 84px），对齐后同一行控件不再高低不齐。
 * 该高度低于 [com.milenote.android.ui.theme.MileNoteDimens.TouchTarget]（48dp），属于紧凑筛选行，
 * 不改动 Dimens 设计 token。
 */

/** 紧凑筛选行统一高度：搜索框与筛选胶囊都按 32dp 对齐（420dpi = 84px）。 */
private val CompactControlHeight = 32.dp

/**
 * 紧凑搜索框（[CompactControlHeight] 高、圆形描边、`outlineVariant` 边框、**透明底**）：
 * 左侧 18dp 搜索图标 + 占位文字，聚焦时描边变 `primary`。底色不加填充，
 * 浅色主题下与页面白底一致（与改造前的 OutlinedTextField 视觉相同），只把上下高度压到 32dp。
 *
 * @param value 当前关键词。
 * @param onValueChange 关键词变化回调。
 * @param placeholder 空态占位文字（已翻译）。
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(CompactControlHeight)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = if (focused) scheme.primary else scheme.outlineVariant,
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = placeholder }
            .padding(start = 10.dp, end = 12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        singleLine = true,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("清空搜索", "Clear search"),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") },
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * 筛选胶囊（[CompactControlHeight] 高）：显示当前筛选摘要，点开 [content] 里的菜单项。
 *
 * @param label 胶囊文案（无筛选时传「筛选」，有筛选时传「本月」「本月 · 电耗」这类摘要）。
 * @param expanded 菜单是否展开。
 * @param onToggle 点击胶囊回调（通常置 `expanded = true`）。
 * @param onDismiss 点菜单外部 / 返回键回调。
 * @param contentDescription 下拉箭头的无障碍描述。
 * @param content 菜单内容（用 [FilterMenuGroupLabel] / [FilterMenuCheckIcon] / [FilterMenuDivider] 组织分组）。
 */
@Composable
fun FilterPill(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    contentDescription: String = tr("筛选", "Filter"),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            modifier = Modifier.height(CompactControlHeight),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

/** 下拉菜单分组标题（时间 / 分类 / 能源）。 */
@Composable
fun FilterMenuGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 菜单选中勾：未选中返回 null（不占位），选中返回 18dp 对勾。 */
@Composable
fun FilterMenuCheckIcon(selected: Boolean): (@Composable () -> Unit)? =
    if (!selected) {
        null
    } else {
        {
            Icon(
                imageVector = MileNoteIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }

/** 菜单分组之间的分隔线（与勾选、重置项同宽，上下各留 4dp）。 */
@Composable
fun FilterMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
