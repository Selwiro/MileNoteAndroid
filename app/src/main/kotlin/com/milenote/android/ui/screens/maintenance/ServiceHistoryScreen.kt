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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.FilterMenuCheckIcon
import com.milenote.android.ui.components.FilterPill
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
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
        if (filteredHistory.isEmpty()) {
            item(key = "empty") {
                if (historyFilter != "全部" && history.isNotEmpty()) {
                    EmptyState(
                        icon = MileNoteIcons.FilterList,
                        title = tr("没有符合条件的记录", "No matching records"),
                        description = tr(
                            "点右上角「筛选」可切回全部。",
                            "Tap Filter at the top right to show all.",
                        ),
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
