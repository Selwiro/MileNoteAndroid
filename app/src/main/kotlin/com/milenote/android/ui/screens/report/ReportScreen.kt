package com.milenote.android.ui.screens.report

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
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
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.screens.dashboard.BreakdownSlice
import com.milenote.android.ui.screens.dashboard.CategoryBreakdown
import com.milenote.android.ui.screens.dashboard.chartColorOf
import com.milenote.android.ui.screens.dashboard.percentText
import com.milenote.android.ui.theme.LocalMileNoteCategoryColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.moneyMedium
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

/** 报告的费用构成明细最多显示几行（多的折成「其他 N 类」）。 */
private const val ReportCategoryRows = 8

/**
 * 用车报告（从「我的 → 数据与同步 → 用车报告」推入）。
 *
 * 三个 tab 共用同一套卡片，只换口径与文案：月报=自然月、年报=自然年、累计=首条记录 → 今天。
 * 默认打开「本月」（进行中，对比走同期口径）。
 *
 * 报告结构：总览（支出 + 环比的对比）→ 环比 / 同比指标 → 费用构成（环图 + 分类明细）
 * → 能源 → 趋势 → 之最。数字全部来自 domain 的 [report]；本页只做展示与周期切换，不写账本。
 *
 * @param vm 全局 ViewModel（当前车账本、演示态、读账错误）
 */
@Composable
fun ReportScreen(vm: MileNoteViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        val error = state.loadError
        if (error != null) {
            ReportLoadError(message = error, onRetry = vm::reloadLedger, modifier = modifier)
        } else {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState(label = tr("正在读取本机账本…", "Reading the local ledger…"))
            }
        }
        return
    }
    ReportContent(
        vm = vm,
        ledger = ledger,
        demo = state.demo,
        loadError = state.loadError,
        modifier = modifier,
    )
}

@Composable
private fun ReportContent(
    vm: MileNoteViewModel,
    ledger: Ledger,
    demo: Boolean,
    loadError: String?,
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
    var shareOpen by remember { mutableStateOf(false) }

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

    BackHandler(enabled = shareOpen) { shareOpen = false }

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MileNoteDimens.PageHorizontal,
            end = MileNoteDimens.PageHorizontal,
            top = MileNoteDimens.CardGap,
            bottom = MileNoteDimens.PageBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loadError != null) {
            item(key = "load-error") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageBar(
                        message = tr(
                            "本地账本读取异常，原始数据已另存。当前为空账本，可继续使用或重新读取。",
                            "The local ledger could not be read and a copy of the original was saved. You are on an empty ledger — keep using it or re-read the original.",
                        ),
                        kind = MessageKind.ERROR,
                        actionLabel = tr("重新读取账本", "Re-read the ledger"),
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
                MessageBar(
                    message = tr(
                        "演示账本 · 修改不会保存到真实账本",
                        "Demo ledger · Changes are not saved to your real ledger",
                    ),
                    kind = MessageKind.DEMO,
                    actionLabel = tr("退出演示", "Exit demo"),
                    onAction = { vm.exitDemo() },
                )
            }
        }
        item(key = "period") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            onSelect = { index -> kindName = ReportKind.entries[index].name },
                        )
                    }
                    if (kind == ReportKind.ALL) {
                        Text(
                            text = periodLabel(data, period),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        PeriodStepper(
                            label = periodLabel(data, period),
                            canPrev = canPrev,
                            canNext = canNext,
                            onPrev = ::goPrev,
                            onNext = ::goNext,
                            onPick = { pickerOpen = true },
                        )
                    }
                }
                Text(
                    text = rangeCaption(data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            item(key = "overview") {
                OverviewCard(ledger = ledger, data = data)
            }
            if (data.period.kind != ReportKind.ALL && data.hasEarlierRecords) {
                item(key = "comparison") {
                    ComparisonCard(data = data)
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
            item(key = "share") {
                TextButton(
                    onClick = { shareOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        MileNoteIcons.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr("分享报告", "Share report"))
                }
            }
        }
    }

        if (shareOpen) {
            ReportShareOverlay(data = data, ledger = ledger, onDismiss = { shareOpen = false })
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
// 周期头
// ---------------------------------------------------------------------------------------------

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

/** 周期头下的区间说明。 */
private fun rangeCaption(data: ReportData): String {
    if (data.period.kind == ReportKind.ALL) {
        val first = data.firstDate?.let { Dates.parse(it) }
        return if (first == null) {
            tr("全部记录", "All records")
        } else {
            tr(
                "%s – 今天 · 全部记录",
                "%s – today · all records",
                fullDate(first),
            )
        }
    }
    val from = Dates.parse(data.range.from) ?: return ""
    val to = Dates.parse(data.range.to) ?: return ""
    val span = "${dayLabel(from)} – ${dayLabel(to)}"
    return when {
        data.inProgress && data.period.kind == ReportKind.MONTH ->
            tr("%s · 本月进行中", "%s · this month, in progress", span)
        data.inProgress ->
            tr("%s · 今年进行中", "%s · this year, in progress", span)
        data.period.kind == ReportKind.MONTH ->
            tr("%s · 完整月", "%s · complete month", span)
        else ->
            tr("%s · 完整年", "%s · complete year", span)
    }
}

private fun dayLabel(date: LocalDate): String =
    if (I18n.english) "${monthShort(date.monthValue)} ${date.dayOfMonth}" else "${date.monthValue} 月 ${date.dayOfMonth} 日"

private fun fullDate(date: LocalDate): String =
    if (I18n.english) "${monthShort(date.monthValue)} ${date.dayOfMonth}, ${date.year}"
    else "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"

private fun monthShort(month: Int): String =
    Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

// ---------------------------------------------------------------------------------------------
// 总览
// ---------------------------------------------------------------------------------------------

@Composable
private fun OverviewCard(ledger: Ledger, data: ReportData) {
    val scheme = MaterialTheme.colorScheme
    val summary = tr(
        "%s支出 %s %s，%s",
        "%s spending %s %s, %s",
        periodLabel(data, data.period),
        Format.money(data.total),
        Units.money,
        deltaSummary(data),
    )
    SectionCard(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = summary },
    ) {
        Text(
            text = tr("支出合计", "Total spend"),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        MoneyText(
            amount = Format.money(data.total),
            size = MoneySize.LARGE,
            unit = Units.money,
            showCurrencySymbol = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (data.period.kind == ReportKind.ALL) {
            Text(
                text = tr(
                    "月均 %s · 记录跨度 %s",
                    "%s a month · %s of records",
                    money(data.monthlyAvg),
                    spanText(data.spanMonths),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            data.totalDelta?.let { delta ->
                if (delta.previous <= 0.0 && !data.hasEarlierRecords) {
                    // 账本的第一期：上一期本来就没数据，写「上月无支出」会被读成「上月没花钱」。
                    Text(
                        text = if (data.period.kind == ReportKind.YEAR) {
                            tr("账本从今年开始，暂无上期对比", "Ledger starts this year — no earlier period to compare")
                        } else {
                            tr("账本从本月开始，暂无上期对比", "Ledger starts this month — no earlier period to compare")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DeltaLine(
                        label = previousLabel(data.period.kind, data.inProgress),
                        text = deltaText(delta, previousEmptyLabel(data.period.kind, data.inProgress)),
                    )
                }
            }
            if (data.period.kind == ReportKind.MONTH) {
                data.yearAgoDelta?.let { delta ->
                    DeltaLine(
                        label = if (data.inProgress) {
                            tr("较去年同期", "vs same span last year")
                        } else {
                            tr("较去年同月", "vs same month last year")
                        },
                        text = deltaText(delta, tr("去年同月无支出", "No spending that month last year")),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReportMetric(
                    label = tr("记录", "Entries"),
                    value = data.recordCount.toString(),
                    unit = if (I18n.english) null else tr("笔", "entries"),
                    modifier = Modifier.weight(1f),
                )
                ReportMetric(
                    label = tr("里程", "Distance"),
                    value = data.distance?.let { Format.fmt(it) } ?: "—",
                    unit = if (data.distance != null) Units.distanceUnit else null,
                    caption = if (data.distance != null) null else tr("本期没有里程读数", "No odometer readings this period"),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (data.period.kind) {
                    ReportKind.MONTH -> {
                        ReportMetric(
                            label = tr("日均", "Daily avg"),
                            value = moneyOf(data.daily),
                            unit = Units.money,
                            modifier = Modifier.weight(1f),
                        )
                        ReportMetric(
                            label = tr("每公里", "Per km"),
                            value = data.perKm?.let { Format.money(it) } ?: "—",
                            unit = if (data.perKm != null) Units.costPerDistance else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ReportKind.YEAR -> {
                        ReportMetric(
                            label = tr("月均支出", "Monthly avg"),
                            value = moneyOf(data.monthlyAvg),
                            unit = Units.money,
                            modifier = Modifier.weight(1f),
                        )
                        ReportMetric(
                            label = tr("有记录月份", "Months with data"),
                            value = "${data.monthsWithData}/${elapsedMonths(data)}",
                            unit = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ReportKind.ALL -> {
                        ReportMetric(
                            label = tr("每公里", "Per km"),
                            value = data.perKm?.let { Format.money(it) } ?: "—",
                            unit = if (data.perKm != null) Units.costPerDistance else null,
                            modifier = Modifier.weight(1f),
                        )
                        ReportMetric(
                            label = tr("陪伴", "Together"),
                            value = Companion.days(ledger.vehicle.companionDate)?.toString() ?: "—",
                            unit = if (Companion.days(ledger.vehicle.companionDate) != null) tr("天", "days") else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        futureRecordNote(data)?.let { note ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 预录（未来日期）说明：累计口径已计入，月 / 年报未计入，文案必须说清楚，避免两个页面的数字看起来打架。
 * 分享卡与正文也复用这一句。
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

private fun elapsedMonths(data: ReportData): Int =
    if (data.inProgress) Dates.parse(data.range.to)?.monthValue ?: 12 else 12

@Composable
private fun DeltaLine(label: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun previousLabel(kind: ReportKind, inProgress: Boolean): String = when (kind) {
    ReportKind.MONTH -> if (inProgress) tr("较上月同期", "vs same span last month") else tr("较上月", "vs last month")
    ReportKind.YEAR -> if (inProgress) tr("较去年同期", "vs same span last year") else tr("较上年", "vs last year")
    ReportKind.ALL -> ""
}

private fun previousEmptyLabel(kind: ReportKind, inProgress: Boolean): String = when (kind) {
    ReportKind.MONTH -> if (inProgress) tr("上月同期无支出", "No spending in that span last month") else tr("上月无支出", "No spending last month")
    ReportKind.YEAR -> if (inProgress) tr("去年同期无支出", "No spending in that span last year") else tr("上年无支出", "No spending last year")
    ReportKind.ALL -> ""
}

/** `↓ ¥96.20 · 7.5%`；上期为 0 时给「无支出」，差值 < 0.5 元给「基本持平」。 */
private fun deltaText(delta: ReportDelta, emptyText: String): String {
    if (delta.previous <= 0.0) return emptyText
    if (abs(delta.diff) < 0.5) return tr("基本持平", "About the same")
    val arrow = if (delta.diff > 0) "↑" else "↓"
    val amount = "${Units.currencySymbol}${Format.money(abs(delta.diff))}"
    val percent = delta.percent
    // 与指标 / 分类行同一口径：基期很小时百分比会到五位数，改画倍数。
    if (percent != null && percent >= 900.0) {
        return "$arrow $amount · ${oneDecimal(delta.current / delta.previous)}×"
    }
    val suffix = percent?.let { " · ${oneDecimal(abs(it))}%" } ?: ""
    return "$arrow $amount$suffix"
}

private fun deltaSummary(data: ReportData): String {
    if (data.period.kind == ReportKind.ALL) {
        return tr(
            "月均 %s · 记录跨度 %s",
            "%s a month · %s of records",
            money(data.monthlyAvg),
            spanText(data.spanMonths),
        )
    }
    val delta = data.totalDelta ?: return ""
    return deltaText(delta, previousEmptyLabel(data.period.kind, data.inProgress))
}

@Composable
private fun ReportMetric(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
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
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    maxLines = 1,
                )
            }
        }
        if (caption != null) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 环比 / 同比
// ---------------------------------------------------------------------------------------------

/**
 * 指标对比卡：日均、每公里、记录、里程的环比 / 同比。
 *
 * 支出合计的环比 / 同比留在总览卡里（紧贴大金额，不重复画一遍）；
 * 累计视角没有可比的期（跨度不同），整张卡不出现。
 */
@Composable
private fun ComparisonCard(data: ReportData) {
    val month = data.period.kind == ReportKind.MONTH
    val metrics = data.metrics
    val records = metrics.records.current?.toInt() ?: 0
    SectionCard(
        title = if (month) tr("环比 · 同比", "MoM · YoY") else tr("同比", "YoY"),
        subtitle = if (month) {
            tr("较上月同期、去年同月", "vs last month and a year ago")
        } else {
            tr("较上年同期", "vs the same span last year")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MetricCompareRow(
                label = tr("日均", "Daily avg"),
                value = metrics.daily.current?.let { "${Units.currencySymbol}${Format.money(it)}" } ?: "—",
                monthOverMonth = metrics.daily.monthOverMonth,
                yearOverYear = metrics.daily.yearOverYear,
            )
            metrics.perKm.current?.let { perKm ->
                MetricCompareRow(
                    label = tr("每公里", "Per km"),
                    value = "${Units.currencySymbol}${Format.money(perKm)}",
                    monthOverMonth = metrics.perKm.monthOverMonth,
                    yearOverYear = metrics.perKm.yearOverYear,
                )
            }
            MetricCompareRow(
                label = tr("记录", "Entries"),
                value = if (records == 1) tr("1 笔", "1 entry") else tr("%d 笔", "%d entries", records),
                monthOverMonth = metrics.records.monthOverMonth,
                yearOverYear = metrics.records.yearOverYear,
                countUnit = tr("笔", "entries"),
            )
            metrics.distance.current?.let { distance ->
                MetricCompareRow(
                    label = tr("里程", "Distance"),
                    value = "${Format.fmt(distance)} ${Units.distanceUnit}",
                    monthOverMonth = metrics.distance.monthOverMonth,
                    yearOverYear = metrics.distance.yearOverYear,
                    countUnit = Units.distanceUnit,
                )
            }
        }
    }
}

@Composable
private fun MetricCompareRow(
    label: String,
    value: String,
    monthOverMonth: ReportDelta?,
    yearOverYear: ReportDelta?,
    countUnit: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
            )
        }
        val chips = buildList {
            monthOverMonth?.let { add(metricCompareChip(tr("环比", "MoM"), it, countUnit)) }
            yearOverYear?.let { add(metricCompareChip(tr("同比", "YoY"), it, countUnit)) }
        }
        Text(
            text = chips.ifEmpty { listOf(tr("暂无可比数据", "No comparison yet")) }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分类行的环比 / 同比片段：上期这一分类没有支出 →「新增」，差额 < 0.5 元 →「≈」，其余给增减幅度。
 *
 * 分类金额都是钱，0.5 元阈值与总览「基本持平」同一口径。
 */
internal fun categoryDeltaChip(label: String, current: Double, previous: Double): String {
    if (previous <= 0.0) return "$label ${tr("新增", "new")}"
    val diff = current - previous
    if (abs(diff) < 0.5) return "$label ≈"
    return "$label ${changeArrow(current, previous)}"
}

/**
 * 增减幅度：常规给百分比；涨幅 ≥ 900% 时改画倍数（`↑ 125.9×`），
 * 避免「12,486.8%」这种基期太小时看着像错数的百分比。
 *
 * 跌幅上限是 −100%（本期 ≥ 0），不需要倍数分支。
 */
private fun changeArrow(current: Double, previous: Double): String {
    val percent = (current - previous) / previous * 100.0
    if (percent >= 900.0) return "↑ ${oneDecimal(current / previous)}×"
    val arrow = if (percent > 0) "↑" else "↓"
    return "$arrow ${oneDecimal(abs(percent))}%"
}

/**
 * 指标行的环比 / 同比片段。
 *
 * [countUnit] 非空时用绝对值 + 单位（记录数 / 里程），否则用百分比（日均 / 每公里）。
 * 上期为 0 时百分比没有意义，统一写「上期无」，不画 ∞。
 */
internal fun metricCompareChip(label: String, delta: ReportDelta, countUnit: String? = null): String {
    if (delta.previous <= 0.0) return "$label ${tr("上期无", "no data")}"
    if (countUnit != null) {
        if (abs(delta.diff) < 0.5) return "$label ≈"
        val sign = if (delta.diff > 0) "+" else "−"
        return "$label $sign${Format.fmt(abs(delta.diff))} $countUnit"
    }
    val percent = delta.percent ?: return "$label ${tr("上期无", "no data")}"
    if (abs(percent) < 0.05) return "$label ≈"
    return "$label ${changeArrow(delta.current, delta.previous)}"
}

// ---------------------------------------------------------------------------------------------
// 费用构成（环图 + 分类明细）
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
        data.categories.take(ReportCategoryRows - 1)
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
                    color = scheme.onSurfaceVariant,
                    names = rest.map { it.name },
                ),
            )
        }
    }
    val slices = rows.map { BreakdownSlice(key = it.key, label = it.label, value = it.value, color = it.color) }

    SectionCard(title = tr("费用构成", "Breakdown")) {
        if (rows.isEmpty()) {
            // 有记录但全是 0 元（例如免费充电）：环图画不出来，别让「等待你的第一笔记录」误导。
            Text(
                text = tr("本期没有支出", "No spending this period"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        // 环图本身不再画横排图例：下面的明细行带色点、金额、占比和环比 / 同比，就是图例。
        CategoryBreakdown(slices = slices, total = total, showLegend = false)
        Spacer(modifier = Modifier.height(12.dp))
        rows.forEach { row ->
            CategoryCompareRow(
                label = row.label,
                value = row.value,
                total = total,
                color = row.color,
                previous = data.previousCategories?.let { map -> row.names.sumOf { map[it] ?: 0.0 } },
                yearAgo = data.yearAgoCategories?.let { map -> row.names.sumOf { map[it] ?: 0.0 } },
            )
        }
    }
}

/**
 * 分类明细行：色点 + 名称 + 金额 + 占比，第二行是环比 / 同比。
 *
 * [previous] / [yearAgo] 为 null 表示对比期完全没有记录（整行不画对比）；
 * 0.0 表示对比期有记录、但这一分类没有支出（写「新增」而不是 ∞%）。
 */
@Composable
private fun CategoryCompareRow(
    label: String,
    value: Double,
    total: Double,
    color: Color,
    previous: Double?,
    yearAgo: Double?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${Units.currencySymbol}${Format.money(value)}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = percentText(value, total),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
        }
        val chips = buildList {
            previous?.let { add(categoryDeltaChip(tr("环比", "MoM"), value, it)) }
            yearAgo?.let { add(categoryDeltaChip(tr("同比", "YoY"), value, it)) }
        }
        if (chips.isNotEmpty()) {
            Text(
                text = chips.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 能源
// ---------------------------------------------------------------------------------------------

@Composable
private fun EnergyCard(ledger: Ledger, data: ReportData) {
    val title = when {
        data.fuel != null && data.electric != null -> tr("加油、充电与能耗", "Fuel, charging & energy")
        data.electric != null -> tr("充电与电耗", "Charging")
        else -> tr("加油与油耗", "Refueling")
    }
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            data.fuel?.let { line ->
                EnergyFlow(
                    line = line,
                    title = if (data.electric != null) tr("油耗", "Fuel") else null,
                    electric = false,
                    volumeUnit = Units.volumeUnit,
                    priceUnit = Units.pricePerVolume,
                    rateLabel = tr("油耗", "Fuel economy"),
                    rateUnit = Units.consumption,
                    missingText = tr(
                        "本期没有加满记录，无法计算满箱油耗",
                        "No full tank this period, so fuel economy can't be computed",
                    ),
                    compareLabel = previousLabel(data.period.kind, data.inProgress),
                )
            }
            if (data.fuel != null && data.electric != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            data.electric?.let { line ->
                EnergyFlow(
                    line = line,
                    title = if (data.fuel != null) tr("电耗", "Electric") else null,
                    electric = true,
                    volumeUnit = Units.energyUnit,
                    priceUnit = Units.pricePerEnergy,
                    rateLabel = tr("电耗", "Energy use"),
                    rateUnit = Units.energyConsumption,
                    missingText = tr(
                        "本期没有充满记录，无法计算满电电耗",
                        "No full charge this period, so energy use can't be computed",
                    ),
                    compareLabel = previousLabel(data.period.kind, data.inProgress),
                )
            }
        }
    }
}

@Composable
private fun EnergyFlow(
    line: ReportEnergyLine,
    title: String?,
    electric: Boolean,
    volumeUnit: String,
    priceUnit: String,
    rateLabel: String,
    rateUnit: String,
    missingText: String,
    compareLabel: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        val parts = mutableListOf(energyCountText(line.count, electric))
        if (line.amount > 0.0) parts += "${Format.fmt(line.amount)} $volumeUnit"
        // priceUnit 已含货币名（元/升、USD/L），不要再拼一次 ¥ / $。
        line.unitPrice?.let { parts += "${Format.money(it)} $priceUnit" }
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        val currentRate = line.rate
        val estimate = line.estimated
        // 完全没有能耗数字时不画「油耗 —」这一行，只留下面那句解释，避免同一件事说两遍。
        if (currentRate != null || estimate != null) {
            Text(
                text = if (currentRate != null) {
                    tr("%s %s %s", "%s %s %s", rateLabel, Format.fmt(currentRate), rateUnit)
                } else {
                    tr("累计估算约 %s %s", "Rough estimate %s %s", Format.fmt(estimate), rateUnit)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 样本说明单独一行（段数 + 「样本少」）：塞在油耗同一行时 1.3× 英文会被裁成「smal」。
        if (line.intervals > 0) {
            Text(
                text = buildList {
                    add(spanCountText(line.intervals, electric))
                    if (line.intervals in 1..2 && currentRate != null) add(tr("样本少", "small sample"))
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val previous = line.previous
        val previousRate = previous?.rate
        if (currentRate != null && previousRate != null) {
            val diff = currentRate - previousRate
            val arrow = if (diff > 0) "↑" else if (diff < 0) "↓" else ""
            val diffText = if (abs(diff) < 0.05) {
                tr("基本持平", "About the same")
            } else {
                "$arrow ${Format.fmt(abs(diff))} ${rateUnit}"
            }
            Text(
                text = tr(
                    "%s %s（上期 %s · %s）",
                    "%s %s (previous %s · %s)",
                    compareLabel,
                    diffText,
                    Format.fmt(previousRate),
                    spanCountText(previous.intervals, electric),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
            )
        } else if (line.rate == null && line.estimated == null) {
            Text(
                text = missingText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

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
        else -> tr("近 12 个月支出", "Last 12 months")
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
        ReportTrendChart(bars = bars, highlightKey = highlightKey, onBarClick = onPickKey)
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
    SectionCard(
        title = when (data.period.kind) {
            ReportKind.MONTH -> tr("本月之最", "Month highlights")
            ReportKind.YEAR -> tr("年度之最", "Year highlights")
            ReportKind.ALL -> tr("历史之最", "All-time highlights")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val todayIso = Dates.iso(today)
            fun withFutureTag(record: Record, base: String): String =
                if (record.date > todayIso) base + " · " + tr("预录", "scheduled") else base
            data.highlights.topExpense?.let { record ->
                HighlightRow(
                    label = tr("最大一笔", "Largest expense"),
                    value = withFutureTag(
                        record,
                        listOfNotNull(
                            DataLabels.categoryFor(ledger, record.category, profile),
                            "${Units.currencySymbol}${Format.money(record.amount)}",
                            recordDate(record, all),
                        ).joinToString(" · "),
                    ),
                )
            }
            data.highlights.priciestEnergy?.let { record ->
                val volume = record.liters ?: record.kwh
                val price = record.unitPrice?.takeIf { it > 0.0 }
                    ?: (record.amount / (volume ?: 1.0)).takeIf { volume != null && volume > 0.0 }
                val unit = if (record.kwh != null) Units.pricePerEnergy else Units.pricePerVolume
                HighlightRow(
                    label = if (record.kwh != null) tr("最贵充电", "Priciest charge") else tr("最贵加油", "Priciest fill-up"),
                    value = withFutureTag(
                        record,
                        listOfNotNull(
                            price?.let { "${Format.money(it)} $unit" },
                            recordDate(record, all),
                            record.location?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                    ),
                )
            }
            data.highlights.topStation?.let { station ->
                HighlightRow(
                    label = tr("最常去", "Most visited"),
                    value = "${station.station} · ${tr("%d 次", "%d visits", station.count)}",
                )
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
                )
            }
        }
    }
}

@Composable
private fun HighlightRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                "本地账本读取异常，原始数据已另存。当前为空账本，可继续使用或重新读取。",
                "The local ledger could not be read and a copy of the original was saved. You are on an empty ledger — keep using it or re-read the original.",
            ),
            kind = MessageKind.ERROR,
            actionLabel = tr("重新读取账本", "Re-read the ledger"),
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

private fun money(value: Double?): String =
    if (value == null) "—" else "${Units.currencySymbol}${Format.money(value)}"

private fun moneyOf(value: Double?): String = value?.let { Format.money(it) } ?: "—"

private fun oneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return Format.fmt(rounded)
}

/** `3 年 7 个月` / `3 y 7 m`；跨度为 0 时返回 `—`。 */
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
