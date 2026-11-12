package com.milenote.android.ui.screens.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.ListFilterPages
import com.milenote.android.ui.ListFilterState
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.ActiveFilterChip
import com.milenote.android.ui.components.ActiveFilterChips
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.CustomRangePickerDialog
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.FilterOption
import com.milenote.android.ui.components.FilterOptionList
import com.milenote.android.ui.components.FilterSection
import com.milenote.android.ui.components.FilterSheet
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.RangePresetChips
import com.milenote.android.ui.components.serviceEntriesCountLabel
import com.milenote.android.ui.components.SearchFilterBar
import com.milenote.android.ui.components.rangeLabel
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteDimens

/**
 * 维修保养记录页（底栏 v4：从保养页右上角「维修保养记录」图标推入）。
 *
 * 只输出内容，不含 Scaffold / 顶栏 / 底栏；顶栏「维修保养记录」+ 返回箭头由导航壳提供。
 * 结构：副标题「N 次 · 当前里程」+ 右上角筛选胶囊（全部 / 维修 / 保养 / 汽车美容）+ **卡片式**记录列表
 * （与费用 / 能耗明细同款：白底圆角卡 + 分类徽标 + 项目标题 + 日期/里程 + 金额，点卡片进编辑器）。
 *
 * @param vm 全局 ViewModel（账本、记录编辑器）。
 */
@Composable
fun ServiceHistoryScreen(
    vm: MileNoteViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        LoadingState(
            label = tr("正在读取本机账本…", "Reading the local ledger…"),
            modifier = modifier
                .fillMaxSize()
                .padding(top = 64.dp),
        )
        return
    }

    val ev = ledger.isEv
    val today = remember { Dates.today() }
    val km = remember(ledger) { currentKm(ledger) }
    val history = remember(ledger.records) {
        ledger.records
            .filter { it.category in SERVICE_RECORD_CATEGORIES }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    val filter = state.listFilters[ListFilterPages.SERVICE_HISTORY] ?: ListFilterState()
    val filteredHistory = remember(history, filter, today) {
        filterServiceHistory(history, filter, today)
    }
    // 类型选项从记录里取（旧分类「维修保养」先归一成「保养」），固定顺序：保养 / 维修 / 汽车美容。
    val typeOptions = remember(history) {
        val present = history.map { Categories.normalize(it.category) }.toSet()
        val preferred = listOf("保养", "维修", "汽车美容").filter { it in present }
        preferred + present.filter { it !in preferred }.sorted()
    }
    val activeChips = remember(filter, typeOptions, ledger) {
        buildList {
            if (filter.hasTimeFilter) {
                add(ActiveFilterChip("time", rangeLabel(filter.period, filter.customRange)))
            }
            typeOptions.filter { it in filter.types }.forEach { type ->
                add(
                    ActiveFilterChip(
                        key = "type:$type",
                        label = DataLabels.categoryFor(ledger, type, ledger.vehicle.energyProfile),
                    ),
                )
            }
        }
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(filter) }
    var showRangePicker by remember { mutableStateOf(false) }
    val draftFiltered = remember(history, draft, today) {
        filterServiceHistory(history, draft, today)
    }
    // 类型条数按「时间 + 搜索」算，不按已选类型。
    val draftBase = remember(history, draft.query, draft.period, draft.customRange, today) {
        filterServiceHistory(history, draft.copy(types = emptySet()), today)
    }
    val draftCounts = remember(draftBase) {
        draftBase.groupingBy { Categories.normalize(it.category) }.eachCount()
    }
    val hasActiveFilter = filter.activeCount > 0 || filter.query.isNotBlank()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MileNoteDimens.PageHorizontal,
            end = MileNoteDimens.PageHorizontal,
            top = MileNoteDimens.CardGap,
            bottom = MileNoteDimens.PageBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
    ) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchFilterBar(
                    query = filter.query,
                    onQueryChange = {
                        vm.setListFilter(
                            ListFilterPages.SERVICE_HISTORY,
                            filter.copy(query = it),
                        )
                    },
                    placeholder = tr(
                        "搜索项目、地点、备注或金额…",
                        "Search items, places, notes or amounts…",
                    ),
                    filterCount = filter.activeCount,
                    onOpenFilter = {
                        draft = filter
                        sheetOpen = true
                    },
                )
                ActiveFilterChips(
                    chips = activeChips,
                    onRemove = { key ->
                        when {
                            key == "time" -> vm.setListFilter(
                                ListFilterPages.SERVICE_HISTORY,
                                filter.copy(period = Period.ALL, customRange = null),
                            )
                            key.startsWith("type:") -> vm.setListFilter(
                                ListFilterPages.SERVICE_HISTORY,
                                filter.copy(types = filter.types - key.removePrefix("type:")),
                            )
                        }
                    },
                    onClearAll = {
                        vm.setListFilter(ListFilterPages.SERVICE_HISTORY, filter.cleared())
                    },
                )
            }
        }
        item(key = "summary") {
            Text(
                text = tr(
                    "%s · 当前 %s",
                    "%s · current %s",
                    serviceEntriesCountLabel(filteredHistory.size),
                    distanceText(km),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (filteredHistory.isEmpty()) {
            item(key = "empty") {
                if (history.isNotEmpty() && hasActiveFilter) {
                    EmptyState(
                        icon = MileNoteIcons.FilterList,
                        title = tr("没有符合条件的记录", "No matching records"),
                        description = tr(
                            "清除筛选后可看到全部维修保养记录。",
                            "Clear the filters to see all maintenance records.",
                        ),
                        actionLabel = tr("清除全部筛选", "Clear all filters"),
                        onAction = {
                            vm.setListFilter(ListFilterPages.SERVICE_HISTORY, filter.cleared())
                        },
                    )
                } else {
                    EmptyState(
                        icon = MileNoteIcons.Maintenance,
                        title = tr("还没有维修保养记录", "No maintenance records yet"),
                        description = tr(
                            "完成维修、保养或汽车美容后，记下日期、地点、里程、项目和费用。",
                            "After a repair, service, or detailing job, log the date, location, mileage, " +
                                "items, and cost.",
                        ),
                    )
                }
            }
        } else {
            items(items = filteredHistory, key = { "history-${it.id}" }) { record ->
                ServiceHistoryCard(
                    record = record,
                    ledger = ledger,
                    onClick = { vm.openEditor(recordId = record.id) },
                )
            }
        }
    }

    if (sheetOpen) {
        FilterSheet(
            resultCount = draftFiltered.size,
            onDismiss = { sheetOpen = false },
            onReset = { draft = draft.cleared() },
            onApply = {
                vm.setListFilter(ListFilterPages.SERVICE_HISTORY, draft)
                sheetOpen = false
            },
        ) {
            FilterSection(title = tr("时间", "Date"))
            RangePresetChips(
                period = draft.period,
                customRange = draft.customRange,
                onSelectPeriod = { draft = draft.copy(period = it, customRange = null) },
                onSelectCustom = { showRangePicker = true },
            )
            if (typeOptions.isNotEmpty()) {
                FilterSection(
                    title = tr("类型", "Type"),
                    trailing = if (draft.types.isEmpty()) {
                        tr("全部类型", "All types")
                    } else {
                        tr("已选 %d 项", "%d selected", draft.types.size)
                    },
                )
                FilterOptionList(
                    options = typeOptions.map { type ->
                        FilterOption(
                            key = type,
                            label = DataLabels.categoryFor(ledger, type, ledger.vehicle.energyProfile),
                            count = draftCounts[type] ?: 0,
                        )
                    },
                    selected = draft.types,
                    onToggle = { type ->
                        draft = draft.copy(
                            types = if (type in draft.types) {
                                draft.types - type
                            } else {
                                draft.types + type
                            },
                        )
                    },
                )
            }
        }
    }

    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = draft.customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                draft = draft.copy(customRange = DateRange(from, to))
                showRangePicker = false
            },
        )
    }
}

/** 维修保养记录过滤：时间 + 类型 + 关键词（项目 / 地点 / 备注 / 金额 / 里程 / 日期）。 */
private fun filterServiceHistory(
    records: List<Record>,
    filter: ListFilterState,
    today: java.time.LocalDate,
): List<Record> {
    val range = filter.range(today)
    val query = filter.query.trim()
    val types = filter.types.map { Categories.normalize(it) }.toSet()
    return records
        .filter { it.date >= range.from && it.date <= range.to }
        .filter { record ->
            types.isEmpty() || Categories.normalize(record.category) in types
        }
        .filter { record -> query.isEmpty() || matchesServiceQuery(record, query) }
}

/** 维修保养记录搜索：项目、地点、备注、金额、里程、日期。 */
private fun matchesServiceQuery(record: Record, query: String): Boolean {
    val parts = buildList {
        add(record.note)
        add(record.location.orEmpty())
        add(record.projects.orEmpty())
        add(record.date)
        add(Format.dateDots(record.date))
        add(Format.money(record.amount))
        add(Numbers.jsString(record.amount))
        record.odometer?.let {
            add(Format.fmt(it))
            add(Numbers.jsString(it))
        }
    }
    return parts.any { it.contains(query, ignoreCase = true) }
}

/** 卡片式记录（与费用 / 能耗明细同款：白底圆角卡、分类徽标 + 标题两行 + 金额）。 */
@Composable
private fun ServiceHistoryCard(record: Record, ledger: Ledger, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalMileNoteDarkTheme.current
    val projects = record.projects?.takeIf { it.isNotBlank() }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (dark) scheme.surfaceContainerLow else Color.White,
        shadowElevation = if (dark) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryBadge(categoryName = record.category, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = projects?.let { raw ->
                        raw.split("、").joinToString(tr("、", ", ")) { DataLabels.projectFor(ledger, it) }
                    } ?: DataLabels.categoryFor(ledger, record.category, ledger.vehicle.energyProfile),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(Format.dateDots(record.date))
                        record.odometer?.let {
                            append(" · ")
                            append(distanceText(it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MoneyText(
                amount = Format.money(record.amount),
                size = MoneySize.MEDIUM,
                unit = Units.money,
            )
        }
    }
}
