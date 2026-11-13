package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons

/*
 * 紧凑筛选控件（明细页 / 记录页 / 卡片右上角共用）。
 *
 * 2026-11-13 列表页搜索条改为**一体化搜索条**（方案 C）：搜索框与筛选入口共用一个
 * 44dp 高的圆形描边容器，右侧内嵌 38dp 筛选圆钮，不再是一个独立的实心胶囊。
 * 高度取 32dp（0.38.x 太矮）与 56dp（0.37.1 / 0.40.0 太高）的中间值：
 * - SearchBarShell：44dp 高、1dp `outlineVariant` 描边、聚焦时描边变 `primary` 的容器；
 * - SearchBarInput：容器内的搜索输入（18dp 放大镜 + 16sp 正文 + 32dp 清空热区）；
 * - SearchBarFilterButton：右侧 38dp 圆钮，无筛选时透明、有筛选时填充 `primaryContainer`；
 *   数量不挂在按钮上（角标会盖住漏斗图标、压到搜索条描边），由下方已选 chips 与面板里的计数表达；
 * - FilterPill 仍是卡片头 / 总览的 32dp 紧凑胶囊，与列表页 44dp 搜索条是两种场景，不混用。
 *
 * 38dp 圆钮低于 [com.milenote.android.ui.theme.MileNoteDimens.TouchTarget]（48dp），
 * 与既有 32dp 紧凑行同级，属于列表页紧凑控件；不因此改动 Dimens 设计 token。
 */

/** 列表页搜索条高度：44dp（420dpi = 116px）。 */
private val SearchBarHeight = 44.dp

/** 搜索条右侧筛选圆钮直径：38dp（在 44dp 条内上下各留 3dp）。 */
private val SearchBarActionSize = 38.dp

/**
 * 一体化搜索条的容器外壳：44dp 高、全圆描边，聚焦时描边变 `primary`。
 *
 * @param focused 输入框是否持有焦点（决定描边颜色）。
 * @param content 容器内容（通常是一个 [SearchBarInput] + 分隔线 + [SearchBarFilterButton]）。
 */
@Composable
internal fun SearchBarShell(
    focused: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(SearchBarHeight)
            .border(
                width = 1.dp,
                color = if (focused) scheme.primary else scheme.outlineVariant,
                shape = CircleShape,
            )
            .padding(start = 16.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 搜索条内的输入区：左侧 18dp 放大镜、正文 [androidx.compose.material3.Typography.bodyLarge]、
 * 有内容时显示 32dp 热区的清空按钮；高度由外层容器决定，底色透明。
 *
 * @param value 当前关键词。
 * @param onValueChange 关键词变化回调。
 * @param placeholder 空态占位文字（已翻译）。
 * @param focusRequester 外层用于管理焦点的 requester（下拉 / 清空后可复用）。
 * @param onFocusChange 焦点变化回调（外层据此切换描边颜色）。
 */
@Composable
internal fun SearchBarInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .semantics { contentDescription = placeholder },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        singleLine = true,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Close,
                            contentDescription = tr("清空搜索", "Clear search"),
                            modifier = Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 搜索条右侧的筛选圆钮：无筛选时透明描边（由外层容器提供描边），
 * 有筛选时填充 `primaryContainer`，数量交给下方已选 chips 表达。
 *
 * @param activeCount 生效的筛选条件数（0 表示无筛选）。
 * @param onClick 打开筛选面板。
 */
@Composable
internal fun SearchBarFilterButton(
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val active = activeCount > 0
    val contentDescription = if (active) {
        tr("筛选，已选 %d 项", "Filter, %d active", activeCount)
    } else {
        tr("筛选", "Filter")
    }
    Box(
        modifier = modifier
            .size(SearchBarActionSize)
            .clip(CircleShape)
            .background(if (active) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MileNoteIcons.FilterList,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = if (active) scheme.onPrimaryContainer else scheme.onSurface,
        )
    }
}

/**
 * 筛选胶囊（[CompactControlHeight] 高，**永远单行**）：显示当前筛选摘要，点开 [content] 里的菜单项。
 *
 * 2026-11-13：原先可选的第二行说明（hint）在 1.3× 字号下会把胶囊撑得比 32dp 视图开关更高、
 * 让卡头控件不齐，因此移除；需要解释窗口口径的卡片改在**下拉菜单首行**展示（见 `RangeFilterAction`）。
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
            // 单行标签；大字号下用 minHeight 兜底，保持 32dp 基准。
            modifier = Modifier.heightIn(min = CompactControlHeight),
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

/** 卡片头 / 总览的紧凑胶囊高度：32dp（420dpi = 84px），低于 48dp 触控基准，属紧凑筛选行。 */
private val CompactControlHeight = 32.dp

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
