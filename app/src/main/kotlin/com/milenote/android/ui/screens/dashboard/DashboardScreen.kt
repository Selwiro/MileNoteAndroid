package com.milenote.android.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.navigation.Routes
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.UiState
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.EmptyState
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
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.moneyMedium
import com.milenote.android.util.DataUrlImages
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** 顶卡最小高度：与现状一致（156dp），保证页面节奏不变。 */
private val CompactHeroHeight = 156.dp

/** 右侧肖像区最大宽度（有照片时放用户照片；无照片时留给品牌插画出血）。 */
private val HeroPortraitWidth = 160.dp

/** 无照片时品牌插画宽度（右缘出血 12dp，见 [VehicleHeroCard]）。 */
private val HeroCarArtWidth = 170.dp

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
                        message = tr("本地账本读取异常，已暂停保存。请先导出原始数据检查。", "The local ledger could not be read, so saving is paused. Export the raw data and check it first."),
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
                    title = tr("从第一笔记录开始", "Start with your first record"),
                    description = if (ledger.isEv) {
                        // 纯电没有「小熊油耗」可导入，引导只用钱迹/手动。
                        tr("手动记账，或导入你已有的钱迹数据。", "Add entries manually, or import your existing Qianji data.")
                    } else {
                        tr("手动记账，或导入你已有的小熊油耗、钱迹数据。", "Add entries manually, or import your existing Bear or Qianji data.")
                    },
                    illustration = painterResource(R.drawable.ill_empty_records),
                    actionLabel = tr("记一笔", "Add entry"),
                    onAction = onAddRecord,
                    secondaryLabel = tr("看看演示", "See the demo"),
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
                ev = ledger.isEv,
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
            label = tr("正在读取本机账本…", "Reading the local ledger…"),
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
            message = tr("本地账本暂时无法读取或迁移。原记录仍保留，请导出原始数据检查；已暂停保存。", "The local ledger cannot be read or migrated right now. Your records are intact — export the raw data and check it; saving is paused."),
            kind = MessageKind.ERROR,
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onNavigate(Routes.SETTINGS) }) {
            Text(tr("前往设置导出原始数据", "Go to Settings to export raw data"))
        }
    }
}

/** 演示账本横幅（唯一紫色语义，设计规范 §2.5）。 */
@Composable
private fun DemoBanner(onExitDemo: () -> Unit) {
    MessageBar(
        message = tr("演示账本 · 可切换车辆、试记账与换肤，退出后不保留修改", "Demo ledger · switch vehicles, try entries and themes; changes are discarded on exit"),
        kind = MessageKind.DEMO,
        actionLabel = tr("退出演示", "Exit demo"),
        onAction = onExitDemo,
    )
}

/**
 * 总览顶卡「车辆名片」（方案 A）：品牌渐变 + 车名 + 右侧肖像区 + 「已陪伴 · 笔数」页脚。
 *
 * - 车名是第一视觉层（24sp/32，最多 2 行）；车型/车牌 14sp 单行；页脚 12sp；
 * - 有用户照片时占右侧 160dp 肖像区（左缘渐隐，绝不压左侧文字）；无照片时品牌插画贴底右侧出血；
 * - **不显示总里程、不放右上角按钮**（产品 2026-11-08 决定）；整卡点按进入设置 / 车辆管理；
 * - 字号 ≥1.3× 时收起肖像区、文字整宽，卡高由内容决定（`heightIn`，不裁字）。
 */
@Composable
private fun VehicleHeroCard(
    ledger: Ledger,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vehicle = ledger.vehicle
    val gradient = LocalMileNoteHeroGradient.current
    val contentColor = gradient.contentColor
    val days = Companion.days(vehicle.companionDate)
    val footer = heroFooterText(ledger, days)
    val empty = footer == null
    val photoBitmap = remember(vehicle.image) {
        if (DataUrlImages.isDataUrl(vehicle.image)) DataUrlImages.decode(vehicle.image) else null
    }
    val hasPhoto = photoBitmap != null ||
        (!DataUrlImages.isDataUrl(vehicle.image) && !vehicle.image.isNullOrBlank())
    val fontScale = LocalDensity.current.fontScale
    val fixedHeight = fontScale < 1.3f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fixedHeight) {
                    Modifier.height(CompactHeroHeight)
                } else {
                    Modifier.heightIn(min = CompactHeroHeight)
                },
            )
            .clip(MileNoteShapes.extraLarge)
            .background(Brush.linearGradient(gradient.colors))
            .clickable(
                onClickLabel = tr("管理车辆", "Manage vehicle"),
                onClick = { onNavigate(Routes.SETTINGS) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = tr(
                    "管理车辆：%s",
                    "Manage vehicle: %s",
                    DataLabels.vehicleName(vehicle.name),
                )
            },
    ) {
        val cardWidth = maxWidth
        // 右侧肖像区：正常字号占 42%，最宽 160dp；≥1.3× 字号收起。
        val portraitWidth = if (fixedHeight) minOf(HeroPortraitWidth, cardWidth * 0.42f) else 0.dp
        val textWidth = maxOf(0.dp, cardWidth - portraitWidth - 22.dp)

        // 卡内叠加：左缘暗角（体积感 + 小字对比度余量）+ 右上柔光
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color(0x2E141E26),
                        0.46f to Color(0x0F141E26),
                        0.66f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.08f),
                    radius = size.width * 0.55f,
                ),
            )
        }
        // 顶部 1dp 高光
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )

        if (fixedHeight && hasPhoto) {
            VehiclePortraitPhoto(
                vehicle = vehicle,
                bitmap = photoBitmap,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(portraitWidth),
            )
        } else if (fixedHeight) {
            // 地面光池 + 品牌插画（右缘出血 12dp、贴底 6dp）
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .width(190.dp)
                    .height(52.dp),
            ) {
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width / 2f,
                    ),
                )
            }
            Image(
                painter = painterResource(R.drawable.milenote_car_hero),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = (-6).dp)
                    .width(HeroCarArtWidth),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(textWidth + 20.dp)
                .padding(start = 20.dp, top = 16.dp, bottom = 15.dp),
            verticalArrangement = if (empty) Arrangement.Center else Arrangement.Top,
        ) {
            Text(
                text = DataLabels.vehicleName(vehicle.name),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = heroModelLine(vehicle),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (footer != null) {
                if (fixedHeight) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.16f),
                )
                Spacer(modifier = Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MileNoteIcons.Calendar,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 右侧肖像区：用户照片 cover/fit + 左缘渐隐（DstIn），保证照片不压左侧文字。 */
@Composable
private fun VehiclePortraitPhoto(
    vehicle: Vehicle,
    bitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val contentScale = if (vehicle.imageFit == "cover") ContentScale.Crop else ContentScale.Fit
    val fadeModifier = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.55f to Color.Black,
                        1f to Color.Black,
                    ),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.fillMaxSize().then(fadeModifier),
        )
        !vehicle.image.isNullOrBlank() -> AsyncImage(
            model = vehicle.image,
            contentDescription = null,
            contentScale = contentScale,
            placeholder = painterResource(R.drawable.milenote_car_hero),
            error = painterResource(R.drawable.milenote_car_hero),
            modifier = modifier.fillMaxSize().then(fadeModifier),
        )
        else -> Unit
    }
}

/** 车型 · 车牌；都为空时给「点击完善车辆信息」引导。 */
private fun heroModelLine(vehicle: Vehicle): String =
    listOf(vehicle.model, vehicle.plate)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { tr("点击完善车辆信息", "Tap to complete vehicle info") }

/** 页脚文案：已陪伴天数 · 记录笔数；两者都没有时返回 null（空账本状态）。 */
private fun heroFooterText(ledger: Ledger, days: Int?): String? {
    val recordCount = ledger.records.size
    return when {
        days != null && days > 0 && recordCount > 0 ->
            tr("已陪伴 %d 天 · %d 笔", "%d days · %d entries", days, recordCount)
        days != null && days > 0 -> tr("已陪伴 %d 天", "%d days", days)
        recordCount > 0 -> tr("已记录 %d 笔", "%d entries", recordCount)
        else -> null
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
    ev: Boolean = false,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    // 平均油耗/能耗：满箱/满电区间加权；纯电没有满充区间时回退累计估算口径。
    val energyRate = statsResult.energyRate ?: if (ev) statsResult.estimatedEnergyRate else null

    DashboardCard(
        modifier = modifier,
        title = tr("用车统计", "Vehicle stats"),
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
                    label = tr("用车支出", "Expenses"),
                    value = Format.money(statsResult.total),
                    unit = Units.money,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = tr("行驶里程", "Distance"),
                    value = Format.fmt(statsResult.distance),
                    unit = Units.distanceUnit,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatCell(
                    label = tr("用车成本", "Cost"),
                    value = statsResult.perKm?.let { Format.money(it) } ?: "—",
                    unit = if (statsResult.perKm == null) null else Units.costPerDistance,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = if (ev) tr("平均能耗", "Energy economy") else tr("平均油耗", "Fuel economy"),
                    value = energyRate?.let { Numbers.toFixed2(it) } ?: "—",
                    unit = if (energyRate == null) null else if (ev) Units.energyConsumption else Units.consumption,
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
                    contentDescription = tr("选择统计范围", "Select date range"),
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
            text = { Text(tr("全部", "All")) },
            trailingIcon = checkIcon(presetSelected && period == Period.ALL),
            onClick = { onSelectPeriod(Period.ALL) },
        )
        DropdownMenuItem(
            text = { Text(tr("本月", "This month")) },
            trailingIcon = checkIcon(presetSelected && period == Period.MONTH),
            onClick = { onSelectPeriod(Period.MONTH) },
        )
        DropdownMenuItem(
            text = { Text(tr("今年", "This year")) },
            trailingIcon = checkIcon(presetSelected && period == Period.YEAR),
            onClick = { onSelectPeriod(Period.YEAR) },
        )
        DropdownMenuItem(
            text = { Text(tr("自定义…", "Custom…")) },
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
        title = tr("里程趋势", "Mileage trend"),
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
private enum class TrendRangeMode {
    HALF,
    YEAR,
    CUSTOM,
}

/** 支出趋势维度 → 显示文案（按当前语言取）。 */
private fun trendModeLabel(mode: TrendRangeMode): String = when (mode) {
    TrendRangeMode.HALF -> tr("半年", "6 months")
    TrendRangeMode.YEAR -> tr("一年", "12 months")
    TrendRangeMode.CUSTOM -> tr("自定义月份", "Custom months")
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
        title = tr("支出趋势", "Expense trend"),
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
                            text = { Text(trendModeLabel(entry)) },
                            trailingIcon = checkIcon(mode == entry),
                            onClick = {
                                menuExpanded = false
                                showMonthPicker = true
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(trendModeLabel(entry)) },
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
        ExpenseTrendChart(bars = bars, ev = ledger.isEv)
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
    TrendRangeMode.HALF -> trendModeLabel(TrendRangeMode.HALF)
    TrendRangeMode.YEAR -> trendModeLabel(TrendRangeMode.YEAR)
    TrendRangeMode.CUSTOM -> {
        val start = startMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val end = endMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        when {
            start != null && end != null && start.year == end.year ->
                "${start.year}.${month2(start)}–${month2(end)}"
            start != null && end != null ->
                "${start.year}.${month2(start)}–${end.year}.${month2(end)}"
            months.isNotEmpty() -> tr("%d 个月", "%d months", months.size)
            else -> trendModeLabel(TrendRangeMode.CUSTOM)
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
        title = { Text(tr("自定义月份", "Custom months"), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = tr("选择起止月份，最多 24 个月。", "Pick a start and end month, up to 24 months."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonthDropdown(label = tr("起始月", "Start month"), selected = start, options = options, onSelect = { start = it })
                MonthDropdown(label = tr("结束月", "End month"), selected = end, options = options, onSelect = { end = it })
                when {
                    start > end -> Text(
                        text = tr("结束月不能早于起始月", "The end month cannot be before the start month"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    span > 24 -> Text(
                        text = tr("月份范围最多 24 个月（当前 %d 个月）", "The range can span at most 24 months (currently %d)", span),
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
                Text(tr("确定", "OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel"))
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
    ev: Boolean = false,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }

    DashboardCard(
        title = tr("费用构成", "Expense breakdown"),
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
            ev = ev,
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
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        Period.ALL -> tr("全部", "All")
        Period.MONTH -> tr("本月", "This month")
        Period.YEAR -> tr("今年", "This year")
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
                Text(tr("确定", "OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel"))
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
        title = tr("爱车待办", "Upcoming reminders"),
        action = {
            TextButton(onClick = { onNavigate(Routes.MAINTENANCE) }) {
                Text(tr("管理提醒", "Manage reminders"), style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        if (reminders.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("暂无待办", "Nothing due"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNavigate(Routes.MAINTENANCE) }) {
                    Text(tr("添加提醒", "Add reminder"))
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
                                    text = DataLabels.serviceItem(reminder.title),
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
    ReminderLevel.DONE -> tr("已完成", "Done")
    ReminderLevel.OVERDUE -> tr("已到期", "Overdue")
    ReminderLevel.SOON -> tr("即将到期", "Due soon")
    ReminderLevel.NORMAL -> tr("计划中", "Planned")
}

/** 待办状态 → 组件状态。 */
private fun reminderStatusKind(level: ReminderLevel): StatusKind = when (level) {
    ReminderLevel.DONE -> StatusKind.DONE
    ReminderLevel.OVERDUE -> StatusKind.OVERDUE
    ReminderLevel.SOON -> StatusKind.SOON
    ReminderLevel.NORMAL -> StatusKind.NORMAL
}
