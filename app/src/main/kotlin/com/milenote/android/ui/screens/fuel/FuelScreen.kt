package com.milenote.android.ui.screens.fuel

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.R
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.data.fuelprice.FuelPriceInfo
import com.milenote.android.data.fuelprice.FuelPriceRepository
import com.milenote.android.data.fuelprice.FuelLocation
import com.milenote.android.data.fuelprice.FuelRegions
import com.milenote.android.platform.location.LocationResolver
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.calc.fuelIntervals
import com.milenote.android.domain.calc.fuelRateByRecord
import com.milenote.android.domain.calc.manualFuelIntervals
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Record
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.SecondaryTabs
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.moneyMedium
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import java.time.ZoneOffset

/**
 * 油耗分析页（Web `fuelPage`，`app.mjs:45`）。
 *
 * 结构（自上→下，三块内容统一为 SectionCard 卡片风格，卡内只有标题、无副标题）：
 * 1. 「油耗概览」卡：右上角时间筛选（全部 / 本月 / 今年 / 自定义 DateRangePicker）+ 卡内 2×2 纯文字指标
 *    （平均油耗 / 油费支出 / 平均油费 / 行驶里程）；
 * 2. 「油耗走势」卡：右上角独立时间筛选（只控制走势区间）+ 平滑曲线 + 面积渐变 + 当前值 pill，点按数据点看数值；
 * - 顶部「统计 / 明细」两个固定页签；
 * - 统计：今日油价 + 油耗概览 + 油耗走势；
 * - 明细：搜索、时间筛选、汇总、按月份分组的独立记录卡片，点卡片看详情；
 * - 底部可折叠「满箱法说明」。
 *
 * 新增记录统一由底栏「记一笔」承担；导入入口在「设置 → 外部导入」。
 * 只输出内容，不含 Scaffold / 顶栏 / 底栏；底部由外层 Scaffold 预留。
 *
 * @param vm 全局 ViewModel（期间、编辑记录）。
 * @param onNavigate 顶层路由跳转（保留以兼容导航签名；导入入口已移到设置页，本页不再使用）。
 */
@Composable
fun FuelScreen(
    vm: MileNoteViewModel,
    onNavigate: (String) -> Unit,
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

    val context = LocalContext.current
    val priceRepo = remember { FuelPriceRepository() }
    val scope = rememberCoroutineScope()
    val savedLocation = remember { loadSavedFuelLocation(context) }
    var fuelLocation by remember { mutableStateOf(savedLocation ?: FuelLocation()) }
    var locationRemembered by remember { mutableStateOf(savedLocation != null) }
    var priceInfo by remember { mutableStateOf<FuelPriceInfo?>(null) }
    var priceLoading by remember { mutableStateOf(true) }

    fun refreshPrice(reLocate: Boolean) {
        scope.launch {
            priceLoading = true
            if (reLocate) {
                val resolved = LocationResolver.resolve(context)
                if (resolved != null) {
                    val next = FuelLocation(resolved.province, resolved.cityName)
                    fuelLocation = next
                    saveFuelLocation(context, next)
                    locationRemembered = true
                }
            }
            priceInfo = priceRepo.fetch(fuelLocation)
            priceLoading = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) refreshPrice(reLocate = true)
    }

    // 打开油耗页时自动刷新当前记住位置的油价；不重新定位。
    LaunchedEffect(Unit) { refreshPrice(reLocate = false) }

    val today = Dates.today()
    val period = state.period
    val customRange = state.customRange
    val range = remember(period, customRange, today) { state.effectiveRange(today) }
    val statsResult = remember(ledger, range) { stats(ledger, range.from, range.to) }
    // 概览指标 / 走势跟随顶部筛选（state.period / customRange）。
    val overviewFuelRecords = remember(statsResult) {
        statsResult.records
            .filter { Categories.normalize(it.category) == "油费" }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    val fuelCost = remember(overviewFuelRecords, ledger) { overviewFuelRecords.sumOf { costOf(it, ledger) } }
    val perKmFuel = if (statsResult.distance > 0) fuelCost / statsResult.distance else null
    // 走势卡独立时间筛选：有效区间按日期过滤，不影响概览 / 明细。
    val allIntervals = remember(ledger.records) {
        (fuelIntervals(ledger.records) + manualFuelIntervals(ledger.records))
            .sortedBy { it.date }
    }
    var chartPeriod by rememberSaveable { mutableStateOf(Period.ALL) }
    var chartCustomRange by remember { mutableStateOf<DateRange?>(null) }
    val chartRange = chartCustomRange ?: Dates.range(chartPeriod, today)
    val chartFilteredIntervals = remember(allIntervals, chartRange) {
        allIntervals.filter { interval ->
            interval.date >= chartRange.from && interval.date <= chartRange.to
        }
    }
    val chartHasExplicitRange = chartCustomRange != null || chartPeriod != Period.ALL
    // 展示筛选范围内的全部区间（不再固定截断为最近 12 个）。
    val intervals = remember(chartFilteredIntervals) { chartFilteredIntervals }
    // 明细列表使用独立的时间筛选（只影响本次列表，不影响概览 / 走势）。
    val allFuelRecords = remember(ledger.records) {
        ledger.records
            .filter { Categories.normalize(it.category) == "油费" }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    var detailPeriod by rememberSaveable { mutableStateOf(Period.ALL) }
    var detailCustomRange by remember { mutableStateOf<DateRange?>(null) }
    var detailQuery by rememberSaveable { mutableStateOf("") }
    val detailRange = detailCustomRange ?: Dates.range(detailPeriod, today)
    // 搜索（备注 / 日期）+ 时间范围共同过滤明细列表；汇总按过滤后的记录统计。
    val detailRecords = remember(allFuelRecords, detailRange, detailQuery) {
        val query = detailQuery.trim()
        allFuelRecords
            .filter { it.date >= detailRange.from && it.date <= detailRange.to }
            .filter { record ->
                query.isEmpty() || matchesDetailQuery(record, query)
            }
    }
    val detailGroups = remember(detailRecords) {
        detailRecords.groupBy { monthKey(it.date) }.entries.toList()
    }
    val detailLiters = remember(detailRecords) { detailRecords.sumOf { it.liters ?: 0.0 } }
    val detailAmount = remember(detailRecords) { detailRecords.sumOf { it.amount } }
    // 每条记录对应的满箱区间油耗（domain 纯函数；未加满 / 基准记录无值 → 显示「—」）。
    val rateByRecord = remember(ledger.records) { fuelRateByRecord(ledger.records) }
    // 点击记录卡先看详情，详情内「编辑」才进入编辑。
    var detailRecord by remember { mutableStateOf<Record?>(null) }
    var rangeMenuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var detailMenuExpanded by remember { mutableStateOf(false) }
    var detailShowRangePicker by remember { mutableStateOf(false) }
    var chartMenuExpanded by remember { mutableStateOf(false) }
    var chartShowRangePicker by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedTab) { listState.scrollToItem(0) }

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
            contentPadding = PaddingValues(
                start = MileNoteDimens.PageHorizontal,
                end = MileNoteDimens.PageHorizontal,
                top = MileNoteDimens.CardGap,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
        ) {
        if (selectedTab == 0) {
        item(key = "fuel-price") {
            FuelPriceCard(
                info = priceInfo,
                loading = priceLoading,
                fuelGrade = ledger.vehicle.fuelGrade,
                locationLabel = if (locationRemembered) {
                    DataLabels.region(fuelLocation.displayName)
                } else {
                    tr("获取当前城市油价", "Get local fuel price")
                },
                onGetCurrentCityPrice = {
                    if (LocationResolver.hasPermission(context)) {
                        refreshPrice(reLocate = true)
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                },
            )
        }
        // ---- 油耗概览（卡内 2×2 指标 + 右上角时间筛选） ----
        item(key = "overview") {
            SectionCard(
                title = tr("油耗概览", "Fuel overview"),
                action = {
                    RangeFilterPill(
                        label = rangeLabel(period, customRange),
                        expanded = rangeMenuExpanded,
                        onToggle = { rangeMenuExpanded = true },
                        onDismiss = { rangeMenuExpanded = false },
                    ) {
                        val presetSelected = customRange == null
                        DropdownMenuItem(
                            text = { Text(tr("全部", "All")) },
                            trailingIcon = checkIcon(presetSelected && period == Period.ALL),
                            onClick = {
                                vm.setPeriod(Period.ALL)
                                rangeMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("本月", "This month")) },
                            trailingIcon = checkIcon(presetSelected && period == Period.MONTH),
                            onClick = {
                                vm.setPeriod(Period.MONTH)
                                rangeMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("今年", "This year")) },
                            trailingIcon = checkIcon(presetSelected && period == Period.YEAR),
                            onClick = {
                                vm.setPeriod(Period.YEAR)
                                rangeMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("自定义…", "Custom…")) },
                            trailingIcon = checkIcon(customRange != null),
                            onClick = {
                                rangeMenuExpanded = false
                                showRangePicker = true
                            },
                        )
                    }
                },
            ) {
                FuelMetrics(
                    fuelRate = statsResult.fuelRate,
                    fuelCost = fuelCost,
                    perKmFuel = perKmFuel,
                    distance = statsResult.distance,
                )
            }
        }

        // ---- 油耗走势（一张卡） ----
        item(key = "chart") {
            SectionCard(
                title = tr("油耗走势", "Fuel trend"),
                action = {
                    RangeFilterPill(
                        label = rangeLabel(chartPeriod, chartCustomRange),
                        expanded = chartMenuExpanded,
                        onToggle = { chartMenuExpanded = true },
                        onDismiss = { chartMenuExpanded = false },
                    ) {
                        val presetSelected = chartCustomRange == null
                        DropdownMenuItem(
                            text = { Text(tr("全部", "All")) },
                            trailingIcon = checkIcon(presetSelected && chartPeriod == Period.ALL),
                            onClick = {
                                chartPeriod = Period.ALL
                                chartCustomRange = null
                                chartMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("本月", "This month")) },
                            trailingIcon = checkIcon(presetSelected && chartPeriod == Period.MONTH),
                            onClick = {
                                chartPeriod = Period.MONTH
                                chartCustomRange = null
                                chartMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("今年", "This year")) },
                            trailingIcon = checkIcon(presetSelected && chartPeriod == Period.YEAR),
                            onClick = {
                                chartPeriod = Period.YEAR
                                chartCustomRange = null
                                chartMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("自定义…", "Custom…")) },
                            trailingIcon = checkIcon(chartCustomRange != null),
                            onClick = {
                                chartMenuExpanded = false
                                chartShowRangePicker = true
                            },
                        )
                    }
                },
            ) {
                if (intervals.isEmpty()) {
                    EmptyState(
                        illustration = painterResource(R.drawable.ill_empty_fuel),
                        title = tr("至少需要两次有里程、加油量的加满记录", "At least two full-tank records with mileage and fuel amount are needed"),
                        description = tr("导入加油记录后也会自动计算；导入入口在 设置 → 外部导入。", "Imported fuel records are calculated automatically too; import is in Settings → Import from other apps."),
                    )
                } else {
                    FuelTrendChart(
                        intervals = intervals,
                        rangeStartIso = if (chartHasExplicitRange) chartRange.from else null,
                        rangeEndIso = if (chartHasExplicitRange) chartRange.to else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        } else {
            // ---- 明细页：搜索 + 时间筛选 → 汇总 → 按月份分组的独立记录卡 ----
            item(key = "detailTop") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = detailQuery,
                            onValueChange = { detailQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(tr("搜索备注 / 日期", "Search notes / dates"), style = MaterialTheme.typography.bodyMedium)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = MileNoteIcons.Search,
                                    contentDescription = tr("搜索备注或日期", "Search notes or dates"),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            singleLine = true,
                            shape = CircleShape,
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        RangeFilterPill(
                            label = rangeLabel(detailPeriod, detailCustomRange),
                            expanded = detailMenuExpanded,
                            onToggle = { detailMenuExpanded = true },
                            onDismiss = { detailMenuExpanded = false },
                        ) {
                            val presetSelected = detailCustomRange == null
                            DropdownMenuItem(
                                text = { Text(tr("全部", "All")) },
                                trailingIcon = checkIcon(presetSelected && detailPeriod == Period.ALL),
                                onClick = {
                                    detailPeriod = Period.ALL
                                    detailCustomRange = null
                                    detailMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("本月", "This month")) },
                                trailingIcon = checkIcon(presetSelected && detailPeriod == Period.MONTH),
                                onClick = {
                                    detailPeriod = Period.MONTH
                                    detailCustomRange = null
                                    detailMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("今年", "This year")) },
                                trailingIcon = checkIcon(presetSelected && detailPeriod == Period.YEAR),
                                onClick = {
                                    detailPeriod = Period.YEAR
                                    detailCustomRange = null
                                    detailMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("自定义…", "Custom…")) },
                                trailingIcon = checkIcon(detailCustomRange != null),
                                onClick = {
                                    detailMenuExpanded = false
                                    detailShowRangePicker = true
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tr("共 %d 条记录", "%d records", detailRecords.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = tr("筛选合计 %s 升 · ¥ %s", "Filtered total %s L · ¥ %s", Format.fmt(detailLiters), Format.money(detailAmount)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (detailRecords.isEmpty()) {
                item(key = "detailEmpty") {
                    EmptyState(
                        icon = MileNoteIcons.Fuel,
                        title = if (detailQuery.isBlank()) {
                            tr("还没有加油记录", "No fuel records yet")
                        } else {
                            tr("没有匹配的加油记录", "No matching fuel records")
                        },
                        description = if (detailQuery.isBlank()) {
                            tr("可在底栏中间「记一笔」记录加油；导入入口在 设置 → 外部导入。", "Add fuel from Add entry in the bottom bar; import is in Settings → Import from other apps.")
                        } else {
                            tr("试试清空搜索关键词或调整时间范围。", "Try clearing the search keyword or changing the date range.")
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
                        FuelRecordCard(
                            record = record,
                            rate = rateByRecord[record.id],
                            onClick = { detailRecord = record },
                        )
                    }
                }
            }
            // ---- 明细底部：满箱法说明 ----
            item(key = "footnote") {
                FuelFootnote()
            }
        }
    }
    }

    detailRecord?.let { record ->
        FuelRecordDetailDialog(
            record = record,
            rate = rateByRecord[record.id],
            onDismiss = { detailRecord = null },
            onEdit = {
                detailRecord = null
                vm.openEditor(recordId = record.id)
            },
        )
    }
    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                vm.setCustomRange(from, to)
                showRangePicker = false
            },
        )
    }
    if (detailShowRangePicker) {
        CustomRangePickerDialog(
            customRange = detailCustomRange,
            onDismiss = { detailShowRangePicker = false },
            onConfirm = { from, to ->
                detailCustomRange = DateRange(from, to)
                detailShowRangePicker = false
            },
        )
    }
    if (chartShowRangePicker) {
        CustomRangePickerDialog(
            customRange = chartCustomRange,
            onDismiss = { chartShowRangePicker = false },
            onConfirm = { from, to ->
                chartCustomRange = DateRange(from, to)
                chartShowRangePicker = false
            },
        )
    }
}

/** 今日油价卡：车辆标号价格 + 下次调价/预测；无定位时默认北京。 */
@Composable
private fun FuelPriceCard(
    info: FuelPriceInfo?,
    loading: Boolean,
    fuelGrade: String?,
    locationLabel: String,
    onGetCurrentCityPrice: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = tr("今日油价", "Today's fuel price"),
        action = {
            TextButton(
                onClick = onGetCurrentCityPrice,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Location,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = locationLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        when {
            loading && info == null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("正在获取当地油价…", "Getting local fuel prices…"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            info == null -> Text(
                text = tr("暂时无法获取油价，请检查网络后刷新", "Fuel prices are unavailable. Check your connection and refresh."),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
            else -> {
                val requestedGrade = fuelGrade?.takeIf { it in setOf("92#", "95#", "98#", "0#") } ?: "92#"
                val gradePrice = when (requestedGrade) {
                    "95#" -> info.price95
                    "98#" -> info.price98
                    "0#" -> info.price0
                    else -> info.price92
                }
                val shownGrade = if (gradePrice == null) "92#" else requestedGrade
                val shownPrice = gradePrice ?: info.price92
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = shownGrade,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = shownPrice?.let { Format.money(it) } ?: "—",
                                style = MaterialTheme.typography.headlineLarge,
                                color = scheme.onSurface,
                            )
                            Text(
                                text = tr(" 元/升", " CNY/L"),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.widthIn(min = 150.dp, max = 190.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                            Text(
                                text = info.nextAdjustmentText?.let { raw ->
                                    // 仓库层已按语言生成日期，只有中文文案里的空格需要去掉。
                                    val clean = if (com.milenote.android.i18n.I18n.english) raw else raw.replace(" ", "")
                                    tr("下次调价：%s", "Next price change: %s", clean)
                                } ?: tr("下次调价：时间待公布", "Next price change: date to be announced"),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            info.trend?.let { trend ->
                                val up = trend.contains("上")
                                val trendColor = if (up) scheme.error else Color(0xFF2E7D32)
                                val trendLabel = when {
                                    trend.contains("上") -> tr("上调", "up")
                                    trend.contains("下") -> tr("下调", "down")
                                    else -> tr("搁浅", "unchanged")
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(top = 2.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(trendColor, CircleShape),
                                    )
                                    Text(
                                        text = tr("预计%s", "Expected: %s", trendLabel),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = trendColor,
                                    )
                                }
                        }
                    }
                }
                if (info.isFallback) {
                    Text(
                        text = tr("离线参考价，联网后点刷新获取当地实时油价", "Offline reference price. Refresh while online for real-time local prices."),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 概览指标（卡内 2×2：只保留 label + 数值 + 单位，不做小卡 / 分割线 / caption）
// ---------------------------------------------------------------------------------------------

@Composable
private fun FuelMetrics(
    fuelRate: Double?,
    fuelCost: Double,
    perKmFuel: Double?,
    distance: Double,
) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = tr("平均油耗", "Fuel economy"),
                value = fuelRate?.let { Format.money(it) } ?: "—",
                unit = if (fuelRate != null) tr("升/百公里", "L/100km") else null,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("油费支出", "Fuel expense"),
                value = Format.money(fuelCost),
                unit = tr("元", "CNY"),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = tr("平均油费", "Cost per km"),
                value = perKmFuel?.let { Format.money(it) } ?: "—",
                unit = if (perKmFuel != null) tr("元/公里", "CNY/km") else null,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("行驶里程", "Distance"),
                value = Format.fmt(distance),
                unit = tr("公里", "km"),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 纯文字指标：label / 数值 + 单位（无图标、无 caption、无分割线）。 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.moneyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 时间筛选（与总览「用车统计」一致：全部 / 本月 / 今年 / 自定义）
// ---------------------------------------------------------------------------------------------

/** 卡内右上角胶囊按钮 + DropdownMenu。 */
@Composable
private fun RangeFilterPill(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = tr("选择统计范围", "Select date range"),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

@Composable
private fun checkIcon(selected: Boolean): (@Composable () -> Unit)? =
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

/** 筛选显示文案：自定义优先，跨年时带年份。 */
private fun rangeLabel(period: Period, customRange: DateRange?): String {
    customRange?.let { range ->
        val from = Dates.parse(range.from)
        val to = Dates.parse(range.to)
        if (from != null && to != null) {
            return if (from.year == to.year) {
                "${monthDay(from)}–${monthDay(to)}"
            } else {
                "${yearMonthDay(from)}–${yearMonthDay(to)}"
            }
        }
    }
    return when (period) {
        Period.ALL -> tr("全部", "All")
        Period.MONTH -> tr("本月", "This month")
        Period.YEAR -> tr("今年", "This year")
    }
}

private fun monthDay(date: LocalDate): String =
    "${date.monthValue.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')}"

private fun yearMonthDay(date: LocalDate): String = "${date.year}.${monthDay(date)}"

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

/** 自定义统计范围：M3 DateRangePicker（闭区间，ISO 日期）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangePickerDialog(
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

// ---------------------------------------------------------------------------------------------
// 加油明细时间轴
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

/** 明细页搜索：备注或日期（ISO / 2026.10.18 / 10 月 18 日 均可命中）。 */
private fun matchesDetailQuery(record: Record, query: String): Boolean {
    if (record.note.contains(query, ignoreCase = true)) return true
    val dayLabel = monthDayLabel(record.date)
    return listOf(
        record.date,
        Format.dateDots(record.date),
        dayLabel,
        dayLabel.replace(" ", ""),
    ).any { it.contains(query, ignoreCase = true) }
}

/** 明细页单条加油记录：独立白色圆角卡（阴影、无内嵌卡片）。 */
@Composable
private fun FuelRecordCard(record: Record, rate: Double?, onClick: () -> Unit) {
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
                val (statusLabel, statusKind) = fuelStatus(record)
                StatusPill(text = statusLabel, status = statusKind)
            }
            // 油耗最大，公里数次之，升数与金额最小。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = rate?.let { Format.money(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = tr(" 升/百公里", " L/100km"),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = record.odometer?.let { tr("%s 公里", "%s km", Format.fmt(it)) } ?: tr("— 公里", "— km"),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.liters?.let { tr("+%s 升", "+%s L", Format.fmt(it)) } ?: tr("— 升", "— L"),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "¥ ${Format.money(record.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 时间轴状态点：手填油耗 info 实心 / 漏记 warning 实心 / 已加满 success 实心 / 未加满 outline 空心。
 */
private fun fuelDot(
    record: Record,
    outline: Color,
    semantic: com.milenote.android.ui.theme.MileNoteSemanticColors,
): Pair<Color, Boolean> = when {
    record.kind == "manual" -> semantic.info.content to false
    record.missed -> semantic.warning.content to false
    record.full -> semantic.success.content to false
    else -> outline to true
}


/** 加油记录状态：已加满 / 未加满 / 漏记后重置 / 手填油耗。 */
private fun fuelStatus(record: Record): Pair<String, StatusKind> = when {
    record.missed -> tr("漏记后重置", "Baseline reset after a missed entry") to StatusKind.SOON
    record.kind == "manual" -> tr("手填油耗", "Manual fuel entry") to StatusKind.NORMAL
    record.full -> tr("已加满", "Filled up") to StatusKind.DONE
    else -> tr("未加满", "Not filled up") to StatusKind.NORMAL
}

// ---------------------------------------------------------------------------------------------
// 加油记录详情（点击记录卡先看详情，「编辑」才进入编辑）
// ---------------------------------------------------------------------------------------------

/**
 * 单条加油 / 油耗记录详情弹层。
 *
 * 保留记录卡上的全部信息并补充「百公里油耗」：日期、来源、金额、加油量、总里程、
 * 是否加满 / 漏记、该次满箱区间百公里油耗、备注。
 * 底部「编辑」才走 `vm.openEditor(recordId)`，「关闭」不修改任何数据。
 *
 * @param rate 该记录的满箱法区间油耗；无有效区间为 null（显示「—」）。
 */
@Composable
private fun FuelRecordDetailDialog(
    record: Record,
    rate: Double?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = tr("加油详情", "Fuel entry details"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = monthDayLabel(record.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailRow(
                    tr("来源", "Source"),
                    if (record.source.isBlank() || record.source == "手动记录") {
                        tr("手动记录", "Manual entry")
                    } else {
                        record.source
                    },
                )
                DetailRow(tr("实付金额", "Amount paid"), "¥ ${Format.money(record.amount)}")
                DetailRow(tr("加油量", "Fuel amount"), record.liters?.let { tr("%s升", "%s L", Format.fmt(it)) } ?: "—")
                record.fuelGrade?.takeIf { it.isNotBlank() }?.let { grade ->
                    DetailRow(tr("燃油标号", "Fuel grade"), if (grade == "0#") tr("0# 柴油", "0# diesel") else grade)
                }
                DetailRow(tr("总里程", "Odometer"), record.odometer?.let { tr("%s公里", "%s km", Format.fmt(it)) } ?: "—")
                DetailRow(tr("加满状态", "Full tank"), buildString {
                    append(if (record.full) tr("已加满", "Filled up") else tr("未加满", "Not filled up"))
                    append(" · ")
                    append(if (record.missed) tr("有漏记", "Missed entry") else tr("无漏记", "No missed entry"))
                })
                DetailRow(tr("百公里油耗", "Fuel economy"), rate?.let { tr("%s升/百公里", "%s L/100km", Format.money(it)) } ?: "—")
                val (statusLabel, _) = fuelStatus(record)
                DetailRow(tr("状态", "Status"), statusLabel)
                DetailRow(tr("备注", "Note"), record.note.ifBlank { "—" })
            }
        },
        confirmButton = {
            Button(
                onClick = onEdit,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(tr("编辑", "Edit"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
        },
    )
}

/** 详情字段行：左侧固定宽标签 + 右侧值（可换行）。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 页尾口径说明
// ---------------------------------------------------------------------------------------------

@Composable
private fun FuelFootnote() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = tr("满箱法说明", "Full-tank method"), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) MileNoteIcons.ChevronDown else MileNoteIcons.ChevronRight,
                    contentDescription = if (expanded) tr("收起", "Collapse") else tr("展开", "Expand"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    text = tr(
                        "满箱法：两次加满之间的累计加油量 ÷ 里程差 × 100。第一次加满仅建立基准；" +
                            "未加满的加油量累计到下次加满，漏记会重新建立基准。" +
                            "历史油耗记录保留，并沿用原有统计口径。",
                        "Full-tank method: total fuel between two full tanks ÷ distance × 100. The first fill-up only sets the baseline; partial fill-ups accumulate until the next full tank, and a missed entry resets the baseline. Historical fuel records are kept under the original calculation.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}


private const val FUEL_PREFS = "milenote-fuel-price"
private const val KEY_FUEL_PROVINCE_SLUG = "fuel_province_slug"
private const val KEY_FUEL_PROVINCE_NAME = "fuel_province_name"
private const val KEY_FUEL_CITY_NAME = "fuel_city_name"

private fun loadSavedFuelLocation(context: Context): FuelLocation? {
    val prefs = context.getSharedPreferences(FUEL_PREFS, Context.MODE_PRIVATE)
    val slug = prefs.getString(KEY_FUEL_PROVINCE_SLUG, null) ?: return null
    val province = FuelRegions.bySlug(slug) ?: return null
    val city = prefs.getString(KEY_FUEL_CITY_NAME, null)
    return FuelLocation(province = province, cityName = city)
}

private fun saveFuelLocation(context: Context, location: FuelLocation) {
    context.getSharedPreferences(FUEL_PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_FUEL_PROVINCE_SLUG, location.province.slug)
        .putString(KEY_FUEL_PROVINCE_NAME, location.province.name)
        .putString(KEY_FUEL_CITY_NAME, location.cityName)
        .apply()
}
