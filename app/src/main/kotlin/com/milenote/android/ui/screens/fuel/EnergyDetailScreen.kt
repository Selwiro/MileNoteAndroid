package com.milenote.android.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.EnergyKind
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.energyRateByRecord
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.EnergyTypeFilter
import com.milenote.android.ui.ListFilterPages
import com.milenote.android.ui.ListFilterState
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.ActiveFilterChip
import com.milenote.android.ui.components.ActiveFilterChips
import com.milenote.android.ui.components.CustomRangePickerDialog
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.FilterOption
import com.milenote.android.ui.components.FilterOptionList
import com.milenote.android.ui.components.FilterSection
import com.milenote.android.ui.components.FilterSheet
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.RangePresetChips
import com.milenote.android.ui.components.recordsCountLabel
import com.milenote.android.ui.components.SearchFilterBar
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.components.rangeLabel
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteDimens
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * 能耗明细推入页（油耗 = 油耗明细；纯电 / 插混 = 能耗明细）。
 *
 * 内容与拆分前 `FuelScreen` 的「明细」页签 1:1：紧凑搜索框、时间筛选、汇总、按月份分组的
 * 独立记录卡片（点卡片进编辑器）、底部可折叠「满箱法 / 满电+累计 / 双口径说明」。
 *
 * **内容-only**：不含 Scaffold / 顶栏 / 底栏；页面标题与返回箭头由外层导航壳负责
 * （返回箭头走 `popBackStack()`，insets 由外层 Scaffold 的 `innerPadding` 提供）。
 *
 * 与统计页（[FuelScreen]）的边界：本页**不含**今日油价卡、油价仓库 / 缓存、定位 launcher、
 * 自动抓价 `LaunchedEffect`、走势图表及其筛选 state——这些全部留在统计页，避免推入页
 * 触发无谓的网络与权限请求。
 *
 * 能量档位按 `ledger.vehicle.energyProfile` 在本页重新计算（不复用统计页闭包）：
 * - 燃油 / HEV：单列加油记录，显示单次油耗；
 * - 纯电：单列充电记录，不显示单次能耗（`rateUnit = null`）；
 * - 插混：加油与充电混排，每行按 `record.kwh != null` 自行分流（单位 / 口径 / 措辞随之切换），
 *   汇总拆成「加油」与「充电」两行。
 *
 * 明细时间筛选（`detailPeriod`）与概览 / 走势的筛选互相独立，默认「全部」；本轮拆页后
 * 属于本页 `rememberSaveable`，pop 出去再进来会重置为「全部」（可接受，见方案 R7）。
 *
 * @param vm 全局 ViewModel（账本状态、打开记录编辑器）。
 * @param modifier 外部修饰符（由宿主补 padding / fillMaxSize）。
 */
@Composable
fun EnergyDetailScreen(
    vm: MileNoteViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingState(label = tr("正在加载账本…", "Loading ledger…"))
        }
        return
    }

    // 能量档位在本页独立求值（统计页的闭包不外泄）：纯电 = 只看充电；插混 = 加油/充电混排双流。
    val profile = ledger.vehicle.energyProfile
    val ev = profile == EnergyProfile.ELECTRIC_ONLY
    val phev = profile == EnergyProfile.DUAL
    val today = Dates.today()

    // 明细列表使用本页独立的筛选（不影响统计页的概览 / 走势）。
    val allEnergyRecords = remember(ledger.records) {
        ledger.records
            .filter { Categories.isEnergy(it.category) }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    val filter = state.listFilters[ListFilterPages.FUEL_DETAIL] ?: ListFilterState()
    val detailRange = filter.range(today)
    // 搜索（备注 / 日期 / 金额 / 里程）+ 时间范围 + 类型（插混加油 / 充电）共同过滤列表。
    val detailRecords = remember(allEnergyRecords, filter, today) {
        filterEnergyRecords(allEnergyRecords, filter, today)
    }
    val detailGroups = remember(detailRecords) {
        detailRecords.groupBy { monthKey(it.date) }.entries.toList()
    }
    // 明细汇总的能量量：燃油累计升数，纯电累计充电度数（含未充满记录）。
    val detailEnergyAmount = remember(detailRecords, ev) {
        detailRecords.sumOf { if (ev) it.kwh ?: 0.0 else it.liters ?: 0.0 }
    }
    val detailAmount = remember(detailRecords) { detailRecords.sumOf { it.amount } }
    // 插混明细汇总两行：加油量+油费、充电量+电费。按记录字段分流，与当前档位无关。
    val detailFuelAmount = remember(detailRecords) {
        detailRecords.filter { it.kwh == null }.sumOf { it.liters ?: 0.0 }
    }
    val detailFuelCost = remember(detailRecords) {
        detailRecords.filter { it.kwh == null }.sumOf { it.amount }
    }
    val detailChargeAmount = remember(detailRecords) {
        detailRecords.filter { it.kwh != null }.sumOf { it.kwh ?: 0.0 }
    }
    val detailChargeCost = remember(detailRecords) {
        detailRecords.filter { it.kwh != null }.sumOf { it.amount }
    }
    // 每条记录对应的满箱/满电区间能耗（domain 纯函数；未加满 / 基准记录无值 → 显示「—」）。
    // 插混两条流各查各的：加油记录看满箱法、充电记录看满电法。手填能耗历史上是 L/100km，
    // 两条流都会返回同一条，合并时把燃油流放后面，让手填值按油耗解释。
    val rateByRecord = remember(ledger.records, ev, phev) {
        when {
            ev -> energyRateByRecord(ledger.records, EnergyKind.ELECTRIC)
            !phev -> energyRateByRecord(ledger.records, EnergyKind.FUEL)
            else -> energyRateByRecord(ledger.records, EnergyKind.ELECTRIC) +
                energyRateByRecord(ledger.records, EnergyKind.FUEL)
        }
    }

    val hasAnyEnergy = allEnergyRecords.isNotEmpty()
    val hasActiveFilter = filter.activeCount > 0 || filter.query.isNotBlank()
    val activeChips = remember(filter, ledger) {
        buildList {
            if (filter.hasTimeFilter) {
                add(ActiveFilterChip("time", rangeLabel(filter.period, filter.customRange)))
            }
            filter.types.forEach { type ->
                add(
                    ActiveFilterChip(
                        key = "type:$type",
                        label = if (type == EnergyTypeFilter.CHARGE) {
                            tr("充电", "Charge")
                        } else {
                            tr("加油", "Fuel")
                        },
                    ),
                )
            }
        }
    }

    var sheetOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(filter) }
    var showRangePicker by remember { mutableStateOf(false) }
    val draftFiltered = remember(allEnergyRecords, draft, today) {
        filterEnergyRecords(allEnergyRecords, draft, today)
    }
    // 类型条数按「时间 + 搜索」算，不按已选类型。
    val draftBase = remember(allEnergyRecords, draft.query, draft.period, draft.customRange, today) {
        filterEnergyRecords(allEnergyRecords, draft.copy(types = emptySet()), today)
    }

    // ---- 明细页：搜索 + 筛选面板 → 汇总 → 按月份分组的独立记录卡 ----
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
        item(key = "detailTop") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchFilterBar(
                    query = filter.query,
                    onQueryChange = { vm.setListFilter(ListFilterPages.FUEL_DETAIL, filter.copy(query = it)) },
                    placeholder = tr(
                        "搜索备注、日期、金额或里程…",
                        "Search notes, dates, amounts or mileage…",
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
                                ListFilterPages.FUEL_DETAIL,
                                filter.copy(period = Period.ALL, customRange = null),
                            )
                            key.startsWith("type:") -> vm.setListFilter(
                                ListFilterPages.FUEL_DETAIL,
                                filter.copy(types = filter.types - key.removePrefix("type:")),
                            )
                        }
                    },
                    onClearAll = { vm.setListFilter(ListFilterPages.FUEL_DETAIL, filter.cleared()) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = recordsCountLabel(detailRecords.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (phev) {
                        // 插混汇总两行：加油量/油费 与 充电量/电费 分开（币种符号前缀 + 两位小数）。
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = tr(
                                    "加油 %s %s · %s %s",
                                    "Fuel %s %s · %s %s",
                                    Format.fmt(detailFuelAmount),
                                    Units.volumeUnit,
                                    Units.currencySymbol,
                                    Format.money(detailFuelCost),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = tr(
                                    "充电 %s %s · %s %s",
                                    "Charge %s %s · %s %s",
                                    Format.fmt(detailChargeAmount),
                                    Units.energyUnit,
                                    Units.currencySymbol,
                                    Format.money(detailChargeCost),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = tr(
                                "筛选合计 %s %s · %s %s",
                                "Filtered total %s %s · %s %s",
                                Format.fmt(detailEnergyAmount),
                                if (ev) Units.energyUnit else Units.volumeUnit,
                                Units.currencySymbol,
                                Format.money(detailAmount),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (detailRecords.isEmpty()) {
            item(key = "detailEmpty") {
                EmptyState(
                    icon = MileNoteIcons.Fuel,
                    title = when {
                        !hasAnyEnergy -> if (phev) {
                            tr("还没有加油或充电记录", "No fuel or charging records yet")
                        } else if (ev) {
                            tr("还没有充电记录", "No charging records yet")
                        } else {
                            tr("还没有加油记录", "No fuel records yet")
                        }
                        phev -> tr("没有匹配的加油或充电记录", "No matching fuel or charging records")
                        else -> if (ev) {
                            tr("没有匹配的充电记录", "No matching charging records")
                        } else {
                            tr("没有匹配的加油记录", "No matching fuel records")
                        }
                    },
                    description = if (hasAnyEnergy && hasActiveFilter) {
                        tr("试试清空搜索关键词，或清除时间与类型筛选。", "Try clearing the keyword, date and type filters.")
                    } else {
                        when {
                            phev -> tr(
                                "可在底栏中间「记一笔」记录加油或充电；导入入口在 设置 → 外部导入。",
                                "Add fuel or charging from Add entry in the bottom bar; import is in Settings → Import from other apps.",
                            )
                            ev -> tr("可在底栏中间「记一笔」记录充电；导入入口在 设置 → 外部导入。", "Add charging from Add entry in the bottom bar; import is in Settings → Import from other apps.")
                            else -> tr("可在底栏中间「记一笔」记录加油；导入入口在 设置 → 外部导入。", "Add fuel from Add entry in the bottom bar; import is in Settings → Import from other apps.")
                        }
                    },
                    actionLabel = if (hasAnyEnergy && hasActiveFilter) {
                        tr("清除全部筛选", "Clear all filters")
                    } else {
                        null
                    },
                    onAction = if (hasAnyEnergy && hasActiveFilter) {
                        { vm.setListFilter(ListFilterPages.FUEL_DETAIL, filter.cleared()) }
                    } else {
                        null
                    },
                )
            }
        } else {
            detailGroups.forEach { group ->
                val month = group.key
                item(key = "detailMonth-$month") {
                    FuelMonthTitle(month = month)
                }
                itemsIndexed(
                    items = group.value,
                    key = { index, record -> "detailRecord-$month-$index-${record.id}" },
                ) { _, record ->
                    // 插混混排：每条记录按自己的能量字段决定单位与能耗口径（有 kwh 即充电行）。
                    val rowEv = if (phev) record.kwh != null else ev
                    FuelRecordCard(
                        record = record,
                        rate = rateByRecord[record.id],
                        electric = rowEv,
                        rateUnit = when {
                            phev -> if (rowEv) Units.energyConsumption else Units.consumption
                            ev -> null
                            else -> Units.consumption
                        },
                        mixed = phev,
                        onClick = { vm.openEditor(recordId = record.id) },
                    )
                }
            }
        }
        // ---- 明细底部：满箱法 / 满电+累计口径说明（与统计页共用同一张可折叠卡） ----
        item(key = "footnote") {
            FuelFootnote(ev = ev, phev = phev)
        }
    }

    if (sheetOpen) {
        FilterSheet(
            resultCount = draftFiltered.size,
            onDismiss = { sheetOpen = false },
            onReset = { draft = draft.cleared() },
            onApply = {
                vm.setListFilter(ListFilterPages.FUEL_DETAIL, draft)
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
            if (phev) {
                FilterSection(
                    title = tr("类型", "Type"),
                    trailing = if (draft.types.isEmpty()) {
                        tr("全部类型", "All types")
                    } else {
                        tr("已选 %d 项", "%d selected", draft.types.size)
                    },
                )
                FilterOptionList(
                    options = listOf(
                        FilterOption(
                            key = EnergyTypeFilter.FUEL,
                            label = tr("加油", "Fuel"),
                            count = draftBase.count { it.kwh == null },
                        ),
                        FilterOption(
                            key = EnergyTypeFilter.CHARGE,
                            label = tr("充电", "Charge"),
                            count = draftBase.count { it.kwh != null },
                        ),
                    ),
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

/** 能耗明细过滤：时间 + 关键词 +（插混）加油/充电类型。 */
private fun filterEnergyRecords(
    records: List<Record>,
    filter: ListFilterState,
    today: LocalDate,
): List<Record> {
    val range = filter.range(today)
    val query = filter.query.trim()
    val types = filter.types
    return records
        .filter { it.date >= range.from && it.date <= range.to }
        .filter { record ->
            types.isEmpty() ||
                (EnergyTypeFilter.CHARGE in types && record.kwh != null) ||
                (EnergyTypeFilter.FUEL in types && record.kwh == null)
        }
        .filter { record -> query.isEmpty() || matchesDetailQuery(record, query) }
}

// ---------------------------------------------------------------------------------------------
// 明细行 / 分组标题（从 FuelScreen 原样搬入，仅本页使用）
// ---------------------------------------------------------------------------------------------

/** 月份分组键：合法 ISO 日期取 `YYYY-MM`，异常数据归入「其他记录」。 */
private fun monthKey(date: String): String =
    if (date.length >= 7 && date[4] == '-') date.take(7) else ""

/** 明细页月份小标题（`2026 年 11 月`）。 */
@Composable
private fun FuelMonthTitle(month: String) {
    val year = month.take(4)
    val monthNumber = month.drop(5).trimStart('0').ifEmpty { "0" }
    val title = if (month.length >= 7 && month[4] == '-') {
        val monthEn = monthNumber.toIntOrNull()
            ?.let { Month.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
            ?: monthNumber
        tr("$year 年 $monthNumber 月", "$monthEn $year")
    } else {
        tr("其他记录", "Other records")
    }
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

/** `2026-10-18` → `10 月 18 日`（记录卡 / 详情统一，不显示年份与星期）。 */
private fun monthDayLabel(isoDate: String): String {
    val date = Dates.parse(isoDate)
    return if (date != null) {
        tr(
            "${date.monthValue} 月 ${date.dayOfMonth} 日",
            "${Month.of(date.monthValue).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${date.dayOfMonth}",
        )
    } else {
        Format.dateDots(isoDate)
    }
}

/**
 * 明细页搜索：备注、日期（ISO / 2026.10.18 / 10 月 18 日）、金额、里程、升数/度数。
 *
 * 金额与里程同时匹配「格式化值」和「原始数字」，与费用明细同一套口径。
 */
private fun matchesDetailQuery(record: Record, query: String): Boolean {
    val dayLabel = monthDayLabel(record.date)
    val parts = buildList {
        add(record.note)
        add(record.date)
        add(Format.dateDots(record.date))
        add(dayLabel)
        add(dayLabel.replace(" ", ""))
        add(Format.money(record.amount))
        add(Numbers.jsString(record.amount))
        record.odometer?.let {
            add(Format.fmt(it))
            add(Numbers.jsString(it))
        }
        record.liters?.let {
            add(Format.fmt(it))
            add(Numbers.jsString(it))
        }
        record.kwh?.let {
            add(Format.fmt(it))
            add(Numbers.jsString(it))
        }
    }
    return parts.any { it.contains(query, ignoreCase = true) }
}

/**
 * 明细页单条加油/充电记录：独立白色圆角卡（阴影、无内嵌卡片）。
 *
 * 燃油 / 纯电账本保持原样：燃油显示单次油耗（[rateUnit] = 油耗单位），纯电不显示单次能耗
 * （[rateUnit] = null）。插混（[mixed] = true）同一列表里两种记录混排，每行按自己的能量字段
 * 决定单位、能耗口径与「加油量 / 充电量」措辞。
 *
 * @param rate 该记录的满箱/满电法能耗（L/100km 或 kWh/100km），无值为 null → 显示「—」
 * @param electric 该行是充电记录（有 kwh）还是加油记录
 * @param rateUnit 单次能耗单位；null 时整块隐藏（纯电保留原样）
 * @param mixed 插混混排列表：加油量 / 充电量加显式前缀，其余账本沿用 `+量` 版式
 */
@Composable
private fun FuelRecordCard(
    record: Record,
    rate: Double?,
    electric: Boolean,
    rateUnit: String?,
    mixed: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalMileNoteDarkTheme.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLowest,
        contentColor = scheme.onSurface,
        border = null,
        shadowElevation = if (dark) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = monthDayLabel(record.date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val (statusLabel, statusKind) = fuelStatus(record, electric)
                StatusPill(text = statusLabel, status = statusKind)
            }
            // 燃油：油耗最大，公里数次之，升数与金额最小；
            // 纯电：单次充电不覆盖可计算里程，不显示单次能耗，只保留公里数。
            Row(verticalAlignment = Alignment.Bottom) {
                if (rateUnit != null) {
                    Text(
                        text = rate?.let { Format.money(it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = " $rateUnit",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = record.odometer?.let { tr("%s %s", "%s %s", Format.fmt(it), Units.distanceUnit) }
                        ?: tr("— %s", "— %s", Units.distanceUnit),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        mixed && electric -> record.kwh?.let {
                            tr("充电量 %s %s", "Charge %s %s", Format.fmt(it), Units.energyUnit)
                        } ?: tr("充电量 — %s", "Charge — %s", Units.energyUnit)
                        mixed -> record.liters?.let {
                            tr("加油量 %s %s", "Fuel %s %s", Format.fmt(it), Units.volumeUnit)
                        } ?: tr("加油量 — %s", "Fuel — %s", Units.volumeUnit)
                        electric -> record.kwh?.let { tr("+%s %s", "+%s %s", Format.fmt(it), Units.energyUnit) }
                            ?: tr("— %s", "— %s", Units.energyUnit)
                        else -> record.liters?.let { tr("+%s %s", "+%s %s", Format.fmt(it), Units.volumeUnit) }
                            ?: tr("— %s", "— %s", Units.volumeUnit)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${Units.currencySymbol} ${Format.money(record.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 加油/充电记录状态：已加满(充满) / 未加满(充满) / 漏记后重置 / 手填油耗(能耗)。 */
private fun fuelStatus(record: Record, ev: Boolean): Pair<String, StatusKind> = when {
    record.missed -> tr("漏记后重置", "Baseline reset after a missed entry") to StatusKind.SOON
    record.kind == "manual" -> (if (ev) tr("手填能耗", "Manual energy entry") else tr("手填油耗", "Manual fuel entry")) to StatusKind.NORMAL
    record.full -> (if (ev) tr("已充满", "Full charge") else tr("已加满", "Filled up")) to StatusKind.DONE
    else -> (if (ev) tr("未充满", "Not fully charged") else tr("未加满", "Not filled up")) to StatusKind.NORMAL
}
