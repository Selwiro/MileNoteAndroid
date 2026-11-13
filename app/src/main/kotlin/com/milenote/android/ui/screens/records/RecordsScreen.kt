package com.milenote.android.ui.screens.records

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.R
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.CategoryConfig
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.costOf
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
import com.milenote.android.ui.components.CategoryChip
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
import com.milenote.android.ui.components.SearchFilterBar
import com.milenote.android.ui.components.entriesCountLabel
import com.milenote.android.ui.components.rangeLabel
import com.milenote.android.ui.components.recordsCountLabel
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.categoryColorOf
import com.milenote.android.ui.theme.moneySmall
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * 费用明细页（底栏 v4：原「记录 / 费用统计」页取消，本页由总览右上角图标推入）。
 *
 * 搜索、时间 / 分类筛选、合计、按月分组列表；点行打开编辑。
 * **批量操作**：长按任意一行（或顶栏「选择」）进入多选，可批量改分类 / 删除；
 * 删除后底部提示带「撤销」，按原位插回记录并恢复受影响的保养提醒。
 * 本页自带顶栏（多选时要换标题与动作），外层只提供 Snackbar 宿主与系统栏 inset。
 *
 * @param vm 全局 ViewModel（记录读写、期间状态、编辑器）。
 * @param onBack 返回上一页（系统返回 / 顶栏返回箭头）。
 */
@Composable
fun RecordsScreen(
    vm: MileNoteViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        Column(modifier = modifier.fillMaxSize()) {
            RecordsTopBar(
                selecting = false,
                selectedCount = 0,
                visibleCount = 0,
                allSelected = false,
                selectable = false,
                onBack = onBack,
                onCancelSelection = {},
                onStartSelection = {},
                onToggleSelectAll = {},
            )
            LoadingState(
                label = tr("正在读取本机账本…", "Reading the local ledger…"),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
            )
        }
        return
    }
    RecordsContent(
        ledger = ledger,
        filter = state.listFilters[ListFilterPages.RECORDS] ?: ListFilterState(),
        onFilterChange = { vm.setListFilter(ListFilterPages.RECORDS, it) },
        onAddRecord = { vm.openEditor() },
        onOpenRecord = { recordId -> vm.openEditor(recordId) },
        onDeleteRecords = vm::deleteRecords,
        onChangeCategory = vm::changeRecordsCategory,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RecordsContent(
    ledger: Ledger,
    filter: ListFilterState,
    onFilterChange: (ListFilterState) -> Unit,
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onDeleteRecords: (List<Record>) -> Unit,
    onChangeCategory: (List<Record>, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 进入明细页 / 从编辑页返回时：不自动聚焦搜索框，主动清焦点并收起输入法。
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val today = remember { Dates.today() }
    val filtered = remember(ledger, filter, today) {
        filterRecords(
            ledger = ledger,
            query = filter.query,
            categories = filter.categories,
            period = filter.period,
            customRange = filter.customRange,
            today = today,
        )
    }
    val groups = remember(filtered) { filtered.groupBy { it.date.take(7) } }
    val filteredTotal = remember(filtered, ledger) { filtered.sumOf { costOf(it, ledger) } }
    val hasAnyRecord = ledger.records.isNotEmpty()

    // ---------- 多选 ----------
    // 选中集合按「当前筛选结果」收敛：筛选一变，被藏起来的记录自动退出选择，
    // 「已选 N 条」与实际会改动的记录始终一致。
    var selecting by remember(ledger.id) { mutableStateOf(false) }
    var selectedIds by remember(ledger.id) { mutableStateOf<Set<String>>(emptySet()) }
    val visibleIds = remember(filtered) { filtered.mapTo(LinkedHashSet()) { it.id } }
    LaunchedEffect(visibleIds) { selectedIds = pruneSelection(selectedIds, visibleIds) }
    val selection = remember(selectedIds, visibleIds) { pruneSelection(selectedIds, visibleIds) }
    val selectedRecords = remember(filtered, selection) { filtered.filter { it.id in selection } }
    val selectedTotal = remember(selectedRecords, ledger) { selectedRecords.sumOf { costOf(it, ledger) } }
    val allSelected = isAllSelected(selection, visibleIds)

    fun startSelection(recordId: String? = null) {
        selecting = true
        selectedIds = if (recordId == null) emptySet() else setOf(recordId)
    }

    fun exitSelection() {
        selecting = false
        selectedIds = emptySet()
    }

    fun toggle(recordId: String) {
        selectedIds = toggleSelection(selectedIds, recordId)
    }

    // 多选时系统返回先退出多选，而不是离开页面。
    BackHandler(enabled = selecting) { exitSelection() }

    var showCategorySheet by remember { mutableStateOf(false) }

    // 分类选项：账本实际出现的分类（归一后），内置 9 类在前、自定义在后。
    val categoryOptions = remember(ledger) {
        val present = ledger.records
            .map { Categories.normalize(it.category) }
            .filter { it.isNotEmpty() }
            .toSet()
        Categories.ALL.filter { it in present } +
            present.filter { it !in Categories.ALL }.sorted()
    }
    // 分类条数：按「时间 + 搜索」算、不按已选分类；与时间筛选联动，避免显示不实的 0。
    val categoryCounts = remember(ledger, filter.query, filter.period, filter.customRange, today) {
        filterRecords(
            ledger = ledger,
            query = filter.query,
            categories = emptySet(),
            period = filter.period,
            customRange = filter.customRange,
            today = today,
        ).groupingBy { Categories.normalize(it.category) }.eachCount()
    }
    val activeChips = remember(filter, categoryOptions, ledger) {
        buildList {
            if (filter.hasTimeFilter) {
                add(ActiveFilterChip("time", rangeLabel(filter.period, filter.customRange)))
            }
            categoryOptions.filter { it in filter.categories }.forEach { name ->
                add(
                    ActiveFilterChip(
                        key = "cat:$name",
                        label = DataLabels.categoryFor(
                            ledger,
                            name,
                            ledger.vehicle.energyProfile,
                        ),
                    ),
                )
            }
        }
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(filter) }
    var showRangePicker by remember { mutableStateOf(false) }
    val draftFiltered = remember(ledger, draft, today) {
        filterRecords(
            ledger = ledger,
            query = draft.query,
            categories = draft.categories,
            period = draft.period,
            customRange = draft.customRange,
            today = today,
        )
    }
    // 面板里的分类条数按「时间 + 搜索」算，不按已选分类，切换勾选时数字不会互相归零。
    val draftBase = remember(ledger, draft.query, draft.period, draft.customRange, today) {
        filterRecords(
            ledger = ledger,
            query = draft.query,
            categories = emptySet(),
            period = draft.period,
            customRange = draft.customRange,
            today = today,
        )
    }
    val draftCounts = remember(draftBase) {
        draftBase.groupingBy { Categories.normalize(it.category) }.eachCount()
    }

    fun openSheet() {
        draft = filter
        sheetOpen = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        RecordsTopBar(
            selecting = selecting,
            selectedCount = selection.size,
            visibleCount = visibleIds.size,
            allSelected = allSelected,
            onBack = onBack,
            onCancelSelection = { exitSelection() },
            onStartSelection = { startSelection() },
            onToggleSelectAll = {
                selectedIds = toggleSelectAll(selectedIds, visibleIds)
            },
            selectable = true,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = MileNoteDimens.CardGap,
                    bottom = MileNoteDimens.PageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
            ) {
                item(key = "filters") {
                    Column(
                        modifier = Modifier.padding(horizontal = MileNoteDimens.PageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchFilterBar(
                            query = filter.query,
                            onQueryChange = { onFilterChange(filter.copy(query = it)) },
                            placeholder = tr(
                                "搜索备注、分类、金额或里程…",
                                "Search notes, categories, amounts or mileage…",
                            ),
                            filterCount = filter.activeCount,
                            onOpenFilter = { openSheet() },
                        )
                        ActiveFilterChips(
                            chips = activeChips,
                            onRemove = { key ->
                                when {
                                    key == "time" -> onFilterChange(
                                        filter.copy(period = Period.ALL, customRange = null),
                                    )
                                    key.startsWith("cat:") -> onFilterChange(
                                        filter.copy(categories = filter.categories - key.removePrefix("cat:")),
                                    )
                                }
                            },
                            onClearAll = { onFilterChange(filter.cleared()) },
                        )
                    }
                }
                item(key = "summary") {
                    RecordsSummaryRow(count = filtered.size, total = filteredTotal)
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        if (hasAnyRecord) {
                            EmptyState(
                                title = tr("没有匹配的记录", "No matching records"),
                                description = tr(
                                    "试试更换关键词，或清除时间与分类筛选。",
                                    "Try a different keyword, or clear the date and category filters.",
                                ),
                                icon = MileNoteIcons.Search,
                                actionLabel = tr("清除全部筛选", "Clear all filters"),
                                onAction = { onFilterChange(filter.cleared()) },
                            )
                        } else {
                            EmptyState(
                                title = tr("还没有费用记录", "No expense records yet"),
                                description = tr("记下一笔支出，让用车成本一目了然。", "Add an expense to see your running costs at a glance."),
                                illustration = painterResource(R.drawable.ill_empty_records),
                                actionLabel = tr("记一笔", "Add entry"),
                                onAction = onAddRecord,
                            )
                        }
                    }
                } else {
                    groups.forEach { (month, records) ->
                        stickyHeader(key = "month-$month") {
                            MonthHeader(month = month, records = records, ledger = ledger)
                        }
                        items(items = records, key = { "record-${it.id}" }) { record ->
                            RecordRow(
                                record = record,
                                ledger = ledger,
                                selecting = selecting,
                                selected = record.id in selection,
                                onClick = {
                                    if (selecting) toggle(record.id) else onOpenRecord(record.id)
                                },
                                onLongClick = {
                                    if (selecting) toggle(record.id) else startSelection(record.id)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (selecting) {
            RecordsSelectionBar(
                count = selection.size,
                total = selectedTotal,
                onCategory = { showCategorySheet = true },
                onDelete = {
                    val picked = selectedRecords
                    exitSelection()
                    onDeleteRecords(picked)
                },
            )
        }
    }

    if (sheetOpen) {
        FilterSheet(
            resultCount = draftFiltered.size,
            onDismiss = { sheetOpen = false },
            onReset = { draft = draft.cleared() },
            onApply = {
                onFilterChange(draft)
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
            FilterSection(
                title = tr("分类", "Category"),
                trailing = if (draft.categories.isEmpty()) {
                    tr("全部分类", "All categories")
                } else {
                    tr("已选 %d 项", "%d selected", draft.categories.size)
                },
            )
            FilterOptionList(
                options = listOf(
                    FilterOption(
                        key = "__all__",
                        label = tr("全部分类", "All categories"),
                        count = draftBase.size,
                    ),
                ),
                selected = if (draft.categories.isEmpty()) setOf("__all__") else emptySet(),
                onToggle = { draft = draft.copy(categories = emptySet()) },
            )
            FilterOptionList(
                options = categoryOptions.map { name ->
                    FilterOption(
                        key = name,
                        label = DataLabels.categoryFor(ledger, name, ledger.vehicle.energyProfile),
                        count = draftCounts[name] ?: 0,
                        color = categoryColorOf(name),
                    )
                },
                selected = draft.categories,
                onToggle = { name ->
                    draft = draft.copy(
                        categories = if (name in draft.categories) {
                            draft.categories - name
                        } else {
                            draft.categories + name
                        },
                    )
                },
            )
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

    if (showCategorySheet && selecting) {
        BatchCategorySheet(
            ledger = ledger,
            count = selection.size,
            onDismiss = { showCategorySheet = false },
            onPick = { name ->
                val picked = selectedRecords
                showCategorySheet = false
                exitSelection()
                onChangeCategory(picked, name)
            },
        )
    }
}

/** 顶栏：普通模式 = 返回 + 标题 +「选择」；多选模式 = 关闭 +「已选 N 条」+ 全选。 */
@Composable
private fun RecordsTopBar(
    selecting: Boolean,
    selectedCount: Int,
    visibleCount: Int,
    allSelected: Boolean,
    selectable: Boolean = true,
    onBack: () -> Unit,
    onCancelSelection: () -> Unit,
    onStartSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (selecting) onCancelSelection else onBack) {
            Icon(
                imageVector = if (selecting) MileNoteIcons.Close else MileNoteIcons.Back,
                contentDescription = if (selecting) tr("退出多选", "Exit selection") else tr("返回", "Back"),
            )
        }
        Text(
            text = when {
                selecting && selectedCount == 0 -> tr("选择记录", "Select records")
                selecting -> tr("已选 %d 条", "%d selected", selectedCount)
                else -> tr("费用明细", "Expense details")
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selecting) {
            TextButton(onClick = onToggleSelectAll, enabled = visibleCount > 0) {
                Text(if (allSelected) tr("取消全选", "Deselect all") else tr("全选", "Select all"))
            }
        } else if (selectable) {
            TextButton(onClick = onStartSelection) {
                Text(tr("选择", "Select"))
            }
        }
    }
}

/** 多选底部操作栏：已选条数 + 合计，右侧「改分类 / 删除」。 */
@Composable
private fun RecordsSelectionBar(
    count: Int,
    total: Double,
    onCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalMileNoteDarkTheme.current
    Surface(
        color = if (dark) scheme.surfaceContainer else Color.White,
        shadowElevation = 6.dp,
    ) {
        Column {
            HorizontalDivider(
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = if (dark) 0.28f else 0.45f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = tr("已选合计", "Selected total"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = "${Units.currencySymbol} ${Format.money(total)}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onCategory,
                    enabled = count > 0,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(MileNoteIcons.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(tr("改分类", "Category"))
                }
                Button(
                    onClick = onDelete,
                    enabled = count > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(MileNoteIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(tr("删除", "Delete"))
                }
            }
        }
    }
}

/** 批量改分类底部面板：只列可选分类（隐藏项不出现），点一下即应用。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BatchCategorySheet(
    ledger: Ledger,
    count: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val options = remember(ledger) { CategoryConfig.pickOptions(ledger) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("批量改分类", "Change category"), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = tr(
                    "选中的 %d 条记录会改成下面的分类，金额、日期、备注等其他字段不变。",
                    "The %d selected records will move to the category you pick; amounts, dates and notes stay unchanged.",
                    count,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { name ->
                    CategoryChip(
                        name = name,
                        selected = false,
                        onClick = { onPick(name) },
                        ev = ledger.isEv,
                    )
                }
            }
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        }
    }
}

/** 结果汇总：「共 N 条 · 合计金额」。 */
@Composable
private fun RecordsSummaryRow(count: Int, total: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MileNoteDimens.PageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = recordsCountLabel(count),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = tr("筛选合计 %s %s", "Filtered total %s %s", Units.currencySymbol, Format.money(total)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 月份粘性头：浅色摘要块（`2026 年 11 月` + N 笔记录 + 当月合计）。 */
@Composable
private fun MonthHeader(month: String, records: List<Record>, ledger: Ledger) {
    val scheme = MaterialTheme.colorScheme
    val total = records.sumOf { costOf(it, ledger) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .padding(horizontal = MileNoteDimens.PageHorizontal, vertical = 6.dp),
    ) {
        Surface(
            shape = MileNoteShapes.medium,
            color = scheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = monthLabel(month),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = entriesCountLabel(records.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${Units.currencySymbol} ${Format.money(total)}",
                    style = MaterialTheme.typography.moneySmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 单条记录行：点击编辑 / 多选时切换选中；长按进入多选。 */
@Composable
private fun RecordRow(
    record: Record,
    ledger: Ledger,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 列表使用长按进入多选而不是横向滑动：横向手势留给底栏页面的左右滑动切换。
    RecordRowCard(
        record = record,
        ledger = ledger,
        onClick = onClick,
        onLongClick = onLongClick,
        selecting = selecting,
        selected = selected,
        modifier = Modifier.padding(horizontal = MileNoteDimens.PageHorizontal),
    )
}

/**
 * 记录行：左侧分类色竖轨（多选时换成勾选圆点）+ 圆形分类图标（12% 分类色底）+ 分类名/备注两行，
 * 右侧强调金额 + 里程/来源小字。
 */
@Composable
private fun RecordRowCard(
    record: Record,
    ledger: Ledger,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalMileNoteDarkTheme.current
    val semantic = LocalMileNoteSemanticColors.current
    val accent = categoryColorOf(record.category)
    val cost = costOf(record, ledger)
    val baseColor = if (dark) scheme.surfaceContainerLow else Color.White
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MileNoteShapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MileNoteShapes.large,
        color = if (selected) {
            scheme.primary.copy(alpha = if (dark) 0.18f else 0.08f)
        } else {
            baseColor
        },
        border = if (selected) {
            BorderStroke(1.5.dp, scheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
        shadowElevation = if (dark || selected) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MileNoteDimens.RowMinHeightTwoLine)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selecting) {
                SelectionMark(checked = selected)
            } else {
                // 3dp 分类色竖轨
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            CategoryBadge(categoryName = record.category, size = 40.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = recordTitle(record, ledger),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = recordSubtitle(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (record.amount > 0.0) {
                    MoneyText(
                        amount = Format.money(record.amount),
                        size = MoneySize.SMALL,
                        unit = Units.money,
                        color = if (cost > 0.0) scheme.onSurface else scheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                record.odometer?.let { odometer ->
                    Text(
                        text = Format.km(odometer, Units.distanceUnit),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (record.amount > 0.0 && cost <= 0.0) {
                    Text(
                        text = tr("未计入费用", "Not counted as expense"),
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.warning.content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 多选勾选圆点：未选中为空心圈，选中为主色实心 + 白色对勾（尺寸与 3dp 竖轨一致，不改变行高）。 */
@Composable
private fun SelectionMark(checked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) scheme.primary else Color.Transparent)
            .then(
                if (checked) {
                    Modifier
                } else {
                    Modifier.border(1.5.dp, scheme.outlineVariant, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = MileNoteIcons.Check,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 记录行标题：手填油耗/能耗单独显示（与 Web 记录表一致）；
 * 其余分类走 `DataLabels.categoryFor`（用户改名 → 纯电「电费」/ 插混「能源」→ 英文表）。
 */
private fun recordTitle(record: Record, ledger: Ledger): String =
    if (record.kind == "manual") {
        if (ledger.isEv) tr("手填能耗", "Manual energy entry") else tr("手填油耗", "Manual fuel entry")
    } else {
        DataLabels.categoryFor(ledger, Categories.normalize(record.category), ledger.vehicle.energyProfile)
    }

/** 记录行副标题：日期 + 备注（不再展示记录来源）。 */
private fun recordSubtitle(record: Record): String {
    val date = Format.dateDots(record.date)
    val note = record.note.trim()
    return if (note.isNotEmpty()) "$date · $note" else date
}

/** 搜索 / 分类 / 期间三重筛选，并按日期倒序、同日里程倒序排列。 */
internal fun filterRecords(
    ledger: Ledger,
    query: String,
    categories: Set<String> = emptySet(),
    period: Period = Period.ALL,
    customRange: DateRange? = null,
    today: LocalDate,
): List<Record> {
    val range = customRange ?: Dates.range(period, today)
    val keyword = query.trim()
    val selectedCategories = categories
        .map { Categories.normalize(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    return ledger.records
        .filter { record ->
            val inPeriod = (range.from.isEmpty() || record.date >= range.from) &&
                record.date <= range.to
            val normalizedCategory = Categories.normalize(record.category)
            val matchCategory = selectedCategories.isEmpty() ||
                normalizedCategory in selectedCategories
            val matchSearch = keyword.isEmpty() || buildString {
                append(record.note).append(' ')
                append(record.source).append(' ')
                append(record.date).append(' ')
                append(Dates.displayDots(record.date)).append(' ')
                append(record.category).append(' ')
                append(DataLabels.categoryFor(ledger, record.category, ledger.vehicle.energyProfile)).append(' ')
                append(record.location.orEmpty()).append(' ')
                record.projects?.let { raw ->
                    append(raw).append(' ')
                    raw.split("、").forEach { append(DataLabels.projectFor(ledger, it)).append(' ') }
                }
                // 金额与里程：同时给「格式化值」和「原始数字」，搜索 680 / 680.00 / 42,000 都能命中。
                append(Format.money(record.amount)).append(' ')
                append(Numbers.jsString(record.amount)).append(' ')
                record.odometer?.let {
                    append(Format.km(it)).append(' ')
                    append(Numbers.jsString(it)).append(' ')
                }
            }.contains(keyword, ignoreCase = true)
            inPeriod && matchCategory && matchSearch
        }
        .sortedWith(
            compareByDescending<Record> { it.date }
                .thenByDescending { it.odometer ?: 0.0 },
        )
}

/** `2026-11` → `2026 年 11 月`；空 / 非法日期 → `未设置日期`。 */
private fun monthLabel(month: String): String {
    if (month.length < 7) return tr("未设置日期", "No date")
    val year = month.substring(0, 4)
    val monthNumber = month.substring(5, 7).toIntOrNull() ?: return tr("未设置日期", "No date")
    val monthEn = Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return tr("$year 年 $monthNumber 月", "$monthEn $year")
}
