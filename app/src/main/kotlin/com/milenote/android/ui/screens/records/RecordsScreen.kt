package com.milenote.android.ui.screens.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.R
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.calc.splitProjects
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.UiState
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.SecondaryTabs
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.screens.dashboard.CompositionCard
import com.milenote.android.ui.screens.dashboard.ExpenseTrendCard
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.categoryColorOf
import com.milenote.android.ui.theme.moneySmall
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 费用统计页：固定「统计 / 明细」页签。
 *
 * - 统计：支出趋势 + 费用构成；
 * - 明细：搜索、时间/分类筛选、合计、按月分组列表；
 * - 点行打开编辑，长按删除；底栏高度已由外层 Scaffold 预留。
 *
 * @param vm 全局 ViewModel（记录读写、期间状态、编辑器）。
 * @param onNavigate 路由跳转（当前记录页无二级入口；导出统一在设置页，保留参数以稳定导航契约）。
 */
@Composable
fun RecordsScreen(
    vm: MileNoteViewModel,
    onNavigate: (String) -> Unit,
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
    RecordsContent(
        state = state,
        ledger = ledger,
        onSelectPeriod = vm::setPeriod,
        onSelectCustomRange = { from, to -> vm.setCustomRange(from, to) },
        onAddRecord = { vm.openEditor() },
        onOpenRecord = { recordId -> vm.openEditor(recordId) },
        onDeleteRecord = vm::deleteRecord,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordsContent(
    state: UiState,
    ledger: Ledger,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
    onAddRecord: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onDeleteRecord: (Record) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    // 用空串表示「全部分类」，避免 rememberSaveable 处理 null 的兼容问题。
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var lastCarId by rememberSaveable { mutableStateOf(ledger.id) }
    // 动力类型：纯电把「油费」显示为「电费」/ 插混显示「能源」，手填历史记录叫「手填能耗」。
    val ev = ledger.isEv

    // 进入记录页 / 从编辑页或其它 tab 返回时：不自动聚焦搜索框，主动清焦点并收起输入法。
    // 只有用户点击搜索框（或其它输入框）时才由系统弹出键盘。
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    // 切换车辆时清空搜索与筛选（与 Web 一致）。
    LaunchedEffect(ledger.id) {
        if (ledger.id != lastCarId) {
            lastCarId = ledger.id
            query = ""
            categoryFilter = ""
        }
    }

    val today = remember { Dates.today() }
    val categoryOptions = remember(ledger) { Categories.options(ledger) }
    val filtered = remember(ledger, query, categoryFilter, state.period, today) {
        filterRecords(
            ledger = ledger,
            query = query,
            category = categoryFilter.ifEmpty { null },
            period = state.period,
            today = today,
        )
    }
    val groups = remember(filtered) { filtered.groupBy { it.date.take(7) } }
    val filteredTotal = remember(filtered, ledger) { filtered.sumOf { costOf(it, ledger) } }
    val hasAnyRecord = ledger.records.isNotEmpty()
    val feeRange = remember(state.period, state.customRange, today) { state.effectiveRange(today) }
    val feeStats = remember(ledger, feeRange) { stats(ledger, feeRange.from, feeRange.to) }

    LaunchedEffect(selectedTab) {
        listState.scrollToItem(0)
    }

    // 页签固定在页面顶部（不进 LazyColumn），三页共用 SecondaryTabs 版式。
    Column(modifier = modifier.fillMaxSize()) {
        SecondaryTabs(
            labels = listOf(tr("统计", "Statistics"), tr("明细", "Records")),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // 水平留白仍由各 item 自带（月份 stickyHeader 需要通栏），这里只控制上下。
            contentPadding = PaddingValues(
                top = MileNoteDimens.CardGap,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
        ) {
        if (selectedTab == 0) {
            if (hasAnyRecord) {
                item(key = "fee-trend") {
                    Box(modifier = Modifier.padding(horizontal = MileNoteDimens.PageHorizontal)) {
                        ExpenseTrendCard(ledger = ledger, today = today)
                    }
                }
                item(key = "fee-breakdown") {
                    Box(modifier = Modifier.padding(horizontal = MileNoteDimens.PageHorizontal)) {
                        CompositionCard(
                            period = state.period,
                            customRange = state.customRange,
                            statsResult = feeStats,
                            ev = ev,
                            onSelectPeriod = onSelectPeriod,
                            onSelectCustomRange = onSelectCustomRange,
                        )
                    }
                }
            } else {
                item(key = "stats-empty") {
                    EmptyState(
                        title = tr("还没有费用统计", "No analytics yet"),
                        description = tr("记下一笔支出后，这里会展示支出趋势与费用构成。", "Add an expense and your trends and breakdown will appear here."),
                        illustration = painterResource(R.drawable.ill_empty_records),
                        actionLabel = tr("记一笔", "Add entry"),
                        onAction = onAddRecord,
                    )
                }
            }
        } else {
        item(key = "filters") {
            RecordsSearchFilterRow(
                query = query,
                onQueryChange = { query = it },
                period = state.period,
                category = categoryFilter.ifEmpty { null },
                categories = categoryOptions,
                ledger = ledger,
                onSelectPeriod = onSelectPeriod,
                onSelectCategory = { categoryFilter = it.orEmpty() },
                onReset = {
                    // 「重置筛选」只重置时间与分类，不清空搜索关键词。
                    categoryFilter = ""
                    onSelectPeriod(Period.ALL)
                },
            )
        }
        item(key = "summary") {
            RecordsSummaryRow(count = filtered.size, total = filteredTotal)
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                if (hasAnyRecord) {
                    EmptyState(
                        title = tr("没有匹配的记录", "No matching records"),
                        description = tr("试试更换关键词，或清除分类与时间筛选。", "Try a different keyword, or clear the category and date filters."),
                        icon = MileNoteIcons.Search,
                        actionLabel = tr("清除筛选", "Clear filters"),
                        onAction = {
                            query = ""
                            categoryFilter = ""
                            onSelectPeriod(Period.ALL)
                        },
                    )
                } else {
                    EmptyState(
                        title = tr("这里还没有费用统计", "No analytics here yet"),
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
                        onClick = { onOpenRecord(record.id) },
                        onDelete = { onDeleteRecord(record) },
                    )
                }
            }
        }
        }
    }
}
}

/** 明细页顶部：左搜索（占剩余宽度）+ 右筛选胶囊（时间 / 分类 / 重置）。 */
@Composable
private fun RecordsSearchFilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    period: Period,
    category: String?,
    categories: List<String>,
    ledger: Ledger,
    onSelectPeriod: (Period) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MileNoteDimens.PageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            value = query,
            onValueChange = onQueryChange,
            label = tr("搜索备注、日期或来源…", "Search notes, dates or sources…"),
            leadingIcon = MileNoteIcons.Search,
            shape = CircleShape,
            modifier = Modifier.weight(1f),
        )
        Box {
            Surface(
                onClick = { expanded = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(MileNoteDimens.TouchTarget),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = filterPillLabel(period = period, category = category, ledger = ledger),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Icon(
                        imageVector = MileNoteIcons.ChevronDown,
                        contentDescription = tr("筛选记录", "Filter records"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                FilterMenuGroupLabel(text = tr("时间", "Date"))
                Period.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.filterLabel()) },
                        trailingIcon = filterCheckIcon(item == period),
                        onClick = {
                            onSelectPeriod(item)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                FilterMenuGroupLabel(text = tr("分类", "Category"))
                DropdownMenuItem(
                    text = { Text(tr("全部分类", "All categories")) },
                    trailingIcon = filterCheckIcon(category == null),
                    onClick = {
                        onSelectCategory(null)
                        expanded = false
                    },
                )
                categories.forEach { name ->
                    DropdownMenuItem(
                        text = {
                            Text(DataLabels.categoryFor(ledger, name, ledger.vehicle.energyProfile))
                        },
                        trailingIcon = filterCheckIcon(category == name),
                        onClick = {
                            onSelectCategory(name)
                            expanded = false
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DropdownMenuItem(
                    text = { Text(tr("重置筛选", "Reset filters")) },
                    onClick = {
                        onReset()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 下拉菜单分组标题（时间 / 分类）。 */
@Composable
private fun FilterMenuGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 菜单选中勾。 */
@Composable
private fun filterCheckIcon(selected: Boolean): (@Composable () -> Unit)? =
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

/** 筛选胶囊文案：无筛选「筛选」；否则「本月」「油费/电费」「本月 · 油费/电费」。 */
private fun filterPillLabel(period: Period, category: String?, ledger: Ledger): String {
    val parts = buildList {
        when (period) {
            Period.ALL -> Unit
            Period.MONTH -> add(tr("本月", "This month"))
            Period.YEAR -> add(tr("今年", "This year"))
        }
        category?.takeIf { it.isNotBlank() }?.let {
            add(DataLabels.categoryFor(ledger, it, ledger.vehicle.energyProfile))
        }
    }
    return if (parts.isEmpty()) tr("筛选", "Filter") else parts.joinToString(" · ")
}

/** 时间选项文案。 */
private fun Period.filterLabel(): String = when (this) {
    Period.ALL -> tr("全部", "All")
    Period.MONTH -> tr("本月", "This month")
    Period.YEAR -> tr("今年", "This year")
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
            text = tr("共 %d 条记录", "%d records", count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = tr("筛选合计 %s %s", "Filtered total %s %s", Units.currencySymbol, Format.money(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = tr("%d 笔记录", "%d entries", records.size),
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

/** 单条记录行：点击编辑、长按删除。 */
@Composable
private fun RecordRow(
    record: Record,
    ledger: Ledger,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // 列表使用长按删除而不是横向滑动删除：横向手势留给底栏页面的左右滑动切换。
    RecordRowCard(
        record = record,
        ledger = ledger,
        onClick = onClick,
        onLongClick = onDelete,
        modifier = Modifier.padding(horizontal = MileNoteDimens.PageHorizontal),
    )
}

/**
 * 记录行：左侧分类色竖轨 + 圆形分类图标（12% 分类色底）+ 分类名/备注，
 * 右侧强调金额 + 里程/来源小字；服务类记录补充项目/地点一行。
 */
@Composable
private fun RecordRowCard(
    record: Record,
    ledger: Ledger,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalMileNoteDarkTheme.current
    val semantic = LocalMileNoteSemanticColors.current
    val accent = categoryColorOf(record.category)
    val cost = costOf(record, ledger)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MileNoteShapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MileNoteShapes.large,
        color = if (dark) scheme.surfaceContainerLow else Color.White,
        border = null,
        shadowElevation = if (dark) 0.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MileNoteDimens.RowMinHeightTwoLine)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 3dp 分类色竖轨
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
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
                recordSupporting(record, ledger)?.let { supporting ->
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/** 记录行第三行：项目 / 地点（服务类记录才有）。项目显示名走 `DataLabels.projectFor`。 */
private fun recordSupporting(record: Record, ledger: Ledger): String? {
    val parts = buildList {
        val projects = record.projects.orEmpty()
        if (projects.isNotBlank()) {
            val separator = tr("、", ", ")
            add(splitProjects(projects).joinToString(separator) { DataLabels.projectFor(ledger, it) })
        }
        val location = record.location.orEmpty()
        if (location.isNotBlank()) add(location)
    }
    return parts.joinToString(" · ").ifBlank { null }
}

/** 搜索 / 分类 / 期间三重筛选，并按日期倒序、同日里程倒序排列。 */
private fun filterRecords(
    ledger: Ledger,
    query: String,
    category: String?,
    period: Period,
    today: LocalDate,
): List<Record> {
    val range = Dates.range(period, today)
    val keyword = query.trim()
    return ledger.records
        .filter { record ->
            val inPeriod = (range.from.isEmpty() || record.date >= range.from) &&
                record.date <= range.to
            val matchCategory = category == null ||
                Categories.normalize(record.category) == Categories.normalize(category)
            val matchSearch = keyword.isEmpty() || buildString {
                append(record.note).append(' ')
                append(record.source).append(' ')
                append(record.date).append(' ')
                append(record.category)
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
