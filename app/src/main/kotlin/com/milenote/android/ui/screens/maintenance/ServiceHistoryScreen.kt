package com.milenote.android.ui.screens.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.FilterMenuCheckIcon
import com.milenote.android.ui.components.FilterPill
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.theme.MileNoteDimens

/**
 * 维修保养记录页（底栏 v4：从保养页右上角「维修保养记录」图标推入）。
 *
 * 只输出内容，不含 Scaffold / 顶栏 / 底栏；顶栏「维修保养记录」+ 返回箭头由导航壳提供。
 * 结构：副标题「N 次 · 当前里程」+ 右上角筛选胶囊（全部 / 维修 / 保养 / 汽车美容）+ 扁平记录列表；
 * 点记录打开编辑器（[MileNoteViewModel.openEditor]），中文/英文文案与旧保养页一致。
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
    val km = remember(ledger) { currentKm(ledger) }
    val history = remember(ledger.records) {
        ledger.records
            .filter { it.category in SERVICE_RECORD_CATEGORIES }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    var historyFilter by rememberSaveable { mutableStateOf("全部") }
    // 记录筛选：旧分类「维修保养」先归一成「保养」再比较，「全部」不过滤。
    val filteredHistory = remember(history, historyFilter) {
        if (historyFilter == "全部") {
            history
        } else {
            history.filter { Categories.normalize(it.category) == historyFilter }
        }
    }

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
        item(key = "summary") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = tr(
                        "%d 次 · 当前 %s",
                        "%d entries · current %s",
                        filteredHistory.size,
                        distanceText(km),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                ServiceHistoryFilter(
                    filter = historyFilter,
                    ev = ev,
                    onSelect = { historyFilter = it },
                )
            }
        }
        item(key = "history") {
            ServiceHistoryFlatList(
                records = filteredHistory,
                ledger = ledger,
                filterActive = historyFilter != "全部" && history.isNotEmpty(),
                onEdit = { vm.openEditor(recordId = it.id) },
            )
        }
    }
}

/** 扁平记录列表（页内不再套卡片，分割线沿用旧保养页样式）。 */
@Composable
private fun ServiceHistoryFlatList(
    records: List<Record>,
    ledger: Ledger,
    filterActive: Boolean,
    onEdit: (Record) -> Unit,
) {
    if (records.isEmpty()) {
        Text(
            text = if (filterActive) {
                tr(
                    "没有符合条件的记录。点右上角「筛选」可切回全部。",
                    "No matching records. Tap Filter at the top right to show all.",
                )
            } else {
                tr(
                    "还没有维修保养记录。完成维修、保养或汽车美容后，记下日期、地点、里程、项目和费用。",
                    "No maintenance records yet. After a repair, service, or detailing job, log the " +
                        "date, location, mileage, items, and cost.",
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        records.forEachIndexed { index, record ->
            ServiceHistoryCard(
                record = record,
                ledger = ledger,
                onClick = { onEdit(record) },
            )
            if (index != records.lastIndex) FlatDivider()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 维修保养记录筛选
// ---------------------------------------------------------------------------------------------

/** 记录卡筛选选项（「全部」不过滤；旧分类「维修保养」先归一成「保养」再比较）。 */
private val SERVICE_HISTORY_FILTERS = listOf("全部", "维修", "保养", "汽车美容")

/** 记录页右上角筛选胶囊 + 下拉菜单（全部 / 维修 / 保养 / 汽车美容）。 */
@Composable
private fun ServiceHistoryFilter(filter: String, ev: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    FilterPill(
        label = if (filter == "全部") tr("筛选", "Filter") else DataLabels.category(filter, ev),
        expanded = expanded,
        onToggle = { expanded = true },
        onDismiss = { expanded = false },
        contentDescription = tr("筛选维修保养记录", "Filter maintenance records"),
    ) {
        SERVICE_HISTORY_FILTERS.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(if (option == "全部") tr("全部", "All") else DataLabels.category(option, ev))
                },
                trailingIcon = FilterMenuCheckIcon(selected = option == filter),
                onClick = {
                    onSelect(option)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun ServiceHistoryCard(record: Record, ledger: Ledger, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val projects = record.projects?.takeIf { it.isNotBlank() }
    // 整行可点直接进入编辑；左侧图标垂直居中，只保留日期 + 项目两行。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = tr("编辑记录", "Edit record")) { onClick() }
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryBadge(categoryName = record.category)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = buildString {
                    append(fullDateLabel(record.date))
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
            Text(
                text = projects?.let { raw ->
                    raw.split("、").joinToString(tr("、", ", ")) { DataLabels.projectFor(ledger, it) }
                } ?: DataLabels.categoryFor(ledger, record.category, ledger.vehicle.energyProfile),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
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

/** `2026-10-01` → `2026.10.01`（记录卡第一行日期）。 */
private fun fullDateLabel(isoDate: String): String = Format.dateDots(isoDate)

/** 扁平列表行的细分割线（不再使用内层卡片）。 */
@Composable
private fun FlatDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}
