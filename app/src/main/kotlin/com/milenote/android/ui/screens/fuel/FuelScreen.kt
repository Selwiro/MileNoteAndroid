package com.milenote.android.ui.screens.fuel

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.R
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.domain.calc.Categories
import com.milenote.android.data.fuelprice.FuelPriceCache
import com.milenote.android.data.fuelprice.FuelPriceInfo
import com.milenote.android.data.fuelprice.FuelPriceRepository
import com.milenote.android.data.fuelprice.FuelLocation
import com.milenote.android.data.fuelprice.FuelRegions
import com.milenote.android.platform.location.LocationResolver
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.EnergyKind
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.calc.energyIntervals
import com.milenote.android.domain.calc.manualEnergyIntervals
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.HeaderTextTabs
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.StatsRangeRow
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.moneyMedium
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 能耗分析页（燃油=油耗分析，纯电/插混=能耗分析；页面名与路由名保留 `fuel`）。
 *
 * 能量档位按 `ledger.vehicle.energyProfile` 三分支（燃油 / 纯电逐像素不变，插混走双流）：
 * - **FUEL_ONLY（燃油、HEV）**：今日油价卡 + 2×2 概览（油耗 / 油费支出 / 平均油费 / 里程）+ 油耗走势；
 * - **ELECTRIC_ONLY（纯电）**：无油价卡、不抓价、不定位；2×2 概览（电耗 / 电费支出 / 平均电费 / 里程）+ 电耗走势；
 * - **DUAL（插混 / 增程）**：有油箱 → 油价卡照旧（抓价 / 定位不变）；概览 2×3（平均油耗 / 平均电耗 /
 *   油费 / 电费 / 每公里能源费用 / 里程）、走势右上角「筛选」含时间 + 能源（油耗 / 电耗）两组；
 *   加油与充电明细在推入页 [EnergyDetailScreen] 里混排 + 两行汇总。
 *
 * 结构（自上→下，统一为 SectionCard 卡片风格，卡内只有标题、无副标题）：
 * 1. 「今日油价」卡（有油箱且非英文界面时）：标号油价 + 刷新 / 定位；
 * 2. 「油耗/能耗概览」卡：右上角时间筛选（全部 / 本月 / 今年 / 自定义 DateRangePicker）+ 卡内 2×2（插混 2×3）纯文字指标；
 * 3. 「油耗/能耗走势」卡：右上角「筛选」胶囊（时间分组；插混另有能源分组，选中即切换曲线数据源）+
 *    平滑曲线 + 面积渐变 + 当前值 pill，点按数据点看数值；
 * 4. 底部可折叠「满箱法 / 满电+累计 / 双口径说明」。
 *
 * 「明细」页签已拆成独立推入页（[EnergyDetailScreen]）：本页不再含页签，也不含任何明细 state。
 *
 * 纯电车辆：不显示今日油价卡、不创建油价仓库、不抓取、不注册定位权限；
 * 单位与文案全部按档位切换（单位见 [Units.energyUnit] / [Units.energyConsumption]）。
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

    // 能量档位：燃油 / HEV 走油耗页（今日油价卡 + L/100km），纯电走能耗页（无油价卡、kWh/100km）；
    // 插混（DUAL）走能耗页双流：有油箱 → 今日油价卡照旧显示/抓价/定位，概览·走势·明细同时看油耗与电耗。
    val profile = ledger.vehicle.energyProfile
    val ev = profile == EnergyProfile.ELECTRIC_ONLY
    val phev = profile == EnergyProfile.DUAL
    val context = LocalContext.current
    // 纯电车辆整页不显示「今日油价」：不创建油价仓库、不读取缓存定位、不注册定位权限 launcher。
    val priceRepo = remember(ev) { if (ev) null else FuelPriceRepository() }
    val scope = rememberCoroutineScope()
    val savedLocation = remember(ev) { if (ev) null else loadSavedFuelLocation(context) }
    var fuelLocation by remember(ev) { mutableStateOf(savedLocation ?: FuelLocation()) }
    var locationRemembered by remember(ev) { mutableStateOf(savedLocation != null) }
    // 上次成功抓取的油价先落盘：进入页面立即显示缓存，同一天内不重复请求（跨天才自动刷新）。
    val priceCache = remember(ev) { if (ev) null else FuelPriceCache(context) }
    val cachedAtStart = remember(ev) { if (ev) null else priceCache?.load(fuelLocation) }
    var priceInfo by remember(ev) { mutableStateOf(cachedAtStart?.info) }
    var priceFetchedAt by remember(ev) { mutableStateOf(cachedAtStart?.fetchedAtMillis) }
    var priceStale by remember(ev) { mutableStateOf(false) }
    var priceLoading by remember(ev) { mutableStateOf(!ev && cachedAtStart == null) }

    /** [force] = 手动刷新（忽略当天缓存）；自动刷新（进页面/跨天）时 force=false。 */
    fun refreshPrice(reLocate: Boolean, force: Boolean = false) {
        val repo = priceRepo ?: return
        scope.launch {
            if (reLocate) {
                val resolved = LocationResolver.resolve(context)
                if (resolved != null) {
                    val next = FuelLocation(resolved.province, resolved.cityName)
                    fuelLocation = next
                    saveFuelLocation(context, next)
                    locationRemembered = true
                }
            }
            val cached = priceCache?.load(fuelLocation)
            val now = System.currentTimeMillis()
            if (!force && cached != null && !FuelPriceCache.isStale(cached.fetchedAtMillis, now)) {
                priceInfo = cached.info
                priceFetchedAt = cached.fetchedAtMillis
                priceStale = false
                priceLoading = false
                return@launch
            }
            if (priceInfo == null) priceLoading = true
            val info = repo.fetch(fuelLocation)
            if (info != null) {
                priceCache?.save(fuelLocation, info, now)
                priceInfo = info
                priceFetchedAt = now
                priceStale = false
            } else {
                // 抓取失败但本机有上次成功的数据：继续显示旧价并标注「更新失败」，不清空。
                val cachedFallback = cached ?: priceCache?.load(fuelLocation)
                if (cachedFallback != null) {
                    priceInfo = cachedFallback.info
                    priceFetchedAt = cachedFallback.fetchedAtMillis
                    priceStale = true
                } else {
                    priceInfo = repo.referenceInfo(fuelLocation)
                    priceFetchedAt = null
                    priceStale = false
                }
            }
            priceLoading = false
        }
    }

    val locationPermissionLauncher = if (!ev) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) refreshPrice(reLocate = true, force = true)
        }
    } else {
        null
    }

    // 打开油耗页时自动刷新当前记住位置的油价；不重新定位。纯电车辆不发起任何油价抓取。
    LaunchedEffect(ev) { if (!ev) refreshPrice(reLocate = false) }

    val today = Dates.today()
    val period = state.period
    val customRange = state.customRange
    val range = remember(period, customRange, today) { state.effectiveRange(today) }
    val statsResult = remember(ledger, range) { stats(ledger, range.from, range.to) }
    // 概览指标 / 走势跟随顶部筛选（state.period / customRange）。
    val overviewEnergyRecords = remember(statsResult) {
        statsResult.records
            .filter { Categories.isEnergy(it.category) }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    val energyCost = remember(overviewEnergyRecords, ledger) { overviewEnergyRecords.sumOf { costOf(it, ledger) } }
    val perKmEnergy = if (statsResult.distance > 0) energyCost / statsResult.distance else null
    // 插混概览把能源支出拆成两行：有 kwh 的记录算电费，其余能源记录（加油 + 历史手填油耗）算油费。
    // 两条流的口径都还是 costOf（fuelCounted / 旧 fuelCostSource 规则不变），只是分组不同。
    val fuelEnergyCost = remember(overviewEnergyRecords, ledger) {
        overviewEnergyRecords.filter { it.kwh == null }.sumOf { costOf(it, ledger) }
    }
    val electricEnergyCost = remember(overviewEnergyRecords, ledger) {
        overviewEnergyRecords.filter { it.kwh != null }.sumOf { costOf(it, ledger) }
    }
    // 走势卡与概览共用页级统计范围（state.period / customRange）。
    // 纯电只统计 kwh 满电区间；历史手填油耗是燃油字段，不参与纯电统计。
    // 插混两条流都算好，「油耗 / 电耗」是走势卡头的内容切换（不是筛选）。
    var chartKind by rememberSaveable { mutableStateOf(EnergyKind.FUEL) }
    val allIntervals = remember(ledger.records, ev, phev, chartKind) {
        when {
            ev -> energyIntervals(ledger.records, EnergyKind.ELECTRIC)
            !phev -> energyIntervals(ledger.records, EnergyKind.FUEL) + manualEnergyIntervals(ledger.records)
            chartKind == EnergyKind.FUEL ->
                energyIntervals(ledger.records, EnergyKind.FUEL) + manualEnergyIntervals(ledger.records)
            else -> energyIntervals(ledger.records, EnergyKind.ELECTRIC)
        }.sortedBy { it.date }
    }
    val chartFilteredIntervals = remember(allIntervals, range) {
        allIntervals.filter { interval ->
            interval.date >= range.from && interval.date <= range.to
        }
    }
    val chartHasExplicitRange = customRange != null || period != Period.ALL
    // 展示筛选范围内的全部区间（不再固定截断为最近 12 个）。
    val intervals = remember(chartFilteredIntervals) { chartFilteredIntervals }
    // 明细列表（搜索 / 时间筛选 / 汇总 / 月度分组 / 满箱法能耗）已整体搬到推入页 EnergyDetailScreen，
    // 本页不保留任何明细 state，避免把 detailPeriod 等独立筛选误带进统计页。

    // 单页内容（页签与明细都已迁出）：今日油价 → 概览 → 走势 → 口径说明。
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
        // 英文界面不显示「今日油价」：数据源是中文油价站点，对英文用户没有意义；
        // 纯电车辆没有油价，整卡不显示（也不发起抓取 / 不请求定位）。
        if (!I18n.english && !ev) {
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
                lastUpdatedMillis = priceFetchedAt,
                stale = priceStale,
                onRefresh = { refreshPrice(reLocate = false, force = true) },
                onGetCurrentCityPrice = {
                    if (LocationResolver.hasPermission(context)) {
                        refreshPrice(reLocate = true, force = true)
                    } else {
                        locationPermissionLauncher?.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                },
            )
        }
        }
        // ---- 页级统计范围（概览 + 走势共用，与总览同一个状态） ----
        item(key = "stats-range") {
            StatsRangeRow(
                period = period,
                customRange = customRange,
                onSelectPeriod = vm::setPeriod,
                onSelectCustomRange = { from, to -> vm.setCustomRange(from, to) },
            )
        }
        // ---- 油耗/能耗概览（卡内 2×2；插混 2×3） ----
        item(key = "overview") {
            SectionCard(
                title = if (ev || phev) tr("能耗概览", "Energy overview") else tr("油耗概览", "Fuel overview"),
            ) {
                if (phev) {
                    PhevEnergyMetrics(
                        fuel = statsResult.fuelEnergy,
                        electric = statsResult.electricEnergy,
                        fuelCost = fuelEnergyCost,
                        electricCost = electricEnergyCost,
                        perKmEnergy = perKmEnergy,
                        distance = statsResult.distance,
                    )
                } else {
                    FuelMetrics(
                        energyRate = statsResult.energyRate,
                        estimatedRate = if (ev) statsResult.estimatedEnergyRate else null,
                        ev = ev,
                        energyCost = energyCost,
                        perKmEnergy = perKmEnergy,
                        distance = statsResult.distance,
                    )
                }
            }
        }

        // ---- 油耗/能耗走势（一张卡；插混的曲线数据源在右上角「筛选 → 能源」里切换） ----
        item(key = "chart") {
            SectionCard(
                title = if (ev || phev) tr("能耗走势", "Energy trend") else tr("油耗走势", "Fuel trend"),
                action = if (phev) {
                    {
                        HeaderTextTabs(
                            labels = listOf(tr("油耗", "Fuel"), tr("电耗", "Electric")),
                            selectedIndex = if (chartKind == EnergyKind.FUEL) 0 else 1,
                            onSelect = { index ->
                                chartKind = if (index == 0) EnergyKind.FUEL else EnergyKind.ELECTRIC
                            },
                        )
                    }
                } else {
                    null
                },
            ) {
                // 插混：曲线数据源由右上角「筛选 → 能源」切换（单 Y 轴，单位随切换），空态与图表都跟随当前种类。
                val streamEv = if (phev) chartKind == EnergyKind.ELECTRIC else ev
                if (intervals.isEmpty()) {
                    EmptyState(
                        illustration = painterResource(R.drawable.ill_empty_fuel),
                        title = if (streamEv) {
                            tr("至少需要两次有里程、充电量的充满记录", "At least two full-charge records with mileage and charge amount are needed")
                        } else {
                            tr("至少需要两次有里程、加油量的加满记录", "At least two full-tank records with mileage and fuel amount are needed")
                        },
                        description = if (streamEv) {
                            tr(
                                "满电法：两次充满之间的累计充电量 ÷ 里程差 × 100；没有充满记录时按累计充电量估算（含充电损耗）。导入充电记录后也会自动计算；导入入口在 设置 → 外部导入。",
                                "Full-charge method: total energy between two full charges ÷ distance × 100; without a full charge it falls back to a cumulative estimate (includes charging loss). Imported charging records are calculated automatically too; import is in Settings → Import from other apps.",
                            )
                        } else {
                            tr("导入加油记录后也会自动计算；导入入口在 设置 → 外部导入。", "Imported fuel records are calculated automatically too; import is in Settings → Import from other apps.")
                        },
                    )
                } else {
                    FuelTrendChart(
                        intervals = intervals,
                        rangeStartIso = if (chartHasExplicitRange) range.from else null,
                        rangeEndIso = if (chartHasExplicitRange) range.to else null,
                        modifier = Modifier.fillMaxWidth(),
                        unit = if (streamEv) Units.energyConsumption else Units.consumption,
                    )
                }
            }
        }

        // 明细列表已迁到推入页 EnergyDetailScreen；口径说明保留在统计页底部。
        item(key = "footnote") {
            FuelFootnote(ev = ev, phev = phev)
        }
    }

}

/** 今日油价卡：车辆标号价格 + 下次调价/预测；无定位时默认北京。 */
@Composable
private fun FuelPriceCard(
    info: FuelPriceInfo?,
    loading: Boolean,
    fuelGrade: String?,
    locationLabel: String,
    lastUpdatedMillis: Long?,
    stale: Boolean,
    onRefresh: () -> Unit,
    onGetCurrentCityPrice: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = tr("今日油价", "Today's fuel price"),
        action = {
            // 刷新按钮移到卡片底部「更新于」前面，右上角只留定位 / 城市切换。
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
            info == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                FuelPriceRefreshButton(onClick = onRefresh, enabled = !loading)
                Text(
                    text = tr("暂时无法获取油价，请检查网络后刷新", "Fuel prices are unavailable. Check your connection and refresh."),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
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
                val statusError: Pair<String, Color>? = when {
                    info.isFallback -> tr(
                        "实时油价获取失败，当前显示参考价（联网后会自动重试）",
                        "Live fuel prices unavailable — showing reference prices (will retry when online)",
                    ) to scheme.error
                    stale -> tr(
                        "网络暂不可用，显示上次获取的油价（%s）",
                        "Network unavailable — showing the last fetched prices (%s)",
                        lastUpdatedMillis?.let(::formatFuelPriceTime) ?: "—",
                    ) to scheme.error
                    else -> null
                }
                val updatedText = if (statusError == null) {
                    lastUpdatedMillis?.let { tr("更新于 %s", "Updated %s", formatFuelPriceTime(it)) }
                } else {
                    null
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 上排：标号 + 油价 + 单位 + 下次调价，同一基线（「下次调价」底边与「元/升」齐）。
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = shownGrade,
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .alignByBaseline()
                                .padding(end = 8.dp),
                        )
                        Text(
                            text = shownPrice?.let { Format.money(it) } ?: "—",
                            style = MaterialTheme.typography.headlineLarge,
                            color = scheme.onSurface,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Text(
                            text = tr(" 元/升", " CNY/L"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .alignByBaseline()
                                .padding(start = 4.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = info.nextAdjustmentText?.let { raw ->
                                // 仓库层已按语言生成日期，只有中文文案里的空格需要去掉。
                                val clean = if (com.milenote.android.i18n.I18n.english) raw else raw.replace(" ", "")
                                tr("下次调价：%s", "Next price change: %s", clean)
                            } ?: tr("下次调价：时间待公布", "Next price change: date to be announced"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        thickness = 1.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    // 底排：左「刷新 + 更新于」，右「预计涨跌」，整行居中对齐（圆点与文字垂直居中）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FuelPriceRefreshButton(onClick = onRefresh, enabled = !loading)
                        val bottomText = updatedText ?: statusError?.first
                        val bottomColor = if (updatedText != null) {
                            scheme.onSurfaceVariant
                        } else {
                            statusError?.second ?: scheme.onSurfaceVariant
                        }
                        if (bottomText != null) {
                            Text(
                                text = bottomText,
                                style = MaterialTheme.typography.bodySmall,
                                color = bottomColor,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        info.trend?.let { trend ->
                            val up = trend.contains("上")
                            val trendColor = if (up) scheme.error else Color(0xFF2E7D32)
                            val trendLabel = when {
                                trend.contains("上") -> tr("上调", "up")
                                trend.contains("下") -> tr("下调", "down")
                                else -> tr("搁浅", "unchanged")
                            }
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(trendColor, CircleShape),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("预计%s", "Expected: %s", trendLabel),
                                style = MaterialTheme.typography.labelLarge,
                                color = trendColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 今日油价卡的刷新按钮：普通小图标（与「更新于」行同高），位于卡片左下角「更新于」前面。 */
@Composable
private fun FuelPriceRefreshButton(onClick: () -> Unit, enabled: Boolean) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(20.dp),
    ) {
        Icon(
            imageVector = MileNoteIcons.Refresh,
            contentDescription = tr("刷新油价", "Refresh fuel prices"),
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 概览指标（卡内 2×2：只保留 label + 数值 + 单位，不做小卡 / 分割线 / caption）
// ---------------------------------------------------------------------------------------------

@Composable
private fun FuelMetrics(
    energyRate: Double?,
    estimatedRate: Double?,
    ev: Boolean,
    energyCost: Double,
    perKmEnergy: Double?,
    distance: Double,
) {
    // 纯电没有满充区间时回退到累计估算口径（值与标注一起出现）。
    val shownRate = energyRate ?: estimatedRate
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = if (ev) tr("平均能耗", "Energy economy") else tr("平均油耗", "Fuel economy"),
                value = shownRate?.let { Format.money(it) } ?: "—",
                unit = if (shownRate == null) null else if (ev) Units.energyConsumption else Units.consumption,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = if (ev) tr("电费支出", "Charging expense") else tr("油费支出", "Fuel expense"),
                value = Format.money(energyCost),
                unit = Units.money,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = if (ev) {
                    tr("平均电费", "Average charging cost")
                } else {
                    tr("平均油费", "Average fuel cost")
                },
                value = perKmEnergy?.let { Format.money(it) } ?: "—",
                unit = if (perKmEnergy != null) Units.costPerDistance else null,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("行驶里程", "Distance"),
                value = Format.fmt(distance),
                unit = Units.distanceUnit,
                modifier = Modifier.weight(1f),
            )
        }
        if (energyRate == null && estimatedRate != null) {
            // 估算口径说明：只有在概览卡使用估算值时出现（纯电无满充区间）。
            Text(
                text = tr(
                    "无满充记录，当前为累计估算口径（含充电损耗）。",
                    "No full-charge interval yet; showing the cumulative estimate (includes charging loss).",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 纯文字指标：label / 数值 + 单位（无图标、无 caption、无分割线）。插混概览（[PhevEnergyMetrics]）复用。 */
@Composable
internal fun StatCell(
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
// 页尾口径说明
// ---------------------------------------------------------------------------------------------

@Composable
internal fun FuelFootnote(ev: Boolean, phev: Boolean) {
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
                Text(
                    text = when {
                        phev -> tr("满箱法 + 满电法说明", "Full-tank & full-charge methods")
                        ev -> tr("满电/累计口径说明", "Full-charge & cumulative method")
                        else -> tr("满箱法说明", "Full-tank method")
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
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
                    text = when {
                        phev -> tr(
                            "满箱法 + 满电法：油耗 = 两次加满之间的累计加油量 ÷ 里程差 × 100；" +
                                "电耗 = 两次充满之间的累计充电量 ÷ 里程差 × 100。两条流各自累计，互不串流；" +
                                "第一次加满 / 充满仅建立基准，未加满 / 未充满的量累计到下次，漏记会重新建立基准。" +
                                "没有满箱 / 满充区间时按累计口径估算（电耗含充电损耗，数值可能高于车机表显）。" +
                                "口径提醒：油耗和电耗的分母都是总行驶里程，另一种能源跑的里程也算在里面，" +
                                "所以两个数都会比「只烧油」或「只用电」时偏低——这是口径如此，不是算错。",
                            "Full-tank + full-charge methods: fuel economy = total fuel between two full tanks ÷ distance × 100; " +
                                "electric economy = total energy between two full charges ÷ distance × 100. " +
                                "Each stream accumulates on its own — litres and kWh never mix. " +
                                "The first fill-up or full charge only sets the baseline; partial amounts roll over to the next full one, " +
                                "and a missed entry resets the baseline. Without any full interval it falls back to a cumulative estimate " +
                                "(charging amounts include losses, so values may read higher than the car's display). " +
                                "Note: both figures divide by the total distance, which also includes the kilometres driven on the other energy source, " +
                                "so each reads lower than a fuel-only or electric-only figure would. That is how this method works, not a mistake.",
                        )
                        ev -> tr(
                            "满电法：两次充满之间的累计充电量 ÷ 里程差 × 100。第一次充满仅建立基准；" +
                                "未充满的充电量累计到下次充满，漏记会重新建立基准。" +
                                "没有满充区间时按累计口径估算：首尾两条有里程的充电记录之间的累计充电量 ÷ 里程差 × 100。" +
                                "充电量含充电损耗（交流约 10%），数值可能高于车机表显电耗。",
                            "Full-charge method: total energy between two full charges ÷ distance × 100. The first full charge only sets the baseline; partial charges accumulate until the next full charge, and a missed entry resets the baseline. Without any full-charge interval it falls back to a cumulative estimate: total energy between the first and last charging records with mileage ÷ distance × 100. Charging amounts include charging losses (~10% AC), so values may read higher than the car's display.",
                        )
                        else -> tr(
                            "满箱法：两次加满之间的累计加油量 ÷ 里程差 × 100。第一次加满仅建立基准；" +
                                "未加满的加油量累计到下次加满，漏记会重新建立基准。" +
                                "历史油耗记录保留，并沿用原有统计口径。",
                            "Full-tank method: total fuel between two full tanks ÷ distance × 100. The first fill-up only sets the baseline; partial fill-ups accumulate until the next full tank, and a missed entry resets the baseline. Historical fuel records are kept under the original calculation.",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}


/** 油价更新时间展示（上海时区，MM-dd HH:mm）。 */
private fun formatFuelPriceTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA))

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
