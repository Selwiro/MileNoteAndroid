@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.milenote.android.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.BuildConfig
import com.milenote.android.R
import com.milenote.android.data.settings.AppSettings
import com.milenote.android.data.settings.DarkMode
import com.milenote.android.data.update.UpdateCheckResult
import com.milenote.android.data.update.UpdateChecker
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankVehicle
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.AppLanguage
import com.milenote.android.i18n.CurrencyUnit
import com.milenote.android.i18n.DistanceUnit
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.VolumeUnit
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.platform.FileExporter
import com.milenote.android.platform.LedgerExports
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.PendingConfirm
import com.milenote.android.ui.QuarantinedRaw
import com.milenote.android.ui.WebDavDownloadMode
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SecondaryTabs
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.SectionHeader
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.AppTheme
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 返回上一页的导航约定（兜底）：宿主没有 `OnBackPressedDispatcher` 时，
 * 导航层收到该值应执行 `navController.popBackStack()`。
 */
const val SETTINGS_NAV_BACK: String = "back"

/** 设置页内的轻量提示（导出/导入/图片处理的结果；账本类操作由 VM 的全局 Snackbar 播报）。 */
private data class SettingsMessage(
    val text: String,
    val kind: MessageKind,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** 检查更新的结果弹层。 */
private data class UpdateDialogState(
    val title: String,
    val message: String,
    val link: String?,
)

/** 正在编辑的车辆：`id == null` 表示新增。 */
private data class VehicleEditTarget(
    val id: String?,
    val vehicle: Vehicle,
    val recordCount: Int,
)

/**
 * 设置内部页面（用内部状态切换，不新增导航路由；系统返回键先回菜单）。
 *
 * 一级按性质分三组：车辆/外观/单位/记账、数据（外部导入 / 备份与还原 / WebDAV 同步）、关于。
 * 全部子页都从设置首页一层进入，不再有三级页。
 */
private enum class SettingsPage {
    ROOT,
    VEHICLES,
    APPEARANCE,
    UNITS,
    CATEGORIES,
    IMPORT,
    BACKUP,
    WEBDAV,
    ABOUT,
    ;

    /** 顶栏标题（随语言切换；不要放进枚举构造参数，那样会固定在类加载时的语言）。 */
    val title: String
        get() = when (this) {
            ROOT -> tr("设置", "Settings")
            VEHICLES -> tr("我的车辆", "My vehicles")
            APPEARANCE -> tr("外观", "Appearance")
            UNITS -> tr("单位", "Units")
            CATEGORIES -> tr("费用分类与项目", "Categories & items")
            IMPORT -> tr("外部导入", "Import")
            BACKUP -> tr("备份与还原", "Backup & restore")
            WEBDAV -> tr("WebDAV 同步", "WebDAV sync")
            ABOUT -> tr("关于", "About")
        }
}

/**
 * 设置。
 *
 * 首页：三张卡片、8 行菜单（单色图标 + 标题 + chevron）——
 * 车辆/外观/单位/费用分类与项目；外部导入 / 备份与还原 / WebDAV 同步；关于。
 * 点按进入子页；子页共用同一个 TopAppBar（返回箭头回到菜单），系统返回键用
 * [BackHandler] 先回菜单；不新增路由。返回逻辑只有"子页 → 设置首页"一层。
 *
 * - 我的车辆：横向车辆卡片，点卡片切换当前车辆，卡片右上角编辑；添加车辆打开全屏编辑对话框。
 * - 外观：5 套皮肤、深浅色三态。
 * - 单位：货币 / 里程 / 油量显示口径（只改显示标签，不换算数值；油量只服务燃油车）。
 * - 费用分类与项目：费用分类 / 项目库两个页签；分类按当前车辆隔离。
 * - 外部导入：小熊油耗、钱迹记账、其他 App 分享；来源预选后直接进入导入向导（纯电当前车隐藏小熊油耗）。
 * - 备份与还原：JSON 完整备份、费用 CSV 导出、JSON 还原、删除全部数据。
 * - WebDAV 同步：连接配置、前台自动同步、手动上传/下载。
 * - 关于：应用 logo 与简介、检查更新、官网/GitHub、演示账本。
 *
 * @param vm 全局 ViewModel。
 * @param onNavigate 路由回调；仅在首页返回、且宿主没有 OnBackPressedDispatcher 时回传
 *   [SETTINGS_NAV_BACK]（兜底）。
 * @param onOpenImporter 打开导入向导（外部导入），参数为来源 id（bear / qianji）。
 * @param modifier 外部修饰符。
 */
@Composable
fun SettingsScreen(
    vm: MileNoteViewModel,
    onNavigate: (String) -> Unit = {},
    onOpenImporter: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val garage = state.garage
    val cars = garage?.cars.orEmpty()
    val activeId = garage?.activeCar?.id
    val activeCar = garage?.activeCar
    val activeName = activeCar?.let { DataLabels.vehicleName(it.vehicle.name) } ?: "—"
    val appTheme = AppTheme.fromId(garage?.theme)
    val themeId = garage?.theme ?: "blue"
    val customCategoryCount = activeCar?.customCategories.orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .size

    var message by remember { mutableStateOf<SettingsMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VehicleEditTarget?>(null) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    var pendingRaw by remember { mutableStateOf<ByteArray?>(null) }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.ROOT.name) }
    var categoryTab by rememberSaveable { mutableIntStateOf(0) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName } ?: SettingsPage.ROOT

    fun notify(
        text: String,
        kind: MessageKind,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        message = SettingsMessage(text, kind, actionLabel, onAction)
    }

    fun shareBackup(text: String) {
        scope.launch {
            busy = true
            try {
                FileExporter.shareText(
                    context = context,
                    fileName = LedgerExports.backupFileName(),
                    mimeType = "application/json",
                    bytes = text.toByteArray(Charsets.UTF_8),
                )
            } catch (t: Throwable) {
                notify(
                    tr(
                        "分享失败：%s",
                        "Share failed: %s",
                        t.message ?: tr("无法生成备份", "Could not create backup"),
                    ),
                    MessageKind.ERROR,
                )
            } finally {
                busy = false
            }
        }
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingBackup
        pendingBackup = null
        if (uri != null && text != null) {
            runCatching { FileExporter.writeTo(context, uri, text.toByteArray(Charsets.UTF_8)) }
                .onSuccess {
                    notify(
                        text = tr("完整备份已导出", "Full backup exported"),
                        kind = MessageKind.SUCCESS,
                        actionLabel = tr("分享", "Share"),
                        onAction = { shareBackup(text) },
                    )
                }
                .onFailure {
                    notify(
                        tr(
                            "导出失败：%s",
                            "Export failed: %s",
                            it.message ?: tr("无法写入所选文件", "Could not write the selected file"),
                        ),
                        MessageKind.ERROR,
                    )
                }
        }
    }

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val bytes = pendingCsv
        pendingCsv = null
        if (uri != null && bytes != null) {
            runCatching { FileExporter.writeTo(context, uri, bytes) }
                .onSuccess { notify(tr("费用 CSV 已导出", "Expense CSV exported"), MessageKind.SUCCESS) }
                .onFailure {
                    notify(
                        tr(
                            "导出失败：%s",
                            "Export failed: %s",
                            it.message ?: tr("无法写入所选文件", "Could not write the selected file"),
                        ),
                        MessageKind.ERROR,
                    )
                }
        }
    }

    val createRaw = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val bytes = pendingRaw
        pendingRaw = null
        if (uri != null && bytes != null) {
            runCatching { FileExporter.writeTo(context, uri, bytes) }
                .onSuccess { notify(tr("原始账本已导出", "Raw ledger exported"), MessageKind.SUCCESS) }
                .onFailure {
                    notify(
                        tr(
                            "导出失败：%s",
                            "Export failed: %s",
                            it.message ?: tr("无法写入所选文件", "Could not write the selected file"),
                        ),
                        MessageKind.ERROR,
                    )
                }
        }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException(tr("无法读取所选文件", "Could not read the selected file"))
                }
                if (text.length > 30_000_000) {
                    throw IllegalStateException(tr("备份文件超过 30 MB，已取消导入", "Backup file is larger than 30 MB; import cancelled"))
                }
                // 合并规则与提示由 VM/仓库统一处理（结果走全局 Snackbar）。
                vm.restoreBackup(text)
            } catch (t: Throwable) {
                notify(
                    tr(
                        "导入备份失败：%s",
                        "Import failed: %s",
                        t.message ?: tr("文件格式不正确", "Invalid file format"),
                    ),
                    MessageKind.ERROR,
                )
            } finally {
                busy = false
            }
        }
    }

    fun exportBackupFile() {
        scope.launch {
            busy = true
            try {
                pendingBackup = vm.exportBackup()
                createBackup.launch(LedgerExports.backupFileName())
            } catch (t: Throwable) {
                notify(
                    tr(
                        "导出失败：%s",
                        "Export failed: %s",
                        t.message ?: tr("无法生成备份", "Could not create backup"),
                    ),
                    MessageKind.ERROR,
                )
            } finally {
                busy = false
            }
        }
    }

    fun exportCsvFile() {
        val ledger = activeCar
        if (ledger == null) {
            notify(tr("当前没有可导出的车辆", "No vehicle available to export"), MessageKind.WARNING)
            return
        }
        pendingCsv = LedgerExports.csv(ledger)
        createCsv.launch(LedgerExports.csvFileName())
    }

    /** 导出解析失败时被隔离的原始账本（只用于人工检查/抢救，不能直接还原）。 */
    fun exportRawFile() {
        scope.launch {
            busy = true
            try {
                val bytes = vm.readQuarantinedRaw()
                if (bytes == null) {
                    notify(tr("没有可导出的原始账本", "No raw ledger available to export"), MessageKind.WARNING)
                } else {
                    pendingRaw = bytes
                    createRaw.launch("里程志-原始数据-${Dates.iso(Dates.today())}.json")
                }
            } finally {
                busy = false
            }
        }
    }

    fun openVehicleEditor(id: String?) {
        // 编辑非当前车辆时先切到目标车，避免 saveVehicle 把数据写到别的账本；新增不切车。
        if (id != null) vm.selectVehicle(id)
        val ledger: Ledger? = id?.let { target -> cars.firstOrNull { it.id == target } }
        editing = VehicleEditTarget(
            id = id,
            vehicle = ledger?.vehicle ?: blankVehicle(),
            recordCount = ledger?.records?.size ?: 0,
        )
    }

    // 子页时系统返回 / 手势返回先回设置菜单，不退出页面路由。
    BackHandler(enabled = page != SettingsPage.ROOT) {
        pageName = SettingsPage.ROOT.name
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(page.title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    IconButton(
                        onClick = {
                            if (page == SettingsPage.ROOT) {
                                if (backDispatcher != null) {
                                    backDispatcher.onBackPressed()
                                } else {
                                    onNavigate(SETTINGS_NAV_BACK)
                                }
                            } else {
                                pageName = SettingsPage.ROOT.name
                            }
                        },
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Back,
                            contentDescription = if (page == SettingsPage.ROOT) tr("返回", "Back") else tr("返回设置", "Back to settings"),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                ),
            )
        },
        // 外层 MileNoteApp 已经处理了系统栏 inset，这里不再叠加，避免状态栏双倍留白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (garage == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(label = tr("正在读取本机账本…", "Loading local ledger…"))
            }
            return@Scaffold
        }

        // 应用 Scaffold 的 topBar padding，否则列表首项会被顶栏遮住、也无法滚出完整内容。
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // key(page)：切换子页时重置滚动位置，不沿用上一页的 LazyColumn 偏移。
            key(page) {
                when (page) {
                    SettingsPage.ROOT -> SettingsPageList(narrow = true) {
                        if (state.loadError != null || state.demo || message != null) {
                            item("messages") {
                                SettingsMessageArea(
                                    loadError = state.loadError,
                                    demo = state.demo,
                                    message = message,
                                    onExitDemo = { vm.exitDemo() },
                                    onDismissMessage = { message = null },
                                )
                            }
                        }

                        item("menu-main") {
                            SectionCard(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Car,
                                    title = tr("我的车辆", "My vehicles"),
                                    onClick = { pageName = SettingsPage.VEHICLES.name },
                                )
                                SectionDivider()
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Palette,
                                    title = tr("外观", "Appearance"),
                                    onClick = { pageName = SettingsPage.APPEARANCE.name },
                                )
                                SectionDivider()
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Ruler,
                                    title = tr("单位", "Units"),
                                    onClick = { pageName = SettingsPage.UNITS.name },
                                )
                                SectionDivider()
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Tag,
                                    title = tr("费用分类与项目", "Categories & items"),
                                    onClick = { pageName = SettingsPage.CATEGORIES.name },
                                )
                            }
                        }

                        item("menu-data") {
                            SectionCard(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Import,
                                    title = tr("外部导入", "Import"),
                                    onClick = { pageName = SettingsPage.IMPORT.name },
                                )
                                SectionDivider()
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Archive,
                                    title = tr("备份与还原", "Backup & restore"),
                                    onClick = { pageName = SettingsPage.BACKUP.name },
                                )
                                SectionDivider()
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Cloud,
                                    title = tr("WebDAV 同步", "WebDAV sync"),
                                    onClick = { pageName = SettingsPage.WEBDAV.name },
                                )
                            }
                        }

                        item("menu-about") {
                            SectionCard(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                SettingsMenuRow(
                                    icon = MileNoteIcons.Info,
                                    title = tr("关于", "About"),
                                    onClick = { pageName = SettingsPage.ABOUT.name },
                                )
                            }
                        }
                    }

                    SettingsPage.VEHICLES -> SettingsPageList {
                        if (message != null) {
                            item("messages") {
                                SettingsMessageArea(
                                    loadError = null,
                                    demo = false,
                                    message = message,
                                    onExitDemo = {},
                                    onDismissMessage = { message = null },
                                )
                            }
                        }

                        item("vehicles-header") {
                            SectionHeader(
                                title = tr("车辆列表", "Vehicles"),
                                subtitle = tr(
                                    "共 %d 辆 · %d 条记录 · 点按卡片进入编辑",
                                    "%d vehicles · %d records · Tap a card to edit",
                                    cars.size,
                                    cars.sumOf { it.records.size },
                                ),
                                actionLabel = tr("添加车辆", "Add vehicle"),
                                actionIcon = MileNoteIcons.Add,
                                onAction = { openVehicleEditor(null) },
                            )
                        }

                        item("vehicles") {
                            if (cars.isEmpty()) {
                                EmptyState(
                                    title = tr("还没有车辆", "No vehicles yet"),
                                    description = tr("添加第一辆车，开始记录每一%s的花费。", "Add your first car and start tracking the cost of every %s.", Units.distanceUnit),
                                    icon = MileNoteIcons.Car,
                                    actionLabel = tr("添加车辆", "Add vehicle"),
                                    onAction = { openVehicleEditor(null) },
                                )
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
                                ) {
                                    items(cars, key = { it.id }) { car ->
                                        VehicleCard(
                                            ledger = car,
                                            selected = car.id == activeId,
                                            onClick = { openVehicleEditor(car.id) },
                                        )
                                    }
                                }
                            }
                        }

                    }

                    SettingsPage.UNITS -> SettingsPageList {
                        item("units") {
                            UnitsSection(
                                settings = settings,
                                onCurrencyChange = { unit -> vm.setCurrency(unit) },
                                onDistanceUnitChange = { unit -> vm.setDistanceUnit(unit) },
                                onVolumeUnitChange = { unit -> vm.setVolumeUnit(unit) },
                            )
                        }
                    }

                    SettingsPage.APPEARANCE -> SettingsPageList {
                        item("appearance") {
                            AppearanceSection(
                                settings = settings,
                                appTheme = appTheme,
                                onThemeChange = { theme -> vm.setTheme(theme.name.lowercase()) },
                                onDarkModeChange = { mode -> vm.setDarkMode(mode) },
                                language = settings.language,
                                onLanguageChange = { language -> vm.setLanguage(language) },
                            )
                        }
                    }

                    SettingsPage.CATEGORIES -> CategoryTabsPage(
                        tab = categoryTab,
                        onTabChange = { categoryTab = it },
                        ledger = activeCar,
                        themeId = themeId,
                        vehicleName = activeName,
                        onAddCategory = { name -> vm.addCustomCategory(name) },
                        onRemoveCategory = { name ->
                            vm.confirm(
                                PendingConfirm(
                                    title = tr("删除自定义分类？", "Delete custom category?"),
                                    message = tr(
                                        "「%s」将从记账的可选分类中移除；已有记录的分类与金额不受影响。",
                                        "“%s” will be removed from the category picker; existing records and amounts are unchanged.",
                                        name,
                                    ),
                                    confirmLabel = tr("删除", "Delete"),
                                    destructive = true,
                                ) { vm.removeCustomCategory(name) },
                            )
                        },
                        onAddProject = { category, name -> vm.addProject(category, name) },
                        onRemoveProject = { category, name -> vm.removeProject(category, name) },
                    )

                    SettingsPage.IMPORT -> ExternalImportPage(
                        // 纯电当前车隐藏「小熊油耗」（只认加油 XLS），其它导入入口保留。
                        ev = activeCar?.isEv == true,
                        onOpenImporter = { source -> onOpenImporter(source) },
                        onGoBackup = { pageName = SettingsPage.BACKUP.name },
                    )

                    SettingsPage.BACKUP -> BackupRestorePage(
                        carsCount = cars.size,
                        recordsCount = cars.sumOf { it.records.size },
                        demo = state.demo,
                        loadError = state.loadError,
                        quarantinedRaw = state.quarantinedRaw,
                        busy = busy,
                        message = message,
                        onExportBackup = { exportBackupFile() },
                        onExportCsv = { exportCsvFile() },
                        onExportRaw = { exportRawFile() },
                        onRefreshQuarantinedRaw = { vm.refreshQuarantinedRaw() },
                        onImportBackup = {
                            openBackup.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain"),
                            )
                        },
                        onDeleteAll = { vm.deleteAllData() },
                        onExitDemo = { vm.exitDemo() },
                        onDismissMessage = { message = null },
                    )

                    SettingsPage.WEBDAV -> SettingsPageList {
                        item("webdav") {
                            WebDavPage(
                                state = state.webDav,
                                onSaveConfig = { url, username, password, allowHttp ->
                                    vm.saveWebDavConfig(url, username, password, allowHttp)
                                },
                                onClearConfig = { vm.clearWebDavConfig() },
                                onSyncNow = { vm.webDavSyncNow() },
                                onUpload = { vm.webDavUpload() },
                                onDownloadReplace = { vm.webDavDownload(WebDavDownloadMode.REPLACE) },
                                onResolveConflict = { choice -> vm.resolveWebDavConflict(choice) },
                                onDismissConflict = { vm.dismissWebDavConflict() },
                                onDismissMessage = { vm.dismissWebDavMessage() },
                                onAutoSyncChange = { vm.setWebDavAutoSync(it) },
                            )
                        }
                    }

                    SettingsPage.ABOUT -> AboutPage(
                        demo = state.demo,
                        onToggleDemo = {
                            if (state.demo) vm.exitDemo() else vm.enterDemo()
                        },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        VehicleEditorDialog(
            initial = target.vehicle,
            title = if (target.id == null) tr("添加车辆", "Add vehicle") else tr("编辑车辆", "Edit vehicle"),
            recordCount = target.recordCount,
            onDismiss = { editing = null },
            onSave = { vehicle, prunePowertrainReminders ->
                editing = null
                if (target.id == null) {
                    vm.addVehicle(vehicle)
                } else {
                    vm.saveVehicle(vehicle, prunePowertrainReminders = prunePowertrainReminders)
                }
            },
            onDelete = target.id?.let { id -> { vm.deleteVehicle(id) } },
        )
    }
}

/** 设置子页统一的滚动容器：16/24dp 页边距、内容 max 840dp、底部 96dp 预留。 */
@Composable
private fun SettingsPageList(
    narrow: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontal = if (narrow) {
            if (maxWidth >= 600.dp) 32.dp else 28.dp
        } else {
            if (maxWidth >= 600.dp) 24.dp else MileNoteDimens.PageHorizontal
        }
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = if (narrow) 560.dp else 840.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(
                start = horizontal,
                end = horizontal,
                top = MileNoteDimens.CardGap,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap),
            content = content,
        )
    }
}

/** 设置菜单行：36dp 图标色底圆片 + 标题 + 摘要 + 可选 chevron；无 onClick 时作为只读说明行。 */
/**
 * 设置一级/二级页的菜单行：单色图标 + 标题（+ 可选一行说明），无图标底色。
 */
@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    subtitle: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) {
            Icon(
                imageVector = MileNoteIcons.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 首页 / 数据页顶部的提示区（账本读取错误、演示横幅、导出导入结果）。 */
@Composable
private fun SettingsMessageArea(
    loadError: String?,
    demo: Boolean,
    message: SettingsMessage?,
    onExitDemo: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loadError?.let { MessageBar(message = it, kind = MessageKind.ERROR) }
        if (demo) {
            MessageBar(
                message = tr("演示账本 · 修改不会保存到真实账本", "Demo ledger · Changes are not saved to your real ledger"),
                kind = MessageKind.DEMO,
                actionLabel = tr("退出演示", "Exit demo"),
                onAction = onExitDemo,
            )
        }
        message?.let { current ->
            MessageBar(
                message = current.text,
                kind = current.kind,
                actionLabel = current.actionLabel,
                onAction = current.onAction,
                onDismiss = onDismissMessage,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 车辆卡片
// ---------------------------------------------------------------------------------------------

/** 车辆卡片：缩略图 + 昵称 + 车牌/车型 + 当前里程 + 记录数；点卡片切车，右上角编辑。 */
@Composable
private fun VehicleCard(
    ledger: Ledger,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val vehicle = ledger.vehicle
    val secondary = listOf(vehicle.plate, vehicle.model)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { tr("未填写车牌 / 车型", "No plate / model") }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        border = when {
            selected -> BorderStroke(2.dp, scheme.primary)
            else -> BorderStroke(1.dp, scheme.outlineVariant)
        },
        modifier = Modifier.width(248.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VehicleThumb(
                    image = vehicle.image,
                    modifier = Modifier.size(width = 56.dp, height = 44.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = DataLabels.vehicleName(vehicle.name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 纯电车辆徽标（燃油车不显示），多车时一眼可辨。
                        if (ledger.isEv) {
                            Surface(
                                shape = MileNoteShapeTokens.full,
                                color = if (selected) scheme.primary else scheme.secondaryContainer,
                            ) {
                                Text(
                                    text = tr("纯电", "EV"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) scheme.onPrimary else scheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            scheme.onPrimaryContainer.copy(alpha = 0.78f)
                        } else {
                            scheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = tr("%s %s", "%s %s", Format.fmt(currentKm(ledger)), Units.distanceUnit),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = tr("· %d 条记录", "· %d records", ledger.records.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        scheme.onPrimaryContainer.copy(alpha = 0.78f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selected) {
                    Surface(
                        shape = MileNoteShapeTokens.full,
                        color = scheme.primary,
                    ) {
                        Text(
                            text = tr("当前", "Current"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 外观
// ---------------------------------------------------------------------------------------------

private fun darkModeOptions(): List<Pair<DarkMode, String>> = listOf(
    DarkMode.SYSTEM to tr("跟随系统", "System"),
    DarkMode.LIGHT to tr("浅色", "Light"),
    DarkMode.DARK to tr("深色", "Dark"),
)

/** 语言选择项：跟随系统 / 中文 / English（后两项原样显示）。 */
private fun languageOptions(): List<Pair<AppLanguage, String>> = listOf(
    AppLanguage.SYSTEM to tr("跟随系统", "System"),
    AppLanguage.ZH to "中文",
    AppLanguage.EN to "English",
)

/** 货币选择项：跟随语言（默认）/ 元 / 美元。只影响显示符号。 */
private fun currencyOptions(): List<Pair<CurrencyUnit?, String>> = listOf(
    null to tr("跟随语言", "Auto"),
    CurrencyUnit.CNY to tr("元", "CNY"),
    CurrencyUnit.USD to tr("美元", "USD"),
)

/** 里程选择项：公里 / 英里。只影响显示标签。 */
private fun distanceUnitOptions(): List<Pair<DistanceUnit, String>> = listOf(
    DistanceUnit.KM to tr("公里", "km"),
    DistanceUnit.MILE to tr("英里", "mi"),
)

/** 油量选择项：升 / 加仑。只影响显示标签。 */
private fun volumeUnitOptions(): List<Pair<VolumeUnit, String>> = listOf(
    VolumeUnit.LITER to tr("升", "L"),
    VolumeUnit.GALLON to tr("加仑", "gal"),
)

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap)) {
        SectionCard(title = tr("界面皮肤", "Theme")) {
            ThemeSwatchRow(selected = appTheme, onSelect = onThemeChange)
        }
        SectionCard(title = tr("深浅色", "Light / dark")) {
            SegmentedChoiceRow(
                options = darkModeOptions(),
                selected = settings.darkMode,
                onSelect = onDarkModeChange,
            )
        }
        SectionCard(title = tr("语言", "Language")) {
            // 切换后立即生效（tr 读的是 Compose 快照状态，组合会自动重组）。
            SegmentedChoiceRow(
                options = languageOptions(),
                selected = language,
                onSelect = onLanguageChange,
            )
        }
    }
}

/**
 * 单位页（与「外观」平级）：货币 / 里程 / 油量只影响显示标签，不做数值换算。
 *
 * 油量单位只服务燃油车；纯电车辆充电量固定为度（kWh），不随该设置变化。
 */
@Composable
private fun UnitsSection(
    settings: AppSettings,
    onCurrencyChange: (CurrencyUnit?) -> Unit,
    onDistanceUnitChange: (DistanceUnit) -> Unit,
    onVolumeUnitChange: (VolumeUnit) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard {
        Text(
            text = tr("单位只改显示标签，不换算数值。", "Units only change display labels; values are not converted."),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(12.dp))

        Text(tr("货币", "Currency"), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        SegmentedChoiceRow(
            options = currencyOptions(),
            selected = settings.currency,
            onSelect = onCurrencyChange,
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = tr("默认跟随语言：中文元 / 英文美元。", "Follows the language by default: CNY for Chinese, USD for English."),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        SectionDivider()

        Text(tr("里程", "Distance"), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        SegmentedChoiceRow(
            options = distanceUnitOptions(),
            selected = settings.distanceUnit,
            onSelect = onDistanceUnitChange,
        )

        SectionDivider()

        Text(tr("油量（燃油车）", "Volume (fuel vehicles)"), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        SegmentedChoiceRow(
            options = volumeUnitOptions(),
            selected = settings.volumeUnit,
            onSelect = onVolumeUnitChange,
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = tr(
                "纯电车辆充电量固定显示为度（kWh），不受此项影响。",
                "For EVs, charging amounts always display in kWh and are not affected by this setting.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        SectionDivider()

        Text(
            text = tr(
                "燃油按里程/油量组合，纯电固定为度/百公里。",
                "Fuel economy follows the distance/volume combination; EVs always use kWh/100 km.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** 5 套皮肤色点选择（单选语义）。 */
@Composable
private fun ThemeSwatchRow(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppTheme.entries.forEach { theme ->
            val isSelected = theme == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(theme) },
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(theme.swatch),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = MileNoteIcons.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = theme.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 通用单选段控（深浅色 / 图片显示方式等 2–3 项选择）。 */
@Composable
private fun <T> SegmentedChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 费用分类与项目：页签外壳
// ---------------------------------------------------------------------------------------------

/** 费用分类 / 项目库两个固定页签；作用域提示与页签不随内容滚动。 */
@Composable
private fun CategoryTabsPage(
    tab: Int,
    onTabChange: (Int) -> Unit,
    ledger: Ledger?,
    themeId: String,
    vehicleName: String,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRemoveProject: (category: String, name: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = tr("当前车辆：%s", "Current vehicle: %s", DataLabels.vehicleName(vehicleName)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = MileNoteDimens.PageHorizontal,
                end = MileNoteDimens.PageHorizontal,
                top = MileNoteDimens.CardGap,
                bottom = 6.dp,
            ),
        )
        SecondaryTabs(
            labels = listOf(tr("费用分类", "Categories"), tr("项目库", "Item library")),
            selected = tab,
            onSelect = onTabChange,
        )
        Box(modifier = Modifier.weight(1f)) {
            ExpenseCategoriesPage(
                tab = tab,
                ledger = ledger,
                themeId = themeId,
                onAddCategory = onAddCategory,
                onRemoveCategory = onRemoveCategory,
                onAddProject = onAddProject,
                onRemoveProject = onRemoveProject,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 外部导入
// ---------------------------------------------------------------------------------------------

/** 外部导入首页：来源列表；点来源直接进向导（来源预选）。纯电当前车隐藏「小熊油耗」。 */
@Composable
private fun ExternalImportPage(
    ev: Boolean,
    onOpenImporter: (String) -> Unit,
    onGoBackup: () -> Unit,
) {
    SettingsPageList {
        item("sources") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            ) {
                // 小熊油耗只有加油记录（XLS/XLSX），纯电车辆不适用；钱迹等入口保持不变。
                if (!ev) {
                    SettingsMenuRow(
                        icon = MileNoteIcons.CategoryFuel,
                        title = tr("小熊油耗", "Bear"),
                        subtitle = tr("XLS / XLSX · 导入加油记录", "XLS / XLSX · Import fuel records"),
                        onClick = { onOpenImporter("bear") },
                    )
                    SectionDivider()
                }
                SettingsMenuRow(
                    icon = MileNoteIcons.Wallet,
                    title = tr("钱迹记账", "Qianji"),
                    subtitle = tr("TXT / JSON / CSV · 导入支出记录", "TXT / JSON / CSV · Import expense records"),
                    onClick = { onOpenImporter("qianji") },
                )

            }
        }

        item("note") {
            Text(
                text = tr(
                    "导入的记录会标记来源；重复记录按现有合并规则处理（合并或跳过）；" +
                        "表格文件上限 10 MB，只在本机解析，不会上传。",
                    "Imported records keep their source label; duplicates follow the usual merge rules (merge or skip). " +
                        "Spreadsheets are limited to 10 MB and are parsed on this device only — nothing is uploaded.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 备份与还原
// ---------------------------------------------------------------------------------------------

/** 备份与还原：导出（JSON / CSV）、还原（JSON）、危险区。 */
@Composable
private fun BackupRestorePage(
    carsCount: Int,
    recordsCount: Int,
    demo: Boolean,
    loadError: String?,
    quarantinedRaw: QuarantinedRaw?,
    busy: Boolean,
    message: SettingsMessage?,
    onExportBackup: () -> Unit,
    onExportCsv: () -> Unit,
    onExportRaw: () -> Unit,
    onRefreshQuarantinedRaw: suspend () -> Unit,
    onImportBackup: () -> Unit,
    onDeleteAll: () -> Unit,
    onExitDemo: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    // 每次进入本页都重新检查是否有被隔离的损坏账本（读取失败时自动隔离）。
    LaunchedEffect(Unit) { onRefreshQuarantinedRaw() }
    SettingsPageList {
        if (loadError != null || demo || message != null) {
            item("messages") {
                SettingsMessageArea(
                    loadError = loadError,
                    demo = demo,
                    message = message,
                    onExitDemo = onExitDemo,
                    onDismissMessage = onDismissMessage,
                )
            }
        }

        item("status") {
            SectionCard(title = tr("账本", "Ledger")) {
                Text(
                    text = tr("%d 辆车 · %d 条记录", "%d vehicles · %d records", carsCount, recordsCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = tr(
                        "数据只保存在本机；JSON 备份包含全部车辆、图片与提醒，可跨设备还原。",
                        "Data is stored on this device only; a JSON backup includes all vehicles, images and reminders and can be restored on another device.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("export") {
            SectionCard(
                title = tr("导出", "Export"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Export,
                    title = tr("导出完整备份（JSON）", "Export full backup (JSON)"),
                    subtitle = tr("全部车辆与记录，可跨设备还原", "Everything, restorable on another device"),
                    enabled = !busy,
                    onClick = onExportBackup,
                    trailing = {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
                if (quarantinedRaw != null) {
                    SectionDivider()
                    SettingsActionRow(
                        icon = MileNoteIcons.Warning,
                        title = tr("导出原始账本（损坏文件）", "Export raw ledger (corrupted file)"),
                        subtitle = tr("仅用于检查或抢救，不能直接还原", "For inspection only; cannot be restored"),
                        enabled = !busy,
                        onClick = onExportRaw,
                    )
                }
                SectionDivider()
                SettingsActionRow(
                    icon = MileNoteIcons.FileText,
                    title = tr("导出费用 CSV", "Export expense CSV"),
                    subtitle = tr("报表仅用于查看和对账", "Report for viewing and reconciling only"),
                    enabled = !busy,
                    onClick = onExportCsv,
                )
            }
        }

        item("restore") {
            SectionCard(
                title = tr("还原", "Restore"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Import,
                    title = tr("从 JSON 备份还原", "Restore from JSON backup"),
                    subtitle = tr("只合并新增，不覆盖现有数据", "Merges new records only; never overwrites"),
                    enabled = !busy,
                    onClick = onImportBackup,
                )
            }
        }

        item("danger") {
            SectionCard(
                title = tr("危险区", "Danger zone"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Delete,
                    title = tr("删除全部数据", "Delete all data"),
                    subtitle = tr("清空全部数据，需输入「删除」确认", "Wipes all data; type “删除” to confirm"),
                    danger = true,
                    onClick = onDeleteAll,
                )
            }
        }

    }
}

// ---------------------------------------------------------------------------------------------
// 关于
// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutPage(
    demo: Boolean,
    onToggleDemo: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun checkForUpdates() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            checkingUpdate = false
            updateDialog = when (result) {
                is UpdateCheckResult.Available -> UpdateDialogState(
                    title = tr("发现新版本 %s", "Version %s available", result.info.version),
                    message = buildString {
                        if (result.info.notes.isNotBlank()) {
                            append(result.info.notes.trim())
                            append("\n\n")
                        }
                        append(tr("是否前往 GitHub 下载最新 APK？", "Open GitHub to download the latest APK?"))
                    },
                    link = result.info.apkUrl ?: result.info.pageUrl,
                )

                UpdateCheckResult.UpToDate -> UpdateDialogState(
                    title = tr("已是最新版本", "You're up to date"),
                    message = tr(
                        "当前版本 %s (%s) 已是最新。",
                        "Version %s (%s) is up to date.",
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    link = null,
                )

                is UpdateCheckResult.Failed -> UpdateDialogState(
                    title = tr("检查更新失败", "Update check failed"),
                    message = result.message,
                    link = null,
                )
            }
        }
    }

    SettingsPageList {
        item("app") {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 应用真实图标（自适应图标的背景色 + 前景层），不使用占位图标。
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("里程志", "MileNote"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = tr(
                        "本地优先的多车用车账本：记录费用与加油、用满箱法算油耗、管理保养计划；" +
                            "数据只保存在本机，无账号、无后端。",
                        "A local-first ledger for your cars: track expenses and refuels, compute fuel economy " +
                            "with the full-tank method, and manage service plans. Your data stays on this device — " +
                            "no account, no backend.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("links") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Refresh,
                    title = tr("检查更新", "Check for updates"),
                    subtitle = tr("检查 GitHub 上是否有新版本", "Check GitHub for a new version"),
                    enabled = !checkingUpdate,
                    onClick = { checkForUpdates() },
                    trailing = {
                        if (checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Globe,
                    title = tr("官网", "Website"),
                    subtitle = "selwiro.github.io/MileNoteAndroid",
                    onClick = { openLink("https://selwiro.github.io/MileNoteAndroid/") },
                    showChevron = false,
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Code,
                    title = tr("GitHub 仓库", "GitHub repository"),
                    subtitle = "github.com/Selwiro/MileNoteAndroid",
                    onClick = { openLink("https://github.com/Selwiro/MileNoteAndroid") },
                    showChevron = false,
                )
            }
        }

        item("demo") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Sparkle,
                    title = if (demo) tr("退出演示账本", "Exit demo ledger") else tr("体验演示账本", "Try the demo ledger"),
                    subtitle = if (demo) {
                        tr("内置样例，修改不保存", "Built-in samples; changes are not saved")
                    } else {
                        tr("内置样例体验，不写入真实账本", "Try built-in samples; nothing is written")
                    },
                    onClick = onToggleDemo,
                )
            }
        }

    }

    updateDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { updateDialog = null },
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = {
                if (dialog.link != null) {
                    TextButton(
                        onClick = {
                            openLink(dialog.link)
                            updateDialog = null
                        },
                    ) {
                        Text(tr("去下载", "Download"))
                    }
                } else {
                    TextButton(onClick = { updateDialog = null }) {
                        Text(tr("关闭", "Close"))
                    }
                }
            },
            dismissButton = if (dialog.link != null) {
                {
                    TextButton(onClick = { updateDialog = null }) {
                        Text(tr("稍后", "Later"))
                    }
                }
            } else {
                null
            },
        )
    }
}


// ---------------------------------------------------------------------------------------------
// 通用行与分隔线
// ---------------------------------------------------------------------------------------------

/** 数据区的一行动作：图标 + 标题 + 说明；动作行不带 chevron（进入子页才用 chevron）。 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> scheme.onSurface.copy(alpha = 0.38f)
                    danger -> scheme.error
                    else -> scheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        scheme.onSurfaceVariant
                    } else {
                        scheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 区块内分隔线（带左右缩进，避免与图标列打架）。 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
