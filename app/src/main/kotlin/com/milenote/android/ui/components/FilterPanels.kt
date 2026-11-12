package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * 筛选体系的共享控件（2026-11-12 统一方案）：
 * - 统计页：StatsRangeRow（页级唯一范围入口）+ RangeMenuItems；
 * - 列表页：SearchFilterBar + ActiveFilterChips + FilterSheet（草稿态、实时计数）；
 * - 时间预设 / 文案：Period.rangeLabel()、rangeLabel()、customRangeLabel()（全 App 唯一一份）；
 * - 卡头内容切换：HeaderTextTabs（里程/支出、油耗/电耗同一套）。
 * 视觉高度沿用明细页 32dp 紧凑行；面板内每行触控热区 ≥ 48dp。
 */

// ---------------------------------------------------------------------------------------------
// 数量文案（英文单复数；中文不变）
// ---------------------------------------------------------------------------------------------

/** 汇总行「共 N 条记录」。 */
fun recordsCountLabel(count: Int): String =
    if (count == 1) tr("共 %d 条记录", "%d record", count) else tr("共 %d 条记录", "%d records", count)

/** 月份分组头「N 笔记录」。 */
fun entriesCountLabel(count: Int): String =
    if (count == 1) tr("%d 笔记录", "%d entry", count) else tr("%d 笔记录", "%d entries", count)

/** 保养记录汇总「N 次」。 */
fun serviceEntriesCountLabel(count: Int): String =
    if (count == 1) tr("%d 次", "%d entry", count) else tr("%d 次", "%d entries", count)

/** 筛选面板底部「查看 N 条」。 */
fun showResultsLabel(count: Int): String =
    if (count == 1) tr("查看 %d 条", "Show %d result", count) else tr("查看 %d 条", "Show %d results", count)

// ---------------------------------------------------------------------------------------------
// 时间预设文案与区间
// ---------------------------------------------------------------------------------------------

/** 时间预设文案（全 App 唯一一份，顺序同 [Period.entries]）。 */
fun Period.rangeLabel(): String = when (this) {
    Period.ALL -> tr("全部", "All")
    Period.MONTH -> tr("本月", "This month")
    Period.LAST_MONTH -> tr("上月", "Last month")
    Period.LAST_3_MONTHS -> tr("近 3 个月", "Last 3 months")
    Period.YEAR -> tr("今年", "This year")
}

/** 自定义范围的短文案：同年 `10.01–10.18`，跨年 `2025.12.01–2026.01.02`；解析失败返回 null。 */
fun customRangeLabel(customRange: DateRange?): String? {
    val range = customRange ?: return null
    val from = Dates.parse(range.from) ?: return null
    val to = Dates.parse(range.to) ?: return null
    return if (from.year == to.year) {
        "${monthDay(from)}–${monthDay(to)}"
    } else {
        "${yearMonthDay(from)}–${yearMonthDay(to)}"
    }
}

/** 时间胶囊文案：自定义优先，否则取预设文案。 */
fun rangeLabel(period: Period, customRange: DateRange?): String =
    customRangeLabel(customRange) ?: period.rangeLabel()

private fun monthDay(date: LocalDate): String =
    "${date.monthValue.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')}"

private fun yearMonthDay(date: LocalDate): String = "${date.year}.${monthDay(date)}"

// ---------------------------------------------------------------------------------------------
// 统计页：页级「统计范围」行
// ---------------------------------------------------------------------------------------------

/**
 * 卡片头范围胶囊：总览「用车统计」与油耗「概览」共用，直接放进 `SectionCard` 的 action 槽，
 * 不再占页面独立一行；同页的趋势/走势卡只跟随、不再放第二个时间控件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeFilterAction(
    period: Period,
    customRange: DateRange?,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    FilterPill(
        label = rangeLabel(period, customRange),
        expanded = expanded,
        onToggle = { expanded = true },
        onDismiss = { expanded = false },
        contentDescription = tr("选择统计范围", "Select date range"),
    ) {
        RangeMenuItems(
            period = period,
            customRange = customRange,
            onSelectPeriod = {
                onSelectPeriod(it)
                expanded = false
            },
            onSelectCustom = {
                expanded = false
                showRangePicker = true
            },
        )
    }
    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                onSelectCustomRange(from, to)
                showRangePicker = false
            },
        )
    }
}

/** 时间分组菜单项（全部 / 本月 / 上月 / 近 3 个月 / 今年 / 自定义…）。 */
@Composable
fun RangeMenuItems(
    period: Period,
    customRange: DateRange?,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustom: () -> Unit,
) {
    val presetSelected = customRange == null
    Period.entries.forEach { item ->
        DropdownMenuItem(
            text = { Text(item.rangeLabel()) },
            trailingIcon = FilterMenuCheckIcon(presetSelected && period == item),
            onClick = { onSelectPeriod(item) },
        )
    }
    DropdownMenuItem(
        text = { Text(tr("自定义…", "Custom…")) },
        trailingIcon = FilterMenuCheckIcon(customRange != null),
        onClick = onSelectCustom,
    )
}

/** 时间预设 chips（筛选面板内用；单选用 [FilterChip]，自定义选中时显示日期摘要）。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RangePresetChips(
    period: Period,
    customRange: DateRange?,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Period.entries.forEach { item ->
            FilterChip(
                selected = customRange == null && period == item,
                onClick = { onSelectPeriod(item) },
                shape = MileNoteShapeTokens.full,
                label = { Text(item.rangeLabel(), style = MaterialTheme.typography.labelLarge) },
            )
        }
        FilterChip(
            selected = customRange != null,
            onClick = onSelectCustom,
            shape = MileNoteShapeTokens.full,
            label = {
                Text(
                    text = customRangeLabel(customRange) ?: tr("自定义…", "Custom…"),
                    style = MaterialTheme.typography.labelLarge,
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 列表页：搜索 + 筛选胶囊 + 已选 chips
// ---------------------------------------------------------------------------------------------

/** 胶囊按钮（不带菜单）：用于打开筛选面板。 */
@Composable
fun FilterButton(
    label: String,
    onClick: () -> Unit,
    contentDescription: String = tr("筛选", "Filter"),
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        modifier = modifier.height(32.dp),
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
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(
                imageVector = MileNoteIcons.ChevronDown,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 列表页筛选栏：搜索框占满剩余宽度，右侧「筛选 · N」胶囊。 */
@Composable
fun SearchFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    filterCount: Int,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        FilterButton(
            label = if (filterCount == 0) {
                tr("筛选", "Filter")
            } else {
                tr("筛选 · %d", "Filter · %d", filterCount)
            },
            onClick = onOpenFilter,
            contentDescription = tr("打开筛选面板", "Open filters"),
        )
    }
}

/** 一个已选条件（chips 行）。 */
data class ActiveFilterChip(val key: String, val label: String)

/** 已选条件 chips：单个 × 移除，末尾「清除全部」。没有条件时不占位。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActiveFilterChips(
    chips: List<ActiveFilterChip>,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { chip ->
            InputChip(
                selected = false,
                onClick = { onRemove(chip.key) },
                shape = MileNoteShapeTokens.full,
                label = {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("移除 %s 筛选", "Remove %s filter", chip.label),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        TextButton(onClick = onClearAll) {
            Text(tr("清除全部", "Clear all"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 筛选面板
// ---------------------------------------------------------------------------------------------

/**
 * 底部筛选面板（草稿态）。
 *
 * 面板内的修改先写进页面的 draft；点「查看 N 条」才写回 ViewModel 并关闭。
 * 下滑 / 返回 / 关闭 = 放弃本次改动；「重置」只重置草稿、不关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    resultCount: Int,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("筛选", "Filter"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("关闭筛选", "Close filters"),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                content()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReset) {
                    Text(tr("重置", "Reset"))
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onApply) {
                    Text(showResultsLabel(resultCount))
                }
            }
        }
    }
}

/** 面板内分组标题（时间 / 分类 / 类型）。 */
@Composable
fun FilterSection(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 面板内的一个选项（分类 / 类型）。 */
data class FilterOption(
    val key: String,
    val label: String,
    val count: Int? = null,
    val color: Color? = null,
)

/** 单列复选行（类型，选项少时用）。 */
@Composable
fun FilterOptionList(
    options: List<FilterOption>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        options.forEach { option ->
            FilterCheckRow(
                option = option,
                checked = option.key in selected,
                onClick = { onToggle(option.key) },
            )
        }
    }
}

/** 复选行：整行可点，高 48dp；可选分类色点与命中条数。 */
@Composable
private fun FilterCheckRow(
    option: FilterOption,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MileNoteShapes.small)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        option.color?.let { color ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (option.count != null) {
            Text(
                text = option.count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 卡头文字 tab（里程 / 支出、油耗 / 电耗）
// ---------------------------------------------------------------------------------------------

/**
 * 卡头文字 tab：选中加粗 + 3dp 主色下划线，宽度贴文字。
 *
 * 总览「里程 / 支出」与油耗页「油耗 / 电耗」共用；内容切换不是筛选。
 */
@Composable
fun HeaderTextTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Column(
                modifier = Modifier
                    .clip(MileNoteShapes.small)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(top = 4.dp, bottom = 4.dp)
                    // 宽度收紧到文字宽：下划线不能把整行拉满，否则第二个 tab 会被挤出卡外。
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = label,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 自定义日期范围
// ---------------------------------------------------------------------------------------------

/** 自定义统计范围：M3 DateRangePicker（闭区间，ISO 日期）。全 App 唯一一份。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRangePickerDialog(
    customRange: DateRange?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val initialStart = remember(customRange) { customRange?.from?.let(::toUtcMillis) }
    val initialEnd = remember(customRange) { customRange?.to?.let(::toUtcMillis) }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart,
        initialSelectedEndDateMillis = initialEnd,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null &&
                    pickerState.selectedEndDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis
                    if (start != null && end != null) {
                        onConfirm(fromUtcMillis(start), fromUtcMillis(end))
                    }
                },
            ) {
                Text(tr("确定", "OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(horizontal = 8.dp),
            title = {
                Text(
                    text = tr("选择统计范围", "Select date range"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                )
            },
            showModeToggle = false,
        )
    }
}

/** `YYYY-MM-DD` → 当日 UTC 零点毫秒（DatePicker 的日期语义）。 */
private fun toUtcMillis(isoDate: String): Long? = Dates.parse(isoDate)
    ?.atStartOfDay(ZoneOffset.UTC)
    ?.toInstant()
    ?.toEpochMilli()

/** DatePicker 返回的 UTC 零点毫秒 → `YYYY-MM-DD`。 */
private fun fromUtcMillis(millis: Long): String =
    Dates.iso(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
