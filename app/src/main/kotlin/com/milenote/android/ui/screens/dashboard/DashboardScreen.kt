package com.milenote.android.ui.screens.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.milenote.android.R
import com.milenote.android.domain.calc.Companion
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.ReminderLevel
import com.milenote.android.domain.calc.StatsResult
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.groupReminders
import com.milenote.android.domain.calc.reminderState
import com.milenote.android.domain.calc.serviceProgress
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.navigation.Routes
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.UiState
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.HeroBadge
import com.milenote.android.ui.components.HeroCarArt
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteCategoryColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.LocalMileNoteHeroGradient
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.moneyMedium
import com.milenote.android.util.DataUrlImages
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** 紧凑 Hero 高度：只保留车辆身份与插画，不再承载里程/花销数据。 */
private val CompactHeroHeight = 156.dp

/** Hero 右侧插画区域（96×88dp；HeroCarArt 为 2.38:1 宽幅，横向自适应）。 */
private val HeroArtWidth = 96.dp
private val HeroArtHeight = 88.dp

/**
 * 总览页：紧凑 Hero 车辆卡 + 用车统计大卡（2×2 指标 + 右上时间筛选）+ 里程趋势 + 爱车待办。
 *
 * 页面不自带 Scaffold / 顶栏 / 底栏 / FAB；只输出滚动内容，并按 [MileNoteDimens.PageBottom]
 * 预留底部空间。统计范围使用 [UiState.effectiveRange]（自定义范围优先），只影响指标 / 构成；
 * 「支出趋势 / 费用构成」已移到费用统计页；本页图表 / 待办与统计卡统一为同一种 SectionCard 版式。
 *
 * @param vm 全局 ViewModel（期间 / 自定义范围 / 编辑记录 / 演示）。
 * @param onNavigate 顶层路由跳转（记录、油耗、保养、设置）。
 */
@Composable
fun DashboardScreen(
    vm: MileNoteViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        DashboardLoadState(state = state, onNavigate = onNavigate, modifier = modifier)
        return
    }
    DashboardContent(
        state = state,
        ledger = ledger,
        onNavigate = onNavigate,
        onAddRecord = { vm.openEditor() },
        onSelectPeriod = vm::setPeriod,
        onSelectCustomRange = { from, to -> vm.setCustomRange(from, to) },
        onEnterDemo = vm::enterDemo,
        onExitDemo = vm::exitDemo,
        modifier = modifier,
    )
}

@Composable
private fun DashboardContent(
    state: UiState,
    ledger: Ledger,
    onNavigate: (String) -> Unit,
    onAddRecord: () -> Unit,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
    onEnterDemo: () -> Unit,
    onExitDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { Dates.today() }
    val range = remember(state.period, state.customRange, today) { state.effectiveRange(today) }
    val periodStats = remember(ledger, range) { stats(ledger, range.from, range.to) }
    val nearReminders = remember(ledger.reminders, today) {
        groupReminders(ledger.reminders, today).near.take(3)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MileNoteDimens.PageHorizontal,
            end = MileNoteDimens.PageHorizontal,
            top = MileNoteDimens.CardGap,
            bottom = MileNoteDimens.PageBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.demo) {
            item(key = "demo-banner") {
                DemoBanner(onExitDemo = onExitDemo)
            }
        }
        state.loadError?.let { loadError ->
            item(key = "load-error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MessageBar(
                        message = "本地账本读取异常，已暂停保存。请先导出原始数据检查。",
                        kind = MessageKind.ERROR,
                    )
                    Text(
                        text = loadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "hero") {
            VehicleHeroCard(ledger = ledger, onNavigate = onNavigate)
        }
        if (ledger.records.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "从第一笔记录开始",
                    description = "手动记账，或导入你已有的小熊油耗、钱迹数据。",
                    illustration = painterResource(R.drawable.ill_empty_records),
                    actionLabel = "记一笔",
                    onAction = onAddRecord,
                    secondaryLabel = "看看演示",
                    onSecondaryAction = onEnterDemo,
                )
            }
            return@LazyColumn
        }

        item(key = "stats") {
            VehicleStatsCard(
                period = state.period,
                customRange = state.customRange,
                statsResult = periodStats,
                onSelectPeriod = onSelectPeriod,
                onSelectCustomRange = onSelectCustomRange,
            )
        }
        item(key = "reminders") {
            NearRemindersCard(
                ledger = ledger,
                reminders = nearReminders,
                today = today,
                onNavigate = onNavigate,
            )
        }
        item(key = "mileage") {
            MileageTrendCard(
                ledger = ledger,
                period = state.period,
                customRange = state.customRange,
                range = range,
                onSelectPeriod = onSelectPeriod,
                onSelectCustomRange = onSelectCustomRange,
            )
        }

    }

}

/** 账本尚未加载 / 读取失败时的占位。 */
@Composable
private fun DashboardLoadState(
    state: UiState,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.loadError
    if (error == null) {
        LoadingState(
            label = "正在读取本机账本…",
            modifier = modifier
                .fillMaxSize()
                .padding(top = 64.dp),
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(MileNoteDimens.PageHorizontal),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MessageBar(
            message = "本地账本暂时无法读取或迁移。原记录仍保留，请导出原始数据检查；已暂停保存。",
            kind = MessageKind.ERROR,
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onNavigate(Routes.SETTINGS) }) {
            Text("前往设置导出原始数据")
        }
    }
}

/** 演示账本横幅（唯一紫色语义，设计规范 §2.5）。 */
@Composable
private fun DemoBanner(onExitDemo: () -> Unit) {
    MessageBar(
        message = "演示账本 · 可切换车辆、试记账与换肤，退出后不保留修改",
        kind = MessageKind.DEMO,
        actionLabel = "退出演示",
        onAction = onExitDemo,
    )
}

/**
 * 紧凑 Hero 车辆卡（约 156dp 高）：只保留车名、车型/车牌、陪伴/记录徽章与右侧车辆插画。
 *
 * 不再显示当前里程、本月支出等数据（已移到「用车统计」卡）；整卡点按进入设置 / 管理车辆。
 * 自定义 Row 布局可确保文字区与插画互不遮挡。
 */
@Composable
private fun VehicleHeroCard(
    ledger: Ledger,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vehicle = ledger.vehicle
    val days = Companion.days(vehicle.companionDate)
    val badge = heroBadge(ledger, days)
    val gradient = LocalMileNoteHeroGradient.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CompactHeroHeight)
            .clip(MileNoteShapes.extraLarge)
            .background(Brush.linearGradient(gradient.colors))
            .clickable(onClick = { onNavigate(Routes.SETTINGS) }),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HeroBadge(text = badge.first, icon = badge.second)
                Text(
                    text = vehicle.name.ifBlank { "我的爱车" },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = gradient.contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = heroSubtitle(ledger, days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = gradient.contentColor.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            HeroVehicleArt(
                vehicle = vehicle,
                modifier = Modifier
                    .width(HeroArtWidth)
                    .height(HeroArtHeight),
            )
        }
    }
}

/**
 * 紧凑 Hero 副标题：车型/车牌优先；有陪伴天数/记录数时由徽章承载，副标题不再重复；
 * 无数据时保留「添加车辆信息，开启你的用车账本」引导文案。
 */
private fun heroSubtitle(ledger: Ledger, days: Int?): String {
    val vehicle = ledger.vehicle
    val modelPlate = listOf(vehicle.model, vehicle.plate)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    if (modelPlate.isNotEmpty()) return modelPlate

    // 徽章已显示「陪伴 X 天 · N 笔」或「已记录 N 笔」时，副标题只提示完善信息，避免重复。
    if (ledger.records.isNotEmpty() || (days != null && days > 0)) return "点击完善车辆信息"

    val km = currentKm(ledger)
    if (km > 0) return "当前里程 ${Format.km(km)} · 点击完善车辆信息"
    if (vehicle.name.isNotBlank() && vehicle.name != "我的爱车") return "点击完善车辆信息"
    return "添加车辆信息，开启你的用车账本"
}

/**
 * 紧凑 Hero 徽章：有提车日期显示陪伴天数（并有记录时附带笔数）；
 * 否则显示记录数或「完善车辆信息」，不再出现「设置提车日期」假按钮。
 */
private fun heroBadge(ledger: Ledger, days: Int?): Pair<String, ImageVector> {
    val recordCount = ledger.records.size
    if (days != null && days > 0) {
        val text = if (recordCount > 0) "陪伴 $days 天 · $recordCount 笔" else "陪伴 $days 天"
        return text to MileNoteIcons.Calendar
    }
    if (recordCount > 0) return "已记录 $recordCount 笔" to MileNoteIcons.Records
    return "完善车辆信息" to MileNoteIcons.Edit
}

/** 紧凑 Hero 右侧插画：用户图片优先（圆角 + 白描边 + 轻阴影）；无图 / 解码失败用品牌车插画。 */
@Composable
private fun HeroVehicleArt(vehicle: Vehicle, modifier: Modifier = Modifier) {
    val image = vehicle.image
    val contentScale = if (vehicle.imageFit == "cover") ContentScale.Crop else ContentScale.Fit
    val shape = RoundedCornerShape(16.dp)
    when {
        DataUrlImages.isDataUrl(image) -> {
            val bitmap = remember(image) { DataUrlImages.decode(image) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = vehicle.name.ifBlank { "车辆图片" },
                    modifier = modifier
                        .shadow(elevation = 4.dp, shape = shape, clip = false)
                        .border(width = 2.dp, color = Color.White.copy(alpha = 0.82f), shape = shape)
                        .clip(shape),
                    contentScale = contentScale,
                )
            } else {
                HeroCarArt(modifier = modifier)
            }
        }
        !image.isNullOrBlank() -> {
            AsyncImage(
                model = image,
                contentDescription = vehicle.name.ifBlank { "车辆图片" },
                modifier = modifier
                    .shadow(elevation = 4.dp, shape = shape, clip = false)
                    .border(width = 2.dp, color = Color.White.copy(alpha = 0.82f), shape = shape)
                    .clip(shape),
                placeholder = painterResource(R.drawable.milenote_car_hero),
                error = painterResource(R.drawable.milenote_car_hero),
                contentScale = contentScale,
            )
        }
        else -> HeroCarArt(modifier = modifier)
    }
}

/**
 * 总览统一卡片容器：`SectionCard` + 卡内标题行（titleMediumEmphasized）+ 可选一行功能说明 / 右侧动作。
 *
 * 支出趋势、费用构成、用车统计、爱车待办共用同一版式与内边距，卡片之间只靠 12dp 间距分组。
 */
@Composable
private fun DashboardCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            action?.invoke()
        }
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

/**
 * 用车统计大卡：2×2 指标（label + 数值 + 单位，无说明 caption）+ 右上角时间筛选。
 *
 * 时间筛选：全部 / 本月 / 今年 / 自定义（M3 DateRangePicker）。自定义范围优先，
 * 所有指标随 [range] 联动；数值口径仍由 domain `stats` 计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehicleStatsCard(
    period: Period,
    customRange: DateRange?,
    statsResult: StatsResult,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMileNoteSemanticColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }

    DashboardCard(
        modifier = modifier,
        title = "用车统计",
        action = {
            RangeFilterButton(
                label = rangeLabel(period = period, customRange = customRange),
                expanded = menuExpanded,
                onToggle = { menuExpanded = true },
                onDismiss = { menuExpanded = false },
                period = period,
                customRange = customRange,
                onSelectPeriod = {
                    onSelectPeriod(it)
                    menuExpanded = false
                },
                onSelectCustom = {
                    menuExpanded = false
                    showRangePicker = true
                },
            )
        },
    ) {
        // 2×2 指标：用行/列间距与对齐形成分组（不用分割线）。
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatCell(
                    label = "用车支出",
                    value = Format.money(statsResult.total),
                    unit = "元",
                    icon = MileNoteIcons.Wallet,
                    accent = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "行驶里程",
                    value = Format.fmt(statsResult.distance),
                    unit = "公里",
                    icon = MileNoteIcons.Road,
                    accent = semantic.info.content,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatCell(
                    label = "用车成本",
                    value = statsResult.perKm?.let { Format.money(it) } ?: "—",
                    unit = if (statsResult.perKm == null) null else "元/公里",
                    icon = MileNoteIcons.Chart,
                    accent = scheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "平均油耗",
                    value = statsResult.fuelRate?.let { Numbers.toFixed2(it) } ?: "—",
                    unit = if (statsResult.fuelRate == null) null else "升/百公里",
                    icon = MileNoteIcons.Fuel,
                    accent = semantic.success.content,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                onSelectCustomRange(from, to)
                showRangePicker = false
            },
        )
    }
}

/** 卡内右上角胶囊按钮 + DropdownMenu（用车统计 / 支出趋势 / 费用构成共用样式）。 */
@Composable
private fun RangePillMenu(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                    contentDescription = "选择统计范围",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)
    }
}

/** 统计范围按钮 + DropdownMenu（全部 / 本月 / 今年 / 自定义）。 */
@Composable
private fun RangeFilterButton(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    period: Period,
    customRange: DateRange?,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustom: () -> Unit,
) {
    RangePillMenu(
        label = label,
        expanded = expanded,
        onToggle = onToggle,
        onDismiss = onDismiss,
    ) {
        val presetSelected = customRange == null
        DropdownMenuItem(
            text = { Text("全部") },
            trailingIcon = checkIcon(presetSelected && period == Period.ALL),
            onClick = { onSelectPeriod(Period.ALL) },
        )
        DropdownMenuItem(
            text = { Text("本月") },
            trailingIcon = checkIcon(presetSelected && period == Period.MONTH),
            onClick = { onSelectPeriod(Period.MONTH) },
        )
        DropdownMenuItem(
            text = { Text("今年") },
            trailingIcon = checkIcon(presetSelected && period == Period.YEAR),
            onClick = { onSelectPeriod(Period.YEAR) },
        )
        DropdownMenuItem(
            text = { Text("自定义…") },
            trailingIcon = checkIcon(customRange != null),
            onClick = onSelectCustom,
        )
    }
}

/**
 * 里程趋势卡：右上角与「用车统计」完全一致的时间筛选（全部/本月/今年/自定义），
 * 折线图按所选范围的自然月展示每月行驶里程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MileageTrendCard(
    ledger: Ledger,
    period: Period,
    customRange: DateRange?,
    range: DateRange,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    val bars = remember(ledger, range) { buildMileageBars(ledger, range) }

    DashboardCard(
        title = "里程趋势",
        action = {
            RangeFilterButton(
                label = rangeLabel(period = period, customRange = customRange),
                expanded = menuExpanded,
                onToggle = { menuExpanded = true },
                onDismiss = { menuExpanded = false },
                period = period,
                customRange = customRange,
                onSelectPeriod = {
                    onSelectPeriod(it)
                    menuExpanded = false
                },
                onSelectCustom = {
                    menuExpanded = false
                    showRangePicker = true
                },
            )
        },
    ) {
        MileageTrendChart(bars = bars)
    }

    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                onSelectCustomRange(from, to)
                showRangePicker = false
            },
        )
    }
}

/** 支出趋势的时间维度。 */
private enum class TrendRangeMode(val label: String) {
    HALF("半年"),
    YEAR("一年"),
    CUSTOM("自定义月份"),
}

/**
 * 支出趋势卡：右上角月份区间筛选（半年默认 / 一年 / 自定义月份），图表按月聚合。
 *
 * 半年 = 最近 6 个自然月，一年 = 最近 12 个自然月；自定义月份最多 24 个月。
 */
@Composable
internal fun ExpenseTrendCard(ledger: Ledger, today: LocalDate) {
    var modeName by rememberSaveable { mutableStateOf(TrendRangeMode.HALF.name) }
    // 空串表示未选择（rememberSaveable 对 String? 的保存兼容性更稳）。
    var customStart by rememberSaveable { mutableStateOf("") }
    var customEnd by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }

    val mode = TrendRangeMode.entries.firstOrNull { it.name == modeName } ?: TrendRangeMode.HALF
    val months = remember(mode, customStart, customEnd, today) {
        trendMonths(mode, customStart.ifBlank { null }, customEnd.ifBlank { null }, today)
    }
    val palette = LocalMileNoteCategoryColors.current
    val darkTheme = LocalMileNoteDarkTheme.current
    val bars = remember(ledger, palette, darkTheme, months) {
        buildMonthBars(ledger, palette, darkTheme, months)
    }

    DashboardCard(
        title = "支出趋势",
        action = {
            RangePillMenu(
                label = trendRangeLabel(mode, customStart.ifBlank { null }, customEnd.ifBlank { null }, months),
                expanded = menuExpanded,
                onToggle = { menuExpanded = true },
                onDismiss = { menuExpanded = false },
            ) {
                TrendRangeMode.entries.forEach { entry ->
                    if (entry == TrendRangeMode.CUSTOM) {
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            trailingIcon = checkIcon(mode == entry),
                            onClick = {
                                menuExpanded = false
                                showMonthPicker = true
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            trailingIcon = checkIcon(mode == entry),
                            onClick = {
                                modeName = entry.name
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        },
    ) {
        ExpenseTrendChart(bars = bars)
    }

    if (showMonthPicker) {
        val defaultMonths = remember(mode, customStart, customEnd, months) {
            recentMonths(today, 6)
        }
        MonthRangePickerDialog(
            initialStartMonth = customStart.ifBlank { monthKey(defaultMonths.first()) },
            initialEndMonth = customEnd.ifBlank { monthKey(defaultMonths.last()) },
            onDismiss = { showMonthPicker = false },
            onConfirm = { start, end ->
                customStart = start
                customEnd = end
                modeName = TrendRangeMode.CUSTOM.name
                showMonthPicker = false
            },
        )
    }
}

/** 最近 [count] 个自然月（含当月，升序）。 */
private fun recentMonths(today: LocalDate, count: Int): List<YearMonth> {
    val end = YearMonth.from(today)
    return (count - 1 downTo 0).map { end.minusMonths(it.toLong()) }
}

/** 按筛选模式生成月份列表；自定义范围非法 / 为空时回退半年。 */
private fun trendMonths(
    mode: TrendRangeMode,
    startMonth: String?,
    endMonth: String?,
    today: LocalDate,
): List<YearMonth> = when (mode) {
    TrendRangeMode.HALF -> recentMonths(today, 6)
    TrendRangeMode.YEAR -> recentMonths(today, 12)
    TrendRangeMode.CUSTOM -> {
        val start = startMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val end = endMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (start != null && end != null && start <= end) {
            monthsBetween(start, end).take(24)
        } else {
            recentMonths(today, 6)
        }
    }
}

/** 起止月（含）之间的月份列表。 */
private fun monthsBetween(start: YearMonth, end: YearMonth): List<YearMonth> {
    val months = mutableListOf<YearMonth>()
    var current = start
    while (current <= end) {
        months += current
        current = current.plusMonths(1)
    }
    return months
}

/** 趋势筛选标签：半年 / 一年 / 自定义月份（同年 `yyyy.MM–MM`，跨年完整）。 */
private fun trendRangeLabel(
    mode: TrendRangeMode,
    startMonth: String?,
    endMonth: String?,
    months: List<YearMonth>,
): String = when (mode) {
    TrendRangeMode.HALF -> TrendRangeMode.HALF.label
    TrendRangeMode.YEAR -> TrendRangeMode.YEAR.label
    TrendRangeMode.CUSTOM -> {
        val start = startMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val end = endMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        when {
            start != null && end != null && start.year == end.year ->
                "${start.year}.${month2(start)}–${month2(end)}"
            start != null && end != null ->
                "${start.year}.${month2(start)}–${end.year}.${month2(end)}"
            months.isNotEmpty() -> "${months.size} 个月"
            else -> TrendRangeMode.CUSTOM.label
        }
    }
}

private fun month2(month: YearMonth): String = month.monthValue.toString().padStart(2, '0')

/** 自定义月份区间选择（两个月份下拉，最多 24 个月）。 */
@Composable
private fun MonthRangePickerDialog(
    initialStartMonth: String,
    initialEndMonth: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val today = remember { Dates.today() }
    val options = remember(today) {
        (0 until 36).map { YearMonth.from(today).minusMonths(it.toLong()) }.reversed()
    }
    var start by remember {
        mutableStateOf(runCatching { YearMonth.parse(initialStartMonth) }.getOrElse { YearMonth.from(today) })
    }
    var end by remember {
        mutableStateOf(runCatching { YearMonth.parse(initialEndMonth) }.getOrElse { YearMonth.from(today) })
    }
    val span = if (start <= end) monthsBetween(start, end).size else 0
    val valid = start <= end && span in 1..24

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = { Text("自定义月份", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "选择起止月份，最多 24 个月。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonthDropdown(label = "起始月", selected = start, options = options, onSelect = { start = it })
                MonthDropdown(label = "结束月", selected = end, options = options, onSelect = { end = it })
                when {
                    start > end -> Text(
                        text = "结束月不能早于起始月",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    span > 24 -> Text(
                        text = "月份范围最多 24 个月（当前 $span 个月）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(monthKey(start), monthKey(end)) },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 单个月份下拉选择。 */
@Composable
private fun MonthDropdown(
    label: String,
    selected: YearMonth,
    options: List<YearMonth>,
    onSelect: (YearMonth) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MileNoteShapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${selected.year}.${month2(selected)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.year}.${month2(option)}") },
                    trailingIcon = checkIcon(option == selected),
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 费用构成卡：右上角统计范围（全部 / 本月 / 今年 / 自定义，与用车统计联动），
 * 环图 + 下方横排分类图例。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompositionCard(
    period: Period,
    customRange: DateRange?,
    statsResult: StatsResult,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }

    DashboardCard(
        title = "费用构成",
        action = {
            RangeFilterButton(
                label = rangeLabel(period = period, customRange = customRange),
                expanded = menuExpanded,
                onToggle = { menuExpanded = true },
                onDismiss = { menuExpanded = false },
                period = period,
                customRange = customRange,
                onSelectPeriod = {
                    onSelectPeriod(it)
                    menuExpanded = false
                },
                onSelectCustom = {
                    menuExpanded = false
                    showRangePicker = true
                },
            )
        },
    ) {
        CategoryBreakdown(
            categories = statsResult.categories,
            total = statsResult.total,
        )
    }

    if (showRangePicker) {
        CustomRangePickerDialog(
            customRange = customRange,
            onDismiss = { showRangePicker = false },
            onConfirm = { from, to ->
                onSelectCustomRange(from, to)
                showRangePicker = false
            },
        )
    }
}

/** 下拉菜单选中态的勾选图标。 */
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

/** 单个统计指标：小色标 + label / 数值 + 单位（不显示说明 caption）。 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String?,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = accent,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/**
 * 统计范围显示文案：自定义范围优先，跨年时带年份。
 *
 * 例：`全部` / `本月` / `今年` / `10.01–10.31` / `2025.12.01–2026.01.31`。
 */
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
        Period.ALL -> "全部"
        Period.MONTH -> "本月"
        Period.YEAR -> "今年"
    }
}

private fun monthDay(date: LocalDate): String =
    "${date.monthValue.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')}"

private fun yearMonthDay(date: LocalDate): String = "${date.year}.${monthDay(date)}"

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
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
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
                    text = "选择统计范围",
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

/** 爱车待办卡：未来一年内最近 3 条（Web 只展示 `near`，仅里程无日期的提醒不进待办）。 */
@Composable
private fun NearRemindersCard(
    ledger: Ledger,
    reminders: List<Reminder>,
    today: LocalDate,
    onNavigate: (String) -> Unit,
) {
    val km = remember(ledger) { currentKm(ledger) }
    DashboardCard(
        title = "爱车待办",
        action = {
            TextButton(onClick = { onNavigate(Routes.MAINTENANCE) }) {
                Text("管理提醒", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        if (reminders.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "暂无待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(Routes.MAINTENANCE) }) {
                    Text("添加提醒")
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                reminders.forEach { reminder ->
                    val status = reminderState(reminder, km, today)
                    val progress = serviceProgress(reminder, km, today)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MileNoteShapes.medium)
                            .clickable { onNavigate(Routes.MAINTENANCE) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CategoryBadge(
                            categoryName = "保养",
                            icon = MileNoteIcons.Bell,
                            size = 36.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = reminder.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusPill(
                                    text = reminderStatusLabel(status.level),
                                    status = reminderStatusKind(status.level),
                                )
                            }
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { (progress.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(MileNoteShapeTokens.full),
                                    color = if (progress.overdue) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    gapSize = 0.dp,
                                    drawStopIndicator = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 待办状态 → 文案（与 Web `normal/soon/overdue/done` 一致）。 */
private fun reminderStatusLabel(level: ReminderLevel): String = when (level) {
    ReminderLevel.DONE -> "已完成"
    ReminderLevel.OVERDUE -> "已到期"
    ReminderLevel.SOON -> "即将到期"
    ReminderLevel.NORMAL -> "计划中"
}

/** 待办状态 → 组件状态。 */
private fun reminderStatusKind(level: ReminderLevel): StatusKind = when (level) {
    ReminderLevel.DONE -> StatusKind.DONE
    ReminderLevel.OVERDUE -> StatusKind.OVERDUE
    ReminderLevel.SOON -> StatusKind.SOON
    ReminderLevel.NORMAL -> StatusKind.NORMAL
}
