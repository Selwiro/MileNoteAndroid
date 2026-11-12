package com.milenote.android.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.navigation.Routes
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.UiState
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.HeaderTextTabs
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.components.RangeFilterAction
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteCategoryColors
import com.milenote.android.ui.theme.LocalMileNoteDarkTheme
import com.milenote.android.ui.theme.LocalMileNoteHeroGradient
import com.milenote.android.ui.theme.HeroGradient
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.moneyMedium
import com.milenote.android.util.DataUrlImages
import java.time.LocalDate

/** 顶卡最小高度：与现状一致（156dp），保证页面节奏不变。 */
private val CompactHeroHeight = 156.dp

/** 右侧肖像区最大宽度（有照片时放用户照片；无照片时留给品牌插画出血）。 */
private val HeroPortraitWidth = 160.dp

/** 无照片时品牌插画宽度（右缘出血 12dp，见 [VehicleHeroCard]）。 */
private val HeroCarArtWidth = 170.dp

/**
 * 总览页：紧凑 Hero 车辆卡 + 用车统计大卡（2×2 指标 + 右上时间筛选）+ 趋势卡 + 爱车待办。
 *
 * 页面不自带 Scaffold / 顶栏 / 底栏 / FAB；只输出滚动内容，并按 [MileNoteDimens.PageBottom]
 * 预留底部空间。统计范围使用 [UiState.effectiveRange]（自定义范围优先），只影响指标 / 趋势；
 * 底栏 v4 起「支出趋势」并回本页（费用统计页取消），2026-11-12 起与原「里程趋势」合并成
 * 一张卡、卡头文字 tab 切换；「费用构成」留到月报 / 年报。
 * 本页图表 / 待办与统计卡统一为同一种 SectionCard 版式。
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
        DashboardLoadState(
            state = state,
            onNavigate = onNavigate,
            onReloadLedger = vm::reloadLedger,
            modifier = modifier,
        )
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
        onReloadLedger = vm::reloadLedger,
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
    onReloadLedger: () -> Unit,
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
                        message = if (state.persistenceBlocked) {
                            tr(
                                "本地账本读取异常，已暂停保存（不会覆盖原文件）。请先导出原始数据检查，修好后可重新读取。",
                                "The local ledger could not be read, so saving is paused (the original file is left untouched). Export the raw data first; once fixed you can re-read it.",
                            )
                        } else {
                            tr(
                                "本地账本读取异常，原始数据已另存。当前为空账本，可继续使用或重新读取。",
                                "The local ledger could not be read and a copy of the original was saved. You are on an empty ledger — keep using it or re-read the original.",
                            )
                        },
                        kind = MessageKind.ERROR,
                    )
                    Text(
                        text = loadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onReloadLedger) {
                        Text(tr("重新读取账本", "Re-read the ledger"))
                    }
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
                phev = ledger.vehicle.energyProfile == EnergyProfile.DUAL,
                onSelectPeriod = onSelectPeriod,
                onSelectCustomRange = onSelectCustomRange,
            )
        }
        // 2026-11-12：原「里程趋势 + 支出趋势」两张卡合并成一张「趋势」卡，
        // 卡头文字 tab 切换里程 / 支出，时间范围与「用车统计」共用同一全局状态。
        item(key = "trend") {
            TrendCard(
                ledger = ledger,
                range = range,
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

    }

}

/** 账本尚未加载 / 读取失败时的占位。 */
@Composable
private fun DashboardLoadState(
    state: UiState,
    onNavigate: (String) -> Unit,
    onReloadLedger: () -> Unit,
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
            message = if (state.persistenceBlocked) {
                tr(
                    "本地账本暂时无法读取或迁移，已停止写入以保护原文件。原记录仍保留，请先导出原始数据检查。",
                    "The local ledger cannot be read or migrated right now. Writing is disabled to protect the original file — export the raw data and check it first.",
                )
            } else {
                tr(
                    "本地账本暂时无法读取或迁移，原始数据已另存。原记录仍保留，请导出原始数据检查。",
                    "The local ledger cannot be read or migrated right now. A copy of the original was saved; export the raw data and check it.",
                )
            },
            kind = MessageKind.ERROR,
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onNavigate(Routes.MINE) }) {
            Text(tr("前往设置导出原始数据", "Go to Settings to export raw data"))
        }
        TextButton(onClick = onReloadLedger) {
            Text(tr("重新读取账本", "Re-read the ledger"))
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
    // 自定义图分两种：普通照片（不透明）走右侧满高肖像区铺满裁切；透明底抠图/贴纸
    // 跟品牌插画同一构图（右下贴底出血），否则抠图会被铺满裁掉、或被塞进渐变色块里发灰。
    val cutoutArt = remember(photoBitmap) { photoBitmap != null && isCutoutArt(photoBitmap) }
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
                onClick = { onNavigate(Routes.MINE) },
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

        if (fixedHeight && hasPhoto && cutoutArt && photoBitmap != null) {
            // 透明底抠图：与下方品牌插画同款「贴底 + 右缘出血 + 地面光池」，不裁切、不渐隐。
            CustomCutoutArt(bitmap = photoBitmap)
        } else if (fixedHeight && hasPhoto) {
            // 照片版：整卡铺满（Crop）+ 左侧品牌色渐层压字（scrim），不做半透明「鬼影」渐隐。
            VehiclePortraitPhoto(
                vehicle = vehicle,
                bitmap = photoBitmap,
                modifier = Modifier.matchParentSize(),
            )
            HeroPhotoScrim(gradient = gradient)
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

        // 顶部 1dp 高光（画在照片 / 插画之后，照片版也不会被盖住）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )

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

/**
 * 照片版 Hero：用户照片整卡铺满裁切（Crop），配合 [HeroPhotoScrim] 在左侧压出可读的品牌色底。
 *
 * 旧做法把照片塞进右侧 160dp×全高肖像区、按「完整展示 / 铺满画框」切 Fit / Crop 再拉 55% 长渐隐：
 * Fit 会在渐变卡里露出带硬边的浮空矩形，长渐隐又让照片半透明、车像鬼影。整卡铺满 + 实色 scrim
 * 是设计稿里 A/B 合体版的落地方式（见 docs/design/dashboard-hero-redesign-20261108.md §2 升级路径）。
 */
@Composable
private fun VehiclePortraitPhoto(
    vehicle: Vehicle,
    bitmap: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        !vehicle.image.isNullOrBlank() -> AsyncImage(
            model = vehicle.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.milenote_car_hero),
            error = painterResource(R.drawable.milenote_car_hero),
            modifier = modifier,
        )
        else -> Unit
    }
}

/**
 * 照片版 Hero 的可读性遮罩：左侧用品牌渐变色做「实色 → 透明」横向渐层（文字区 α≈0.88–0.97，
 * 照片从卡片宽约 78% 起完全露出），底部再压一层很轻的暗角，避免照片下沿把页脚小字吃掉。
 */
@Composable
private fun BoxScope.HeroPhotoScrim(gradient: HeroGradient) {
    Canvas(modifier = Modifier.matchParentSize()) {
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to gradient.start.copy(alpha = 0.97f),
                    0.40f to gradient.start.copy(alpha = 0.95f),
                    0.52f to gradient.start.copy(alpha = 0.88f),
                    0.60f to gradient.start.copy(alpha = 0.62f),
                    0.68f to gradient.start.copy(alpha = 0.28f),
                    0.78f to Color.Transparent,
                ),
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.45f to Color.Transparent,
                    1f to Color(0x2E141E26),
                ),
            ),
        )
    }
}

/**
 * 透明底抠图 / 贴纸：与品牌插画同一构图（贴底、右缘出血 12dp、地面光池），
 * 图片按 Fit 完整显示、不裁切（车辆位里选「完整展示」的图在 Hero 里也保持完整）。
 */
@Composable
private fun BoxScope.CustomCutoutArt(bitmap: ImageBitmap) {
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
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 12.dp, y = (-6).dp)
            .width(HeroCarArtWidth)
            .heightIn(max = 132.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 自定义图是否为「透明底抠图 / 贴纸」：只看图片是否真的带透明像素。
 *
 * 采样 9×5 网格的 alpha（角落 + 中间），透明像素占比 ≥15% 判为抠图；
 * 普通照片（JPEG / 不透明 PNG）一律走右侧满高铺满版式。WebP/PNG 带 alpha 通道
 * 但整张不透明时会落到照片分支，不会误判。
 */
private fun isCutoutArt(bitmap: ImageBitmap): Boolean {
    if (!bitmap.hasAlpha) return false
    val android = runCatching { bitmap.asAndroidBitmap() }.getOrNull() ?: return false
    val w = android.width
    val h = android.height
    if (w <= 0 || h <= 0) return false
    var transparent = 0
    var sampled = 0
    for (gy in 0..4) {
        for (gx in 0..8) {
            val x = (w - 1) * gx / 8
            val y = (h - 1) * gy / 4
            sampled++
            if ((android.getPixel(x, y) ushr 24) and 0xFF < 200) transparent++
        }
    }
    return sampled > 0 && transparent * 100 / sampled >= 15
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
 * 趋势、费用构成、用车统计、爱车待办共用同一版式与内边距，卡片之间只靠 12dp 间距分组。
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
@Composable
private fun VehicleStatsCard(
    period: Period,
    customRange: DateRange?,
    statsResult: StatsResult,
    ev: Boolean = false,
    phev: Boolean = false,
    onSelectPeriod: (Period) -> Unit,
    onSelectCustomRange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 平均油耗/能耗：满箱/满电区间加权；纯电没有满充区间时回退累计估算口径。
    val energyRate = statsResult.energyRate ?: if (ev) statsResult.estimatedEnergyRate else null
    // 插混：主口径仍是油耗（与能耗页一致），在「平均油耗」格下方补一行电耗小字；
    // 电耗同样优先区间加权，没有满充区间时用累计估算；两条流都没有值时不显示这一行。
    val electricRate = if (phev) {
        statsResult.electricEnergy?.let { it.rate ?: it.estimated }
    } else {
        null
    }

    DashboardCard(
        modifier = modifier,
        title = tr("用车统计", "Vehicle stats"),
        action = {
            RangeFilterAction(
                period = period,
                customRange = customRange,
                onSelectPeriod = onSelectPeriod,
                onSelectCustomRange = onSelectCustomRange,
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
                    caption = electricRate?.let {
                        tr("电耗 %s %s", "Electric %s %s", Numbers.toFixed2(it), Units.energyConsumption)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

}

/** 趋势卡的图表类型（卡头文字 tab）。 */
private enum class TrendTab {
    MILEAGE,
    EXPENSE,
}

/** 趋势卡 tab 文案（纯 UI 文案，数据值不翻译）。 */
private fun trendTabLabel(tab: TrendTab): String = when (tab) {
    TrendTab.MILEAGE -> tr("里程", "Mileage")
    TrendTab.EXPENSE -> tr("支出", "Expense")
}

/**
 * 总览「趋势」卡：原「里程趋势」折线 + 「支出趋势」堆叠柱合并成一张卡。
 *
 * - 切换控件放在卡头左侧的**文字 tab**（选中加粗 + 3dp 下划线），不再用通栏段控：
 *   与右上角范围胶囊拉开视觉层级（2026-11-12 用户反馈「两个胶囊像重复控件」）；
 * - 时间范围沿用全局 [period] / [customRange]（与「用车统计」联动），两张图共用
 *   [trendMonthWindow] 的同一批月份，切换 tab 时横轴不跳动；
 * - 两张图共用同一「图表区」（绘图 + 底部月份轴）：轴下不再放信息区，支出图例收进卡头的
 *   「图例」按钮、点开底部弹层查看，所以切 tab 时横轴在同一行、卡高也不变，
 *   下方「爱车待办」不会被顶得上下跳（2026-11-12 用户反馈）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrendCard(
    ledger: Ledger,
    range: DateRange,
    modifier: Modifier = Modifier,
) {
    var showLegend by remember { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(TrendTab.MILEAGE.name) }
    val tab = TrendTab.entries.firstOrNull { it.name == tabName } ?: TrendTab.MILEAGE
    val window = remember(ledger, range) { trendMonthWindow(ledger, range) }
    val months = window.months
    // 「全部」在图表里只画最近 24 个月；轴下不放信息，窗口说明放在卡头右侧。
    val windowLabel = when {
        window.truncated -> tr(
            "近 %d / %d 个月",
            "Last %d of %d months",
            months.size,
            window.totalMonths,
        )
        range.from.isEmpty() -> tr("近 %d 个月", "Last %d months", months.size)
        else -> null
    }
    val mileageBars = remember(ledger, months) { buildMileageBars(ledger, months) }
    val palette = LocalMileNoteCategoryColors.current
    val darkTheme = LocalMileNoteDarkTheme.current
    val monthBars = remember(ledger, palette, darkTheme, months) {
        buildMonthBars(ledger, palette, darkTheme, months)
    }

    SectionCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                HeaderTextTabs(
                    labels = TrendTab.entries.map { trendTabLabel(it) },
                    selectedIndex = tab.ordinal,
                    onSelect = { index ->
                        tabName = TrendTab.entries[index].name
                        showLegend = false
                    },
                )
                if (windowLabel != null) {
                    Text(
                        text = windowLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 图例只在支出 tab 出现；图标按钮 32dp，不改变卡头高度。
            if (tab == TrendTab.EXPENSE) {
                TrendLegendButton(onClick = { showLegend = true })
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        when (tab) {
            TrendTab.MILEAGE -> MileageTrendChart(bars = mileageBars)
            TrendTab.EXPENSE -> ExpenseTrendChart(bars = monthBars, ev = ledger.isEv)
        }
    }

    if (showLegend && tab == TrendTab.EXPENSE) {
        ModalBottomSheet(onDismissRequest = { showLegend = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("图例", "Legend"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = tr(
                        "支出柱段按分类着色；点柱段可看分类、金额与占比。",
                        "Segments are colored by category; tap one for its name, amount and share.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpenseLegend(bars = monthBars, ev = ledger.isEv)
                TextButton(onClick = { showLegend = false }) { Text(tr("关闭", "Close")) }
            }
        }
    }
}

/** 卡头「图例」按钮：圆形小底、18dp 调色板图标，高度与右侧范围胶囊一致（不改变卡头高度）。 */
@Composable
private fun TrendLegendButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) {
        Box(modifier = Modifier.padding(7.dp)) {
            Icon(
                imageVector = MileNoteIcons.Palette,
                contentDescription = tr("图例", "Legend"),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 单个统计指标：小色标 + label / 数值 + 单位（不显示说明 caption）。 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    caption: String? = null,
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
        // PHEV 的「平均油耗」格下方补一行电耗小字（其它指标不传，布局不变）。
        if (caption != null) {
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
                Text(tr("管理提醒", "Manage"), style = MaterialTheme.typography.labelLarge)
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
