package com.milenote.android.ui.screens.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Companion
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReportData
import com.milenote.android.domain.calc.ReportDelta
import com.milenote.android.domain.calc.ReportEnergyLine
import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.domain.calc.ReportPeriod
import com.milenote.android.domain.calc.report
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.HeaderTextTabs
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.screens.dashboard.BreakdownSlice
import com.milenote.android.ui.screens.dashboard.CategoryBreakdown
import com.milenote.android.ui.screens.dashboard.chartColorOf
import com.milenote.android.ui.screens.dashboard.percentText
import com.milenote.android.ui.theme.LocalMileNoteCategoryColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.LocalMileNoteHeroGradient
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.moneyLarge
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

/** 费用构成明细最多显示几类，其余折成「其他 N 类」。 */
private const val ReportCategoryRows = 4

/**
 * 用车报告（从「我的 → 数据与同步 → 用车报告」推入）。
 *
 * 结构：封面（支出 + 三个数）→ 对比（环比/同比 双条图）→ 费用构成 → 能耗 → 趋势 → 之最。
 * 三个 tab 共用同一套卡片，只换口径与文案：月报=自然月、年报=自然年、累计=首条记录 → 今天。
 *
 * 所有数字来自 domain 的 [report]；本页只做展示与周期切换，不写账本。
 * 分享浮层由路由层顶栏右上角的分享按钮触发（[shareOpen]），本页只负责算数据、画浮层。
 *
 * @param vm 全局 ViewModel（当前车账本、演示态、读账错误）
 * @param shareOpen 顶栏分享按钮是否已按下
 * @param onDismissShare 关闭分享浮层
 * @param onShareAvailableChange 本期是否有数据可分享（无数据时顶栏按钮不出现）
 */
@Composable
fun ReportScreen(
    vm: MileNoteViewModel,
    shareOpen: Boolean,
    onDismissShare: () -> Unit,
    onShareAvailableChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        val error = state.loadError
        if (error != null) {
            ReportLoadError(message = error, onRetry = vm::reloadLedger, modifier = modifier)
        } else {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState(label = tr("正在读取本机数据…", "Reading the local data…"))
            }
        }
        return
    }
    ReportContent(
        vm = vm,
        ledger = ledger,
        demo = state.demo,
        loadError = state.loadError,
        shareOpen = shareOpen,
        onDismissShare = onDismissShare,
        onShareAvailableChange = onShareAvailableChange,
        modifier = modifier,
    )
}

@Composable
private fun ReportContent(
    vm: MileNoteViewModel,
    ledger: Ledger,
    demo: Boolean,
    loadError: String?,
    shareOpen: Boolean,
    onDismissShare: () -> Unit,
    onShareAvailableChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { Dates.today() }
    var kindName by rememberSaveable { mutableStateOf(ReportKind.MONTH.name) }
    var monthYear by rememberSaveable { mutableIntStateOf(today.year) }
    var monthMonth by rememberSaveable { mutableIntStateOf(today.monthValue) }
    var yearValue by rememberSaveable { mutableIntStateOf(today.year) }
    // 切车时把三个视角的期都重置为默认；旋转 / 重建不重置（trackedCar 随 saveable 一起回来）。
    var trackedCar by rememberSaveable { mutableStateOf(ledger.id) }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(ledger.id) {
        if (trackedCar != ledger.id) {
            trackedCar = ledger.id
            kindName = ReportKind.MONTH.name
            monthYear = today.year
            monthMonth = today.monthValue
            yearValue = today.year
        }
    }

    val kind = ReportKind.entries.firstOrNull { it.name == kindName } ?: ReportKind.MONTH
    val period = when (kind) {
        ReportKind.MONTH -> ReportPeriod(ReportKind.MONTH, monthYear, monthMonth)
        ReportKind.YEAR -> ReportPeriod(ReportKind.YEAR, yearValue, 1)
        ReportKind.ALL -> ReportPeriod(ReportKind.ALL)
    }
    val data = remember(ledger, period, today) { report(ledger, period, today) }
    LaunchedEffect(data.hasData) { onShareAvailableChange(data.hasData) }

    val dates = remember(ledger) { ledger.records.mapNotNull { Dates.parse(it.date) } }
    val firstDateIso = remember(dates) { dates.minOrNull()?.let { Dates.iso(it) } }
    val firstYear = remember(dates) { dates.minOrNull()?.year ?: today.year }
    val monthsWithData = remember(dates) { dates.map { "%04d-%02d".format(it.year, it.monthValue) }.toSet() }
    val yearsWithData = remember(dates) { dates.map { it.year }.toSet() }

    val prevPeriod = period.previous()
    val nextPeriod = when (kind) {
        ReportKind.MONTH -> {
            val ym = YearMonth.of(monthYear, monthMonth).plusMonths(1)
            ReportPeriod(ReportKind.MONTH, ym.year, ym.monthValue)
        }
        ReportKind.YEAR -> ReportPeriod(ReportKind.YEAR, yearValue + 1, 1)
        ReportKind.ALL -> null
    }
    val canPrev = when (kind) {
        ReportKind.ALL -> false
        else -> prevPeriod?.range()?.let { range ->
            firstDateIso == null || range.to >= firstDateIso
        } ?: false
    }
    val canNext = nextPeriod?.let { !it.isFuture(today) } ?: false

    fun goPrev() {
        when (kind) {
            ReportKind.MONTH -> prevPeriod?.let { monthYear = it.year; monthMonth = it.month }
            ReportKind.YEAR -> yearValue -= 1
            ReportKind.ALL -> Unit
        }
    }

    fun goNext() {
        when (kind) {
            ReportKind.MONTH -> nextPeriod?.let { monthYear = it.year; monthMonth = it.month }
            ReportKind.YEAR -> yearValue += 1
            ReportKind.ALL -> Unit
        }
    }

    BackHandler(enabled = shareOpen) { onDismissShare() }

    val comparison = comparisonModel(data, kind)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MileNoteDimens.PageHorizontal,
                end = MileNoteDimens.PageHorizontal,
                top = MileNoteDimens.CardGap,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
        ) {
            if (loadError != null) {
                item(key = "load-error") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MessageBar(
                            message = tr(
                                "本地数据读取异常，原始数据已另存。当前还没有记录，可继续使用或重新读取。",
                                "The local data could not be read and a copy of the original was saved. You are starting with no records — keep using the app or re-read the original.",
                            ),
                            kind = MessageKind.ERROR,
                            actionLabel = tr("重新读取数据", "Re-read the data"),
                            onAction = vm::reloadLedger,
                        )
                        Text(
                            text = loadError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (demo) {
                item(key = "demo") {
                    DemoNotice(onExit = { vm.exitDemo() })
                }
            }
            item(key = "period") {
                PeriodHeader(
                    data = data,
                    kind = kind,
                    canPrev = canPrev,
                    canNext = canNext,
                    onKind = { index -> kindName = ReportKind.entries[index].name },
                    onPrev = ::goPrev,
                    onNext = ::goNext,
                    onPick = { pickerOpen = true },
                )
            }
            if (!data.hasData) {
                item(key = "empty") {
                    EmptyReportCard(
                        data = data,
                        onPrev = { if (canPrev) goPrev() },
                        canPrev = canPrev,
                        onAdd = { vm.openEditor() },
                    )
                }
            } else {
                item(key = "hero") {
                    ReportHeroCard(ledger = ledger, data = data)
                }
                if (comparison != null) {
                    item(key = "compare") {
                        CompareCard(data = data, model = comparison)
                    }
                }
                item(key = "categories") {
                    BreakdownCard(ledger = ledger, data = data)
                }
                if (data.fuel != null || data.electric != null) {
                    item(key = "energy") {
                        EnergyCard(ledger = ledger, data = data)
                    }
                }
                item(key = "trend") {
                    TrendCard(
                        data = data,
                        today = today,
                        onPickKey = { key ->
                            // `YYYY` = 年柱 → 切年报；`YYYY-MM` = 月柱 → 切月报并定位到该月。
                            if (key.length == 4) {
                                yearValue = key.toIntOrNull() ?: yearValue
                                kindName = ReportKind.YEAR.name
                            } else {
                                key.substring(0, 4).toIntOrNull()?.let { monthYear = it }
                                key.substring(5).toIntOrNull()?.let { monthMonth = it }
                                kindName = ReportKind.MONTH.name
                            }
                        },
                    )
                }
                if (data.highlights.let { it.topExpense != null || it.priciestEnergy != null || it.topStation != null }) {
                    item(key = "highlights") {
                        HighlightsCard(ledger = ledger, data = data, today = today)
                    }
                }
            }
        }

        if (shareOpen && data.hasData) {
            ReportShareOverlay(data = data, ledger = ledger, onDismiss = onDismissShare)
        }
    }

    if (pickerOpen) {
        ReportPeriodPicker(
            isMonth = kind == ReportKind.MONTH,
            selectedYear = if (kind == ReportKind.MONTH) monthYear else yearValue,
            selectedMonth = monthMonth,
            firstYear = firstYear,
            monthsWithData = monthsWithData,
            yearsWithData = yearsWithData,
            today = today,
            onDismiss = { pickerOpen = false },
            onSelect = { year, month ->
                if (kind == ReportKind.MONTH) {
                    monthYear = year
                    monthMonth = month
                } else {
                    yearValue = year
                }
                pickerOpen = false
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 周期头 / 演示提示
// ---------------------------------------------------------------------------------------------

@Composable
private fun PeriodHeader(
    data: ReportData,
    kind: ReportKind,
    canPrev: Boolean,
    canNext: Boolean,
    onKind: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            HeaderTextTabs(
                labels = listOf(
                    tr("月报", "Month"),
                    tr("年报", "Year"),
                    tr("累计", "All time"),
                ),
                selectedIndex = kind.ordinal,
                onSelect = onKind,
            )
        }
        if (kind == ReportKind.ALL) {
            Text(
                text = periodLabel(data, data.period),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else {
            PeriodStepper(
                label = periodLabel(data, data.period),
                canPrev = canPrev,
                canNext = canNext,
                onPrev = onPrev,
                onNext = onNext,
                onPick = onPick,
            )
        }
    }
}

/** 演示态：36dp 单行提示，不再占通栏。 */
@Composable
private fun DemoNotice(onExit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val demo = com.milenote.android.ui.theme.LocalMileNoteSemanticColors.current.demo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(demo.container.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MileNoteIcons.Sparkle,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = tr("演示模式 · 不会写入真实数据", "Demo data · not saved to your real data"),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = tr("退出", "Exit"),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onExit)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** `‹ 2026 年 11 月 ›`：左右翻期，点中间选月 / 年。 */
@Composable
private fun PeriodStepper(
    label: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = canPrev, modifier = Modifier.size(32.dp)) {
            Icon(
                MileNoteIcons.Back,
                contentDescription = tr("上一期", "Previous period"),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 132.dp)
                .clip(CircleShape)
                .clickable(onClick = onPick)
                .padding(horizontal = 2.dp),
        )
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(32.dp)) {
            Icon(
                MileNoteIcons.Forward,
                contentDescription = tr("下一期", "Next period"),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 周期文案：`2026 年 11 月` / `Nov 2026`；累计给静态时段。 */
private fun periodLabel(data: ReportData, period: ReportPeriod): String = when (period.kind) {
    ReportKind.MONTH ->
        if (I18n.english) "${monthShort(period.month)} ${period.year}"
        else "${period.year} 年 ${period.month} 月"
    ReportKind.YEAR -> if (I18n.english) "${period.year}" else "${period.year} 年"
    ReportKind.ALL -> {
        val first = data.firstDate?.let { Dates.parse(it) }
        if (first == null) {
            tr("全部记录", "All records")
        } else if (I18n.english) {
            "${monthShort(first.monthValue)} ${first.year} – today"
        } else {
            "%d.%02d – 至今".format(first.year, first.monthValue)
        }
    }
}

private fun dayLabel(date: LocalDate): String =
    if (I18n.english) "${monthShort(date.monthValue)} ${date.dayOfMonth}" else "${date.monthValue} 月 ${date.dayOfMonth} 日"

private fun monthShort(month: Int): String =
    Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

// ---------------------------------------------------------------------------------------------
// 封面
// ---------------------------------------------------------------------------------------------

/**
 * 封面：全页唯一的深色块，只给「花了多少 + 车跑了多少」，不放任何涨跌文案。
 *
 * 对比交给下面的 [CompareCard]，避免同一件事用两种形式说两遍。
 */
@Composable
private fun ReportHeroCard(ledger: Ledger, data: ReportData) {
    val gradient = LocalMileNoteHeroGradient.current
    val content = gradient.contentColor
    val scheme = MaterialTheme.colorScheme
    val vehicle = DataLabels.vehicleName(ledger.vehicle.name)
    val plate = ledger.vehicle.plate.trim()
    val money = Format.money(data.total)
    val dot = money.indexOf('.')
    val intPart = if (dot >= 0) money.substring(0, dot) else money
    val decPart = if (dot >= 0) money.substring(dot) else ""
    val rangeFrom = Dates.parse(data.range.from)
    val rangeTo = Dates.parse(data.range.to)
    val rangeText = if (rangeFrom != null && rangeTo != null) {
        "${dayLabel(rangeFrom)} – ${dayLabel(rangeTo)}"
    } else {
        ""
    }
    val stats = heroStats(data)
    val summary = tr(
        "%s支出 %s %s，%s",
        "%s spending %s %s, %s",
        periodLabel(data, data.period),
        Format.money(data.total),
        Units.money,
        stats.joinToString("，", ", ") { "${it.label} ${it.value}${it.unit.orEmpty()}" },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(gradient.colors))
            .padding(20.dp)
            .semantics(mergeDescendants = true) { contentDescription = summary },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MileNoteIcons.Car,
                contentDescription = null,
                tint = content.copy(alpha = 0.68f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (plate.isBlank()) vehicle else "$vehicle · $plate",
                fontSize = 12.sp,
                color = content.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (data.inProgress) {
                Text(
                    text = tr("进行中", "In progress"),
                    fontSize = 10.sp,
                    color = content.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(content.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = heroSpendLabel(data),
            fontSize = 12.sp,
            color = content.copy(alpha = 0.62f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = content.copy(alpha = 0.88f),
                    ),
                ) { append(Units.currencySymbol) }
                withStyle(
                    SpanStyle(
                        fontSize = 50.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1).sp,
                        color = content,
                    ),
                ) { append(intPart) }
                if (decPart.isNotEmpty()) {
                    withStyle(
                        SpanStyle(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = content.copy(alpha = 0.72f),
                        ),
                    ) { append(decPart) }
                }
            },
            style = MaterialTheme.typography.moneyLarge.copy(fontSize = 50.sp, lineHeight = 52.sp),
            maxLines = 1,
            softWrap = false,
        )

        Spacer(modifier = Modifier.height(14.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(content.copy(alpha = 0.13f)))
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            stats.forEachIndexed { index, stat ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .height(38.dp)
                            .width(1.dp)
                            .background(content.copy(alpha = 0.12f)),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = stat.value,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = content,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (stat.unit != null) {
                            Text(
                                text = stat.unit,
                                fontSize = 10.sp,
                                color = content.copy(alpha = 0.62f),
                                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Text(
                        text = stat.label,
                        fontSize = 10.sp,
                        color = content.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 1,
                    )
                }
            }
        }

        if (rangeText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = tr(
                    "%s · %d 笔记录",
                    "%s · %d entries",
                    rangeText,
                    data.recordCount,
                ),
                fontSize = 10.sp,
                color = content.copy(alpha = 0.42f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class HeroStat(val value: String, val unit: String?, val label: String)

/** 封面三个数：里程 / 每公里 / 日均（累计口径用月均）。 */
private fun heroStats(data: ReportData): List<HeroStat> {
    val distance = data.distance
    val perKm = data.perKm
    val third = if (data.period.kind == ReportKind.ALL) {
        HeroStat(
            // 月均取整：既符合「月均 ¥1,204」的读数习惯，也避免 1,278.47 + USD 在窄列里被裁掉。
            value = data.monthlyAvg?.let { Format.fmt(kotlin.math.round(it)) } ?: "—",
            unit = if (data.monthlyAvg != null) Units.money else null,
            label = tr("月均", "Monthly"),
        )
    } else {
        HeroStat(
            value = Format.fmt(data.daily),
            unit = Units.money,
            label = tr("日均", "Daily avg"),
        )
    }
    return listOf(
        HeroStat(
            value = distance?.let { Format.fmt(it) } ?: "—",
            unit = if (distance != null) Units.distanceUnit else null,
            label = tr("里程", "Distance"),
        ),
        HeroStat(
            value = perKm?.let { Format.money(it) } ?: "—",
            unit = if (perKm != null) Units.money else null,
            label = tr("每公里", "Per km"),
        ),
        third,
    )
}

private fun heroSpendLabel(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> tr("本月支出", "This month")
    ReportKind.YEAR -> tr("本年支出", "This year")
    ReportKind.ALL -> tr("累计支出", "All time")
}

// ---------------------------------------------------------------------------------------------
// 对比（环比 / 同比 双条图）
// ---------------------------------------------------------------------------------------------

/** 对比卡的一款模式：0 = 环比，1 = 同比。 */
private data class CompareRow(
    val label: String,
    val current: Double,
    val previous: Double,
    val currentText: String,
    val previousText: String,
)

private data class CompareModel(
    /** 0 = 环比，1 = 同比。 */
    val modes: List<Int>,
    val rowsByMode: Map<Int, List<CompareRow>>,
    val previousLabel: Map<Int, String>,
)

/**
 * 环比 / 同比 的数据装配：只保留「本期与上一期都有数」的行，避免画空条。
 *
 * - 月报：环比 = 上月同期 / 上月；同比 = 去年同月（进行中取同期）。
 * - 年报：只有同比 = 上年（domain 里年报的 totalDelta 就是上年）。
 * - 累计：没有可比期，返回 null。
 */
private fun comparisonModel(data: ReportData, kind: ReportKind): CompareModel? {
    if (kind == ReportKind.ALL) return null
    val yoyTotal = if (kind == ReportKind.MONTH) data.yearAgoDelta?.previous else data.totalDelta?.previous
    val momTotal = if (kind == ReportKind.MONTH) data.totalDelta?.previous else null
    val momLabel = if (data.inProgress) tr("上月同期", "Last month") else tr("上月", "Last month")
    val yoyLabel = when {
        kind == ReportKind.YEAR -> tr("上年", "Last year")
        data.inProgress -> tr("去年同期", "A year ago")
        else -> tr("去年同月", "A year ago")
    }
    val modes = buildList {
        if (momTotal != null) add(0)
        if (yoyTotal != null) add(1)
    }
    if (modes.isEmpty()) return null

    fun rows(previousTotal: Double?, yoy: Boolean): List<CompareRow> {
        val rows = mutableListOf<CompareRow>()
        fun money(value: Double): String = "${Units.currencySymbol}${Format.money(value)}"
        val prevTotal = previousTotal?.takeIf { it > 0.0 }
        if (prevTotal != null && data.total > 0.0) {
            rows += CompareRow(
                label = tr("支出", "Spend"),
                current = data.total,
                previous = prevTotal,
                currentText = money(data.total),
                previousText = money(prevTotal),
            )
        }
        val prevDaily = (if (yoy) data.metrics.daily.yearAgo else data.metrics.daily.previous)?.takeIf { it > 0.0 }
        if (prevDaily != null) {
            rows += CompareRow(
                label = tr("日均", "Daily"),
                current = data.daily,
                previous = prevDaily,
                currentText = money(data.daily),
                previousText = money(prevDaily),
            )
        }
        val prevPerKm = (if (yoy) data.metrics.perKm.yearAgo else data.metrics.perKm.previous)?.takeIf { it > 0.0 }
        val perKm = data.perKm
        if (prevPerKm != null && perKm != null) {
            rows += CompareRow(
                label = tr("每公里", "Per km"),
                current = perKm,
                previous = prevPerKm,
                currentText = money(perKm),
                previousText = money(prevPerKm),
            )
        }
        val prevDistance = (if (yoy) data.metrics.distance.yearAgo else data.metrics.distance.previous)?.takeIf { it > 0.0 }
        val distance = data.distance
        if (prevDistance != null && distance != null) {
            rows += CompareRow(
                label = tr("里程", "Distance"),
                current = distance,
                previous = prevDistance,
                currentText = "${Format.fmt(distance)} ${Units.distanceUnit}",
                previousText = "${Format.fmt(prevDistance)} ${Units.distanceUnit}",
            )
        }
        return rows
    }

    val rowsByMode = buildMap {
        if (0 in modes) put(0, rows(momTotal, yoy = false))
        if (1 in modes) put(1, rows(yoyTotal, yoy = true))
    }
    if (rowsByMode.values.all { it.isEmpty() }) return null
    return CompareModel(
        modes = modes,
        rowsByMode = rowsByMode,
        previousLabel = mapOf(0 to momLabel, 1 to yoyLabel),
    )
}

@Composable
private fun CompareCard(data: ReportData, model: CompareModel) {
    val scheme = MaterialTheme.colorScheme
    var mode by rememberSaveable(data) { mutableIntStateOf(model.modes.first()) }
    val active = if (mode in model.modes) mode else model.modes.first()
    val rows = model.rowsByMode[active].orEmpty()
    val previousLabel = model.previousLabel[active].orEmpty()

    SectionCard(
        title = tr("对比", "Comparison"),
        action = {
            if (model.modes.size > 1) {
                ChartModeToggle(
                    labels = listOf(tr("环比", "MoM"), tr("同比", "YoY")),
                    selected = model.modes.indexOf(active),
                    onSelect = { index -> mode = model.modes[index] },
                )
            } else {
                Text(
                    text = tr("同比", "YoY"),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            LegendDot(color = scheme.primary, label = tr("本期", "This period"))
            LegendDot(color = scheme.outlineVariant, label = previousLabel)
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
            }
            CompareRowView(row = row, previousLabel = previousLabel)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompareRowView(row: CompareRow, previousLabel: String) {
    val scheme = MaterialTheme.colorScheme
    val max = maxOf(row.current, row.previous, 1e-9)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = tr(
                    "%s：本期 %s，%s %s",
                    "%s: now %s, %s %s",
                    row.label,
                    row.currentText,
                    previousLabel,
                    row.previousText,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(56.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            BarTrack(
                fraction = barFraction(row.current, max),
                color = scheme.primary,
                height = 6.dp,
            )
            BarTrack(
                fraction = barFraction(row.previous, max),
                color = scheme.outlineVariant,
                height = 6.dp,
            )
        }
        Column(
            modifier = Modifier.width(82.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = row.currentText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = row.previousText,
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 费用构成
// ---------------------------------------------------------------------------------------------

/** 分类明细的一行；[names] 是它包含的原始分类名（折叠的「其他 N 类」会有多个）。 */
private data class BreakdownRow(
    val key: String,
    val label: String,
    val value: Double,
    val color: Color,
    val names: List<String>,
)

@Composable
private fun BreakdownCard(ledger: Ledger, data: ReportData) {
    val palette = LocalMileNoteCategoryColors.current
    val dark = LocalMileNoteDarkTheme.current
    val profile = ledger.energyProfile
    val scheme = MaterialTheme.colorScheme
    val total = data.categories.sumOf { it.value }
    val shown = if (data.categories.size <= ReportCategoryRows) {
        data.categories
    } else {
        data.categories.take(ReportCategoryRows)
    }
    val rest = data.categories.drop(shown.size)
    val rows = buildList {
        shown.forEach { item ->
            add(
                BreakdownRow(
                    key = item.name,
                    label = DataLabels.categoryFor(ledger, item.name, profile),
                    value = item.value,
                    color = chartColorOf(item.name, palette, dark),
                    names = listOf(item.name),
                ),
            )
        }
        if (rest.isNotEmpty()) {
            add(
                BreakdownRow(
                    key = "other",
                    label = tr("其他 %d 类", "Other %d", rest.size),
                    value = rest.sumOf { it.value },
                    color = scheme.outlineVariant,
                    names = rest.map { it.name },
                ),
            )
        }
    }
    val slices = rows.map { BreakdownSlice(key = it.key, label = it.label, value = it.value, color = it.color) }

    SectionCard(
        title = tr("费用构成", "Breakdown"),
        action = {
            Text(
                text = tr("%d 个分类", "%d categories", data.categories.size),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        },
    ) {
        if (rows.isEmpty()) {
            Text(
                text = tr("本期没有支出", "No spending this period"),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CategoryBreakdown(
                slices = slices,
                total = total,
                modifier = Modifier.width(150.dp),
                showLegend = false,
                showCount = false,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(33.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${row.label} ${percentText(row.value, total)} ${moneyText(row.value)}"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(row.color),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = percentText(row.value, total),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = moneyText(row.value),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 能耗
// ---------------------------------------------------------------------------------------------

@Composable
private fun EnergyCard(ledger: Ledger, data: ReportData) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            data.fuel?.let { line ->
                EnergyBlock(
                    line = line,
                    electric = false,
                    title = tr("油耗", "Fuel economy"),
                    volumeUnit = Units.volumeUnit,
                    priceUnit = Units.pricePerVolume,
                    rateUnit = Units.consumption,
                    missingText = tr(
                        "本期没有加满记录，无法计算满箱油耗",
                        "No full tank this period, so fuel economy can't be computed",
                    ),
                )
            }
            if (data.fuel != null && data.electric != null) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            }
            data.electric?.let { line ->
                EnergyBlock(
                    line = line,
                    electric = true,
                    title = tr("电耗", "Energy use"),
                    volumeUnit = Units.energyUnit,
                    priceUnit = Units.pricePerEnergy,
                    rateUnit = Units.energyConsumption,
                    missingText = tr(
                        "本期没有充满记录，无法计算满电电耗",
                        "No full charge this period, so energy use can't be computed",
                    ),
                )
            }
        }
    }
}

@Composable
private fun EnergyBlock(
    line: ReportEnergyLine,
    electric: Boolean,
    title: String?,
    volumeUnit: String,
    priceUnit: String,
    rateUnit: String,
    missingText: String,
) {
    val scheme = MaterialTheme.colorScheme
    val rate = line.rate ?: line.estimated
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(scheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (electric) MileNoteIcons.VehicleElectric else MileNoteIcons.Fuel,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (rate != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = Format.fmt(rate),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = rateUnit,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                            maxLines = 1,
                        )
                        if (line.rate == null && line.estimated != null) {
                            Text(
                                text = tr("估算", "est."),
                                fontSize = 10.sp,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            val previousRate = line.previous?.rate
            if (previousRate != null && rate != null) {
                val diff = rate - previousRate
                val chip = when {
                    abs(diff) < 0.05 -> tr("基本持平", "About the same")
                    diff > 0 -> tr("↑ %s 较上月", "↑ %s vs last month", Format.fmt(diff))
                    else -> tr("↓ %s 较上月", "↓ %s vs last month", Format.fmt(abs(diff)))
                }
                Text(
                    text = chip,
                    fontSize = 11.sp,
                    color = scheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        val parts = mutableListOf(energyCountText(line.count, electric))
        if (line.amount > 0.0) parts += "${Format.fmt(line.amount)} $volumeUnit"
        line.unitPrice?.let { parts += "${Format.money(it)} $priceUnit" }
        Text(
            text = parts.joinToString(" · "),
            fontSize = 11.sp,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (line.intervals > 0) {
            Text(
                text = buildList {
                    add(spanCountText(line.intervals, electric))
                    if (line.intervals in 1..2 && rate != null) add(tr("样本少", "small sample"))
                }.joinToString(" · "),
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (rate == null) {
            Text(
                text = missingText,
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 趋势
// ---------------------------------------------------------------------------------------------

@Composable
private fun TrendCard(data: ReportData, today: LocalDate, onPickKey: (String) -> Unit) {
    val yearly = data.yearlyTrend.isNotEmpty()
    val bars = remember(data) {
        if (yearly) {
            data.yearlyTrend.map { ReportBar(key = it.year.toString(), label = it.year.toString(), value = it.total) }
        } else {
            data.monthlyTrend.map {
                ReportBar(
                    key = "%04d-%02d".format(it.year, it.month),
                    // 中文只放数字（12 个柱子放「12 月」会挤），英文放短月名。
                    label = if (I18n.english) monthShort(it.month) else it.month.toString(),
                    value = it.total,
                )
            }
        }
    }
    val highlightKey = when {
        yearly -> today.year.toString()
        data.period.kind == ReportKind.MONTH ->
            "%04d-%02d".format(data.period.year, data.period.month)
        data.period.kind == ReportKind.YEAR -> null
        else -> "%04d-%02d".format(today.year, today.monthValue)
    }
    val title = when {
        yearly -> tr("各年支出", "Spending by year")
        data.period.kind == ReportKind.YEAR -> tr("%d 年各月支出", "%d by month", data.period.year)
        else -> tr("近 12 个月", "Last 12 months")
    }
    SectionCard(
        title = title,
        action = {
            Text(
                text = tr("合计 %s", "Total %s", "${Units.currencySymbol}${Format.money(bars.sumOf { it.value })}"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        ReportTrendChart(
            bars = bars,
            highlightKey = highlightKey,
            onBarClick = onPickKey,
            chartHeight = 100.dp,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 之最
// ---------------------------------------------------------------------------------------------

@Composable
private fun HighlightsCard(ledger: Ledger, data: ReportData, today: LocalDate) {
    val profile = ledger.energyProfile
    val scheme = MaterialTheme.colorScheme
    val all = data.period.kind == ReportKind.ALL
    val todayIso = Dates.iso(today)
    SectionCard(
        title = when (data.period.kind) {
            ReportKind.MONTH -> tr("本月之最", "Month highlights")
            ReportKind.YEAR -> tr("年度之最", "Year highlights")
            ReportKind.ALL -> tr("历史之最", "All-time highlights")
        },
        action = {
            Text(
                text = tr("按记录算", "From records"),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        },
    ) {
        val tileCount = listOfNotNull(
            data.highlights.topExpense,
            data.highlights.priciestEnergy,
            data.highlights.topStation,
        ).size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            data.highlights.topExpense?.let { record ->
                HighlightTile(
                    modifier = Modifier.weight(1f),
                    icon = MileNoteIcons.Wallet,
                    label = tr("最大一笔", "Largest"),
                    value = "${Units.currencySymbol}${Format.money(record.amount)}",
                    sub = listOfNotNull(
                        DataLabels.categoryFor(ledger, record.category, profile),
                        recordDate(record, all),
                        if (record.date > todayIso) tr("预录", "scheduled") else null,
                    ).joinToString(" · "),
                )
            }
            data.highlights.priciestEnergy?.let { record ->
                val volume = record.liters ?: record.kwh
                val price = record.unitPrice?.takeIf { it > 0.0 }
                    ?: (record.amount / (volume ?: 1.0)).takeIf { volume != null && volume > 0.0 }
                val unit = if (record.kwh != null) Units.pricePerEnergy else Units.pricePerVolume
                HighlightTile(
                    modifier = Modifier.weight(1f),
                    icon = if (record.kwh != null) MileNoteIcons.VehicleElectric else MileNoteIcons.Fuel,
                    label = if (record.kwh != null) tr("最贵充电", "Priciest charge") else tr("最贵加油", "Priciest fill"),
                    value = if (price != null) "${Format.money(price)} $unit" else "—",
                    sub = listOfNotNull(
                        recordDate(record, all),
                        record.location?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
            data.highlights.topStation?.let { station ->
                HighlightTile(
                    modifier = Modifier.weight(1f),
                    icon = MileNoteIcons.Location,
                    label = tr("最常去", "Most visited"),
                    value = station.station,
                    sub = tr("%d 次", "%d visits", station.count),
                )
            }
            repeat((3 - tileCount).coerceAtLeast(0)) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (all) {
            val days = Companion.days(ledger.vehicle.companionDate)
            val span = spanText(data.spanMonths)
            Text(
                text = listOfNotNull(
                    days?.let { tr("陪伴 %d 天", "%d days together", it) },
                    span?.let { tr("记了 %s", "%s of records", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun HighlightTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sub: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerLow)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sub,
            fontSize = 10.sp,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 预录（未来日期）说明：累计口径已计入，月 / 年报未计入，文案必须说清楚，避免两个页面的数字看起来打架。
 * 分享卡的文本版也复用这一句。
 */
internal fun futureRecordNote(data: ReportData): String? {
    if (data.futureCount <= 0) return null
    val amount = "${Units.currencySymbol}${Format.money(data.futureTotal)}"
    return when {
        data.includesFuture && data.futureCount == 1 ->
            tr("含 1 笔未来日期记录（%s）", "Includes 1 future-dated entry (%s)", amount)
        data.includesFuture ->
            tr("含 %d 笔未来日期记录（%s）", "Includes %d future-dated entries (%s)", data.futureCount, amount)
        data.futureCount == 1 ->
            tr("另有 1 笔未来日期记录未计入（%s）", "1 future-dated entry not included (%s)", amount)
        else ->
            tr("另有 %d 笔未来日期记录未计入（%s）", "%d future-dated entries not included (%s)", data.futureCount, amount)
    }
}

private fun recordDate(record: Record, withYear: Boolean): String =
    if (withYear) Format.dateDots(record.date) else record.date.substring(5).replace('-', '.')

// ---------------------------------------------------------------------------------------------
// 空态 / 读账失败
// ---------------------------------------------------------------------------------------------

@Composable
private fun EmptyReportCard(
    data: ReportData,
    onPrev: () -> Unit,
    canPrev: Boolean,
    onAdd: () -> Unit,
) {
    val future = data.period.isFuture(Dates.today())
    val title = when {
        future && data.period.kind == ReportKind.MONTH ->
            tr("还没到 %d 年 %d 月", "Not there yet: %d/%d", data.period.year, data.period.month)
        future -> tr("还没到 %d 年", "Not there yet: %d", data.period.year)
        data.period.kind == ReportKind.ALL -> tr("还没有任何记录", "No entries yet")
        data.inProgress && data.period.kind == ReportKind.MONTH ->
            tr("本月还没有记录", "No entries this month")
        data.inProgress -> tr("今年还没有记录", "No entries this year")
        data.period.kind == ReportKind.MONTH ->
            tr("%d 年 %d 月还没有记录", "No entries in %s %d", data.period.year, data.period.month)
        else -> tr("%d 年还没有记录", "No entries in %d", data.period.year)
    }
    val description = when {
        future -> tr("等这一期到了再来看。", "Come back when this period starts.")
        data.inProgress ->
            tr("记一笔，或回看上一期。", "Add an entry, or look back at the previous period.")
        else -> tr("这一段还没有记账。", "Nothing was recorded in this period.")
    }
    EmptyState(
        title = title,
        description = description,
        icon = MileNoteIcons.FileText,
        actionLabel = if (canPrev) tr("查看上一期", "View previous") else null,
        onAction = if (canPrev) onPrev else null,
        secondaryLabel = tr("记一笔", "Add entry"),
        onSecondaryAction = onAdd,
    )
}

@Composable
private fun ReportLoadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MileNoteDimens.PageHorizontal),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MessageBar(
            message = tr(
                "本地数据读取异常，原始数据已另存。当前还没有记录，可继续使用或重新读取。",
                "The local data could not be read and a copy of the original was saved. You are starting with no records — keep using the app or re-read the original.",
            ),
            kind = MessageKind.ERROR,
            actionLabel = tr("重新读取数据", "Re-read the data"),
            onAction = onRetry,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 格式化辅助
// ---------------------------------------------------------------------------------------------

private fun moneyText(value: Double?): String =
    if (value == null) "—" else "${Units.currencySymbol}${Format.money(value)}"

/** `1 fill · 2 fills` / `加油 1 次`；英文单复数分开给，避免「1 fills」。 */
private fun energyCountText(count: Int, electric: Boolean): String = when {
    electric -> if (count == 1) tr("充电 %d 次", "%d charge", count) else tr("充电 %d 次", "%d charges", count)
    else -> if (count == 1) tr("加油 %d 次", "%d fill", count) else tr("加油 %d 次", "%d fills", count)
}

/** `1 full-tank span / 3 full-tank spans` / `满箱法 3 段`。 */
private fun spanCountText(intervals: Int, electric: Boolean): String = when {
    electric -> if (intervals == 1) {
        tr("满电法 %d 段", "%d full-charge span", intervals)
    } else {
        tr("满电法 %d 段", "%d full-charge spans", intervals)
    }
    else -> if (intervals == 1) {
        tr("满箱法 %d 段", "%d full-tank span", intervals)
    } else {
        tr("满箱法 %d 段", "%d full-tank spans", intervals)
    }
}

private fun spanText(months: Int?): String {
    if (months == null || months <= 0) return "—"
    val years = months / 12
    val rest = months % 12
    return when {
        years == 0 -> tr("%d 个月", "%d mo", rest)
        rest == 0 -> tr("%d 年", "%d y", years)
        else -> tr("%d 年 %d 个月", "%d y %d mo", years, rest)
    }
}
