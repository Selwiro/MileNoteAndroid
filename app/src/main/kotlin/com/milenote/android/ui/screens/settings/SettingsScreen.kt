@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.milenote.android.ui.screens.settings

import android.os.Build
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.BuildConfig
import com.milenote.android.data.settings.AppSettings
import com.milenote.android.data.settings.DarkMode
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankVehicle
import com.milenote.android.platform.FileExporter
import com.milenote.android.platform.LedgerExports
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.PendingConfirm
import com.milenote.android.ui.WebDavDownloadMode
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.SectionHeader
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.AppTheme
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 返回上一页的导航约定（兜底）：宿主没有 `OnBackPressedDispatcher` 时，
 * 导航层收到该值应执行 `navController.popBackStack()`。
 *
 * 正常情况下返回箭头直接走 `onBackPressedDispatcher.onBackPressed()`，
 * 与系统返回键、手势返回共用同一条返回栈，不需要导航层额外特判。
 */
const val SETTINGS_NAV_BACK: String = "back"

/** 设置页内的轻量提示（导出/导入/图片处理的结果；账本类操作由 VM 的全局 Snackbar 播报）。 */
private data class SettingsMessage(val text: String, val kind: MessageKind)

/** 正在编辑的车辆：`id == null` 表示新增。 */
private data class VehicleEditTarget(
    val id: String?,
    val vehicle: Vehicle,
    val recordCount: Int,
)

/** 设置内部页面（用内部状态切换，不新增导航路由；系统返回键先回菜单）。 */
private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    VEHICLES("我的车辆"),
    APPEARANCE("外观"),
    CATEGORIES("费用分类与项目"),
    DATA("数据管理"),
    ABOUT("关于"),
    WEBDAV("WebDAV 同步"),
}

/** 子页的上级页：WebDAV 从数据管理进入，返回回到数据管理；其余子页返回设置首页。 */
private fun SettingsPage.parent(): SettingsPage = when (this) {
    SettingsPage.WEBDAV -> SettingsPage.DATA
    else -> SettingsPage.ROOT
}

/**
 * 设置（用户要求的子菜单结构，替代原「一页到底」的车辆与设置页）。
 *
 * 首页：5 行菜单（40dp 图标色底圆片 + 标题 + 摘要副标题 + chevron）——
 * 我的车辆 / 外观 / 费用分类与项目 / 数据管理 / 关于。
 * 点按进入子页；子页共用同一个 TopAppBar（返回箭头回到菜单），系统返回键用
 * [BackHandler] 先回菜单；不新增路由。原有全部功能原样保留在对应子页。
 *
 * - 我的车辆：横向车辆卡片（缩略图 / 昵称 / 车牌·车型 / 当前里程 / 记录数；选中描边 +「当前」），
 *   点按切换账本，卡片菜单编辑/删除，添加车辆打开全屏编辑对话框（图片/里程/日期/contain-cover）。
 * - 外观：5 套皮肤、深浅色三态。
 * - 费用分类与项目：9 类内置费用分类只读 + 自定义分类增删；项目库按保养/维修/汽车美容/保险
 *   分组展示内置与自定义项目。
 * - 数据管理：导出完整备份（SAF）/ 分享、导出费用 CSV、导入备份、导入账单/保养台账、
 *   演示账本、删除全部数据。
 * - 关于：应用名、版本、包名、数据存放与口径说明。
 *
 * @param vm 全局 ViewModel。
 * @param onNavigate 路由回调；仅在首页返回、且宿主没有 OnBackPressedDispatcher 时回传
 *   [SETTINGS_NAV_BACK]（兜底）。
 * @param onOpenImporter 打开导入向导（账单/保养台账），参数为来源 id（本页传 null）。
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
    val activeName = activeCar?.vehicle?.name?.ifBlank { "我的爱车" } ?: "—"
    val appTheme = AppTheme.fromId(garage?.theme)
    val themeId = garage?.theme ?: "blue"

    var message by remember { mutableStateOf<SettingsMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VehicleEditTarget?>(null) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.ROOT.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName } ?: SettingsPage.ROOT

    fun notify(text: String, kind: MessageKind) {
        message = SettingsMessage(text, kind)
    }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingBackup
        pendingBackup = null
        if (uri != null && text != null) {
            runCatching { FileExporter.writeTo(context, uri, text.toByteArray(Charsets.UTF_8)) }
                .onSuccess { notify("完整备份已导出", MessageKind.SUCCESS) }
                .onFailure { notify("导出失败：${it.message ?: "无法写入所选文件"}", MessageKind.ERROR) }
        }
    }

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val bytes = pendingCsv
        pendingCsv = null
        if (uri != null && bytes != null) {
            runCatching { FileExporter.writeTo(context, uri, bytes) }
                .onSuccess { notify("费用 CSV 已导出", MessageKind.SUCCESS) }
                .onFailure { notify("导出失败：${it.message ?: "无法写入所选文件"}", MessageKind.ERROR) }
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
                        ?: throw IllegalStateException("无法读取所选文件")
                }
                if (text.length > 30_000_000) {
                    throw IllegalStateException("备份文件超过 30 MB，已取消导入")
                }
                // 合并规则与提示由 VM/仓库统一处理（结果走全局 Snackbar）。
                vm.restoreBackup(text)
            } catch (t: Throwable) {
                notify("导入备份失败：${t.message ?: "文件格式不正确"}", MessageKind.ERROR)
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
                notify("导出失败：${t.message ?: "无法生成备份"}", MessageKind.ERROR)
            } finally {
                busy = false
            }
        }
    }

    fun shareBackupFile() {
        scope.launch {
            busy = true
            try {
                val text = vm.exportBackup()
                FileExporter.shareText(
                    context = context,
                    fileName = LedgerExports.backupFileName(),
                    mimeType = "application/json",
                    bytes = text.toByteArray(Charsets.UTF_8),
                )
            } catch (t: Throwable) {
                notify("分享失败：${t.message ?: "无法生成备份"}", MessageKind.ERROR)
            } finally {
                busy = false
            }
        }
    }

    fun exportCsvFile() {
        val ledger = activeCar
        if (ledger == null) {
            notify("当前没有可导出的车辆", MessageKind.WARNING)
            return
        }
        pendingCsv = LedgerExports.csv(ledger)
        createCsv.launch(LedgerExports.csvFileName())
    }

    fun openVehicleEditor(id: String?) {
        // saveVehicle 写当前车辆，编辑前先切到目标车，避免把数据写到别的账本。
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
        pageName = page.parent().name
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
                                pageName = page.parent().name
                            }
                        },
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Back,
                            contentDescription = if (page == SettingsPage.ROOT) "返回" else "返回设置",
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                LoadingState(label = "正在读取本机账本…")
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

                    item("menu") {
                        SectionCard(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        ) {
                            SettingsMenuRow(
                                icon = MileNoteIcons.Car,
                                title = "我的车辆",
                                subtitle = if (cars.isEmpty()) {
                                    "还没有车辆，点按添加"
                                } else {
                                    "${cars.size} 辆 · 当前：$activeName"
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = { pageName = SettingsPage.VEHICLES.name },
                            )
                            SectionDivider()
                            SettingsMenuRow(
                                icon = MileNoteIcons.Sparkle,
                                title = "外观",
                                subtitle = appearanceSummary(appTheme, settings),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = { pageName = SettingsPage.APPEARANCE.name },
                            )
                            SectionDivider()
                            SettingsMenuRow(
                                icon = MileNoteIcons.Wallet,
                                title = "费用分类与项目",
                                subtitle = "${Categories.ALL.size} 类费用 · 保养/维修/美容/保险项目",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                onClick = { pageName = SettingsPage.CATEGORIES.name },
                            )
                            SectionDivider()
                            SettingsMenuRow(
                                icon = MileNoteIcons.Download,
                                title = "数据管理",
                                subtitle = "备份、导入、CSV、演示、删除",
                                containerColor = LocalMileNoteSemanticColors.current.info.container,
                                contentColor = LocalMileNoteSemanticColors.current.info.content,
                                onClick = { pageName = SettingsPage.DATA.name },
                            )
                            SectionDivider()
                            SettingsMenuRow(
                                icon = MileNoteIcons.Info,
                                title = "关于",
                                subtitle = "里程志 ${BuildConfig.VERSION_NAME}",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = { pageName = SettingsPage.ABOUT.name },
                            )
                        }
                    }
                }

                SettingsPage.VEHICLES -> SettingsPageList {
                    item("vehicles-header") {
                        SectionHeader(
                            title = "车辆列表",
                            subtitle = "共 ${cars.size} 辆 · ${cars.sumOf { it.records.size }} 条记录 · 点按卡片进入编辑",
                            actionLabel = "添加车辆",
                            actionIcon = MileNoteIcons.Add,
                            onAction = { openVehicleEditor(null) },
                        )
                    }

                    item("vehicles") {
                        if (cars.isEmpty()) {
                            EmptyState(
                                title = "还没有车辆",
                                description = "添加第一辆车，开始记录每一公里的花费。",
                                icon = MileNoteIcons.Car,
                                actionLabel = "添加车辆",
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

                    item("vehicles-hint") {
                        Text(
                            text = "点按车辆卡片进入编辑；可在编辑页设置昵称、里程、提车日期与图片，也可删除车辆。" +
                                "删除车辆会一并移除该车的费用、提醒与保养记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        )
                    }
                }

                SettingsPage.CATEGORIES -> SettingsPageList {
                    item("categories") {
                        ExpenseCategoriesPage(
                            ledger = activeCar,
                            themeId = themeId,
                            vehicleName = activeName,
                            onAddCategory = { name -> vm.addCustomCategory(name) },
                            onRemoveCategory = { name ->
                                vm.confirm(
                                    PendingConfirm(
                                        title = "删除自定义分类？",
                                        message = "「$name」将从记账的可选分类中移除；已有记录的分类与金额不受影响。",
                                        confirmLabel = "删除",
                                        destructive = true,
                                    ) { vm.removeCustomCategory(name) },
                                )
                            },
                            onAddProject = { category, name -> vm.addProject(category, name) },
                            onRemoveProject = { category, name -> vm.removeProject(category, name) },
                        )
                    }
                }

                SettingsPage.DATA -> SettingsPageList {
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

                    item("webdav-entry") {
                        SectionCard {
                            SettingsMenuRow(
                                icon = MileNoteIcons.Download,
                                title = "WebDAV 同步",
                                subtitle = webDavStatusSummary(state.webDav),
                                containerColor = LocalMileNoteSemanticColors.current.info.container,
                                contentColor = LocalMileNoteSemanticColors.current.info.content,
                                onClick = { pageName = SettingsPage.WEBDAV.name },
                            )
                        }
                    }

                    item("data") {
                        DataSection(
                            carsCount = cars.size,
                            recordsCount = cars.sumOf { it.records.size },
                            activeName = activeName,
                            demo = state.demo,
                            busy = busy,
                            onExportBackup = { exportBackupFile() },
                            onShareBackup = { shareBackupFile() },
                            onExportCsv = { exportCsvFile() },
                            onImportBackup = {
                                openBackup.launch(
                                    arrayOf("application/json", "application/octet-stream", "text/plain"),
                                )
                            },
                            onOpenImporter = { onOpenImporter(null) },
                            onToggleDemo = {
                                if (state.demo) vm.exitDemo() else vm.enterDemo()
                            },
                            onDeleteAll = { vm.deleteAllData() },
                        )
                    }
                }

                SettingsPage.ABOUT -> SettingsPageList {
                    item("about") {
                        AboutSection(recordCount = cars.sumOf { it.records.size })
                    }
                }

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
            }
        }
        }
    }

    editing?.let { target ->
        VehicleEditorDialog(
            initial = target.vehicle,
            title = if (target.id == null) "添加车辆" else "编辑车辆",
            recordCount = target.recordCount,
            onDismiss = { editing = null },
            onSave = { vehicle ->
                editing = null
                if (target.id == null) vm.addVehicle(vehicle) else vm.saveVehicle(vehicle)
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
                top = 8.dp,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap),
            content = content,
        )
    }
}

/** 设置首页菜单行：40dp 图标色底圆片 + 标题 + 摘要 + chevron。 */
@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = MileNoteIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
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
                message = "演示账本 · 修改不会保存到真实账本",
                kind = MessageKind.DEMO,
                actionLabel = "退出演示",
                onAction = onExitDemo,
            )
        }
        message?.let { current ->
            MessageBar(
                message = current.text,
                kind = current.kind,
                onDismiss = onDismissMessage,
            )
        }
    }
}

/** 外观菜单摘要，如「里程蓝 · 跟随系统」。 */
private fun appearanceSummary(appTheme: AppTheme, settings: AppSettings): String {
    val darkLabel = when (settings.darkMode) {
        DarkMode.SYSTEM -> "跟随系统"
        DarkMode.LIGHT -> "浅色"
        DarkMode.DARK -> "深色"
    }
    return "${appTheme.label} · $darkLabel"
}

// ---------------------------------------------------------------------------------------------
// 车辆卡片
// ---------------------------------------------------------------------------------------------

/** 车辆卡片：缩略图 + 昵称 + 车牌/车型 + 当前里程 + 记录数；选中态描边 + 主色容器。 */
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
        .ifBlank { "未填写车牌 / 车型" }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
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
                    Text(
                        text = vehicle.name.ifBlank { "我的爱车" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    text = "${Format.fmt(currentKm(ledger))} 公里",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = "· ${ledger.records.size} 条记录",
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
                        contentColor = scheme.onPrimary,
                    ) {
                        Text(
                            text = "当前",
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

private val darkModeOptions = listOf(
    DarkMode.SYSTEM to "跟随系统",
    DarkMode.LIGHT to "浅色",
    DarkMode.DARK to "深色",
)

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = "外观",
        subtitle = "只改变配色与显示方式，不影响账本数据",
    ) {
        Text("界面皮肤", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        ThemeSwatchRow(selected = appTheme, onSelect = onThemeChange)

        SectionDivider()

        Text("深浅色", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        SegmentedChoiceRow(
            options = darkModeOptions,
            selected = settings.darkMode,
            onSelect = onDarkModeChange,
        )

        SectionDivider()

        Text(
            text = "当前皮肤：${appTheme.label} · ${if (settings.darkMode == DarkMode.SYSTEM) "深色跟随系统" else if (settings.darkMode == DarkMode.DARK) "深色" else "浅色"}",
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
                    text = theme.label,
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
// 账本与数据
// ---------------------------------------------------------------------------------------------

@Composable
private fun DataSection(
    carsCount: Int,
    recordsCount: Int,
    activeName: String,
    demo: Boolean,
    busy: Boolean,
    onExportBackup: () -> Unit,
    onShareBackup: () -> Unit,
    onExportCsv: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenImporter: () -> Unit,
    onToggleDemo: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = "账本与数据",
        subtitle = "数据保存在本机 · 备份为版本 2 JSON，可导出后跨设备恢复",
    ) {
        Text(
            text = "共 $carsCount 辆车 · $recordsCount 条记录 · 当前车辆：$activeName",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))

        SettingsActionRow(
            icon = MileNoteIcons.Export,
            title = "导出完整备份",
            subtitle = "全部车辆、图片、费用与保养提醒（JSON）",
            enabled = !busy,
            onClick = onExportBackup,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = onShareBackup, enabled = !busy) {
                        Icon(
                            imageVector = MileNoteIcons.Share,
                            contentDescription = "分享完整备份",
                            modifier = Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
        SectionDivider()

        SettingsActionRow(
            icon = MileNoteIcons.Download,
            title = "导出费用 CSV",
            subtitle = "当前车辆全部记录 · UTF-8（Excel 可直接打开）",
            enabled = !busy,
            onClick = onExportCsv,
        )
        SectionDivider()

        SettingsActionRow(
            icon = MileNoteIcons.Import,
            title = "导入备份",
            subtitle = "从 JSON 备份恢复；只合并新增记录，不覆盖现有数据",
            enabled = !busy,
            onClick = onImportBackup,
        )
        SectionDivider()

        SettingsActionRow(
            icon = MileNoteIcons.Records,
            title = "导入账单 / 保养台账",
            subtitle = "小熊油耗、钱迹记账、Excel / CSV 保养手册",
            onClick = onOpenImporter,
        )
        SectionDivider()

        SettingsActionRow(
            icon = MileNoteIcons.Sparkle,
            title = if (demo) "退出演示账本" else "演示账本",
            subtitle = if (demo) {
                "当前为内置样例数据，修改不会保存；返回你的真实车库"
            } else {
                "用内置样例体验多车、换肤与导入（不写入真实账本）"
            },
            onClick = onToggleDemo,
        )
        SectionDivider()

        SettingsActionRow(
            icon = MileNoteIcons.Delete,
            title = "删除全部数据",
            subtitle = "清空所有车辆与记录，需输入「删除」确认；建议先导出备份",
            danger = true,
            onClick = onDeleteAll,
        )
    }
}

/** 数据区的一行动作：图标 + 标题 + 说明 + 尾部（默认箭头）。 */
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
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (danger) scheme.errorContainer else scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) scheme.onErrorContainer else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = MileNoteIcons.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 区块内分隔线（带左右缩进，避免与图标列打架）。 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

// ---------------------------------------------------------------------------------------------
// 关于
// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutSection(recordCount: Int) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = "关于") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MileNoteIcons.Car,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("里程志", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "MileNote · 汽车助手",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        AboutRow(label = "版本", value = BuildConfig.VERSION_NAME)
        AboutRow(label = "包名", value = BuildConfig.APPLICATION_ID)
        AboutRow(label = "本机记录", value = "$recordCount 条费用记录")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(scheme.surfaceContainerHigh)
                .padding(12.dp),
        ) {
            Text(
                text = "账本只保存在本机应用存储中；导入导出的文件由你自行选择存放位置，不会上传服务器。" +
                    "里程、油耗与成本口径与 Web 版一致（满箱法、区间归属、油费来源冲突处理）。" +
                    "界面基于 Material 3 Expressive，图标为里程志自绘线描集。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
