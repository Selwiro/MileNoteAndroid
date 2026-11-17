@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.milenote.android.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.data.garage.restoreSummaryMessage
import com.milenote.android.BuildConfig
import com.milenote.android.R
import com.milenote.android.data.settings.AppSettings
import com.milenote.android.data.settings.DarkMode
import com.milenote.android.data.settings.backupOverdueDays
import com.milenote.android.data.settings.formatBackupAt
import com.milenote.android.data.update.UpdateCheckResult
import com.milenote.android.data.update.UpdateChecker
import com.milenote.android.domain.backup.BACKUP_FILE_KEEP_OPTIONS
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankVehicle
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.i18n.AppLanguage
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.platform.BackupFolder
import com.milenote.android.platform.FileExporter
import com.milenote.android.platform.LedgerExports
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.PendingConfirm
import com.milenote.android.ui.QuarantinedRaw
import com.milenote.android.ui.WebDavDownloadMode
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MarkdownText
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SecondaryTabs
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.SectionHeader
import com.milenote.android.ui.screens.importer.BACKUP_MAX_BYTES
import com.milenote.android.ui.screens.importer.decodeText
import com.milenote.android.ui.screens.importer.readUriBytes
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.AppTheme
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页内的轻量提示（导出/导入/图片处理的结果；账本类操作由 VM 的全局 Snackbar 播报）。 */
internal data class SettingsMessage(
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
    /** Release 正文的 Markdown（只有「发现新版本」时有）；渲染成富文本后滚动展示。 */
    val notes: String? = null,
)

/** 许可证全文弹层。 */
private data class LicenseDialogState(
    val title: String,
    val text: String,
)

/** 固定目录备份的结果对话框（成功 / 失败都走这里，不再只用顶部消息条）。 */
private data class BackupDialogState(
    val title: String,
    val body: String,
    /** 成功时可分享这次写出的 JSON。 */
    val shareText: String? = null,
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
internal enum class SettingsPage {
    VEHICLES,
    APPEARANCE,
    UNITS,
    CATEGORIES,
    IMPORT,
    HEALTH,
    BACKUP,
    GARAGE,
    WEBDAV,
    ABOUT,
    PRIVACY,
    LICENSES,
    ;

    /** 路由参数用的稳定 key（小写；改动会让旧深链失效，不要随手改）。 */
    val key: String
        get() = name.lowercase()

    /** 顶栏标题（随语言切换；不要放进枚举构造参数，那样会固定在类加载时的语言）。 */
    val title: String
        get() = when (this) {
            VEHICLES -> tr("我的车辆", "My vehicles")
            APPEARANCE -> tr("外观", "Appearance")
            UNITS -> tr("单位", "Units")
            CATEGORIES -> tr("费用分类与项目", "Categories & items")
            IMPORT -> tr("外部导入", "Import")
            HEALTH -> tr("数据体检", "Data check")
            BACKUP -> tr("备份与还原", "Backup & restore")
            GARAGE -> tr("车库总览", "Garage overview")
            WEBDAV -> tr("WebDAV 同步", "WebDAV sync")
            ABOUT -> tr("关于", "About")
            PRIVACY -> tr("隐私与数据", "Privacy & data")
            LICENSES -> tr("开源许可", "Licenses")
        }

    companion object {
        /** 路由参数 -> 子页；未知 / 空值回退到「我的车辆」。 */
        fun fromKey(key: String?): SettingsPage =
            entries.firstOrNull { it.key == key } ?: VEHICLES
    }
}

/**
 * 设置子页（底栏 v4 起设置首页已抽成「我的」菜单页 [MineScreen]，本页只渲染单个子页）。
 *
 * 从「我的」菜单推入，路由参数 `page` 决定初始子页（[SettingsPage.fromKey]）；顶栏返回箭头 =
 * `onBack()`（导航层 pop 回「我的」）。页内切换不压导航栈，但返回要按层级走：
 * 「车库总览」先回「我的车辆」，从「关于」切进去的页先回「关于」，再返回才回「我的」；
 * 用路由直接打开这些页时仍 pop 回「我的」。
 *
 * - 我的车辆：横向车辆卡片，点卡片切换当前车辆，卡片右上角编辑；添加车辆打开全屏编辑对话框。
 * - 外观：5 套皮肤、深浅色三态。
 * - 单位：货币 / 里程 / 油量显示口径（只改显示标签，不换算数值；油量只服务燃油车）。
 * - 费用分类与项目：费用分类 / 项目库两个页签；分类按当前车辆隔离。
 * - 外部导入：小熊油耗、钱迹记账、其他 App 分享；来源预选后直接进入导入向导（纯电当前车隐藏小熊油耗）。
 * - 备份与还原：JSON 完整备份、费用 CSV 导出、JSON 还原、删除全部数据。
 * - WebDAV 同步：连接配置、前台自动同步、手动上传/下载。
 * - 关于：应用 logo 与简介、检查更新、官网/GitHub、隐私与数据、开源许可入口、演示账本。
 * - 开源许可：本应用与第三方组件的许可证全文（离线内嵌）、Android SDK 许可条款链接。
 *
 * @param vm 全局 ViewModel。
 * @param initialPage 初始子页 key（路由参数，见 [SettingsPage.key]）。
 * @param onBack 返回「我的」（导航层 popBackStack）。
 * @param onOpenImporter 打开导入向导（外部导入），参数为来源 id（bear / qianji）。
 * @param modifier 外部修饰符。
 */
@Composable
fun SettingsScreen(
    vm: MileNoteViewModel,
    initialPage: String,
    onBack: () -> Unit,
    onOpenImporter: (String?) -> Unit = {},
    onOpenDashboard: () -> Unit = {},
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
    var showSetupWizard by remember { mutableStateOf(false) }
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    var pendingXlsx by remember { mutableStateOf<ByteArray?>(null) }
    var pendingRaw by remember { mutableStateOf<ByteArray?>(null) }
    var backupFolderName by remember { mutableStateOf<String?>(null) }
    // 备份结果对话框：成功 / 失败都放到用户眼前，不依赖可能被内容盖住的顶部消息条。
    var backupDialog by remember { mutableStateOf<BackupDialogState?>(null) }
    // 首次点「立即备份到此处」：先拉一次目录选择器，选完自动建 milenote 并立刻备份。
    var backupAfterPick by remember { mutableStateOf(false) }

    // 固定目录备份：目录显示名（SAF 查询放 IO，避免组合期读 ContentResolver）。
    LaunchedEffect(settings.backupFolderUri, settings.backupFolderSubdir) {
        val raw = settings.backupFolderUri
        backupFolderName = if (raw == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    BackupFolder.displayName(context, Uri.parse(raw), settings.backupFolderSubdir)
                }.getOrNull()
            }
        }
    }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.fromKey(initialPage).name) }
    // 「关于 → 隐私与数据 / 开源许可」是页内切换，没有导航栈；用这个标记让返回回「关于」而不是直接回「我的」。
    var cameFromAbout by rememberSaveable { mutableStateOf(false) }
    var categoryTab by rememberSaveable { mutableIntStateOf(0) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName } ?: SettingsPage.fromKey(initialPage)

    /** 顶栏 / 系统返回：车库总览先回「我的车辆」，关于子页先回「关于」，其余 pop 回「我的」。 */
    fun goBack() {
        when {
            pageName == SettingsPage.GARAGE.name -> pageName = SettingsPage.VEHICLES.name
            cameFromAbout -> {
                pageName = SettingsPage.ABOUT.name
                cameFromAbout = false
            }
            else -> onBack()
        }
    }

    // 页内切换不压导航栈，系统返回键也走同一套规则（车库总览 → 我的车辆、关于子页 → 关于）。
    BackHandler(enabled = cameFromAbout || pageName == SettingsPage.GARAGE.name) { goBack() }

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
                    // 只有真正写盘成功才更新时间；取消（uri == null）或失败不更新。
                    vm.markBackupDone()
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

    val createXlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        val bytes = pendingXlsx
        pendingXlsx = null
        if (uri != null && bytes != null) {
            runCatching { FileExporter.writeTo(context, uri, bytes) }
                .onSuccess { notify(tr("费用 Excel 已导出", "Expense Excel exported"), MessageKind.SUCCESS) }
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
                .onSuccess { notify(tr("原始数据已导出", "Raw data exported"), MessageKind.SUCCESS) }
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

    /**
     * 执行一次固定目录备份，并把结果放进对话框——顶部消息条在部分页面会被内容盖住，用户看不到。
     * [subdir] 非空表示写到授权目录下的 `milenote` 子目录（默认流程）。
     */
    fun runBackupTo(raw: String, subdir: String?, folderLabel: String?) {
        scope.launch {
            busy = true
            try {
                val text = vm.exportBackup()
                val (name, pruned) = BackupFolder.backupNow(
                    context = context,
                    treeUri = Uri.parse(raw),
                    subdir = subdir,
                    keep = settings.backupKeepCount,
                    text = text,
                )
                vm.markBackupDone()
                backupDialog = BackupDialogState(
                    title = tr("备份完成", "Backup complete"),
                    body = if (pruned > 0) {
                        tr("已备份 %s，并清理 %d 份旧备份", "Backed up %s and removed %d old backups", name, pruned)
                    } else {
                        tr(
                            "已备份到「%s」：%s",
                            "Backed up to %s: %s",
                            folderLabel ?: tr("备份文件夹", "backup folder"),
                            name,
                        )
                    },
                    shareText = text,
                )
            } catch (t: BackupFolder.BackupFolderUnavailableException) {
                vm.setBackupFolder(null)
                backupDialog = BackupDialogState(
                    title = tr("备份文件夹不可用", "Backup folder unavailable"),
                    body = tr(
                        "备份文件夹授权已失效，请重新选择",
                        "The backup folder permission is no longer valid — choose it again",
                    ),
                )
            } catch (t: Throwable) {
                backupDialog = BackupDialogState(
                    title = tr("备份失败", "Backup failed"),
                    body = tr(
                        "备份失败：%s",
                        "Backup failed: %s",
                        t.message ?: tr("无法写入备份文件夹", "Could not write to the backup folder"),
                    ),
                )
            } finally {
                busy = false
            }
        }
    }

    val chooseBackupFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            backupAfterPick = false
            return@rememberLauncherForActivityResult
        }
        // 持久授权：重启 / 升级后仍可写；个别 provider 不支持，失败就提示换一个。
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onSuccess {
                // 默认目录 = 所选目录 / milenote：选中的正好叫 milenote 就直接用，否则自动建子目录。
                val pickedName = BackupFolder.displayName(context, uri)
                val subdir = if (pickedName.equals(BackupFolder.DEFAULT_SUBDIR, ignoreCase = true)) {
                    null
                } else {
                    BackupFolder.DEFAULT_SUBDIR
                }
                vm.setBackupFolder(uri.toString(), subdir)
                if (backupAfterPick) {
                    backupAfterPick = false
                    runBackupTo(uri.toString(), subdir, subdir ?: pickedName)
                } else {
                    notify(tr("已选择备份文件夹", "Backup folder selected"), MessageKind.SUCCESS)
                }
            }
            .onFailure {
                backupAfterPick = false
                notify(
                    tr(
                        "这个文件夹不支持长期授权，请换一个（例如「文档」里的子文件夹）",
                        "This folder cannot be kept across restarts — pick another (for example a subfolder of Documents).",
                    ),
                    MessageKind.ERROR,
                )
            }
    }

    fun backupToFolder() {
        val raw = settings.backupFolderUri
        if (raw == null) {
            // 还没选过目录：先弹系统选择器（起始位置「文档」），选完自动创建/复用 milenote 并立即备份。
            backupAfterPick = true
            chooseBackupFolder.launch(defaultBackupPickerUri())
            return
        }
        runBackupTo(raw, settings.backupFolderSubdir, backupFolderName)
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                // 先查大小、再限量读（与导入向导同一套）：不把超大文件整个读进内存再判长度，
                // 上限统一 BACKUP_MAX_BYTES（= LedgerLimits.MAX_PLAIN_BYTES），四个入口同一口径（#12）。
                val text = withContext(Dispatchers.IO) {
                    val limitMessage = tr(
                        "备份文件超过 %d MB，已取消导入",
                        "Backup file is larger than %d MB; import cancelled",
                        (BACKUP_MAX_BYTES / (1024 * 1024)).toInt(),
                    )
                    decodeText(readUriBytes(context, uri, BACKUP_MAX_BYTES, limitMessage))
                }
                // 合并规则与提示由 VM/仓库统一处理；结果除了全局消息，还在本页顶部的
                // 消息区显示一份 —— 设置子页里实测看不到全局 Snackbar（UX3 走查发现）。
                val summary = vm.restoreBackupAwait(text)
                // 还原后自动跑一次体检（老备份可能带进 1899 日期 / 悬空提醒等脏数据）。
                val attention = vm.healthAttentionCount()
                notify(
                    text = if (attention > 0) {
                        restoreSummaryMessage(summary) + tr(" · 体检发现 %d 项", " · data check found %d", attention)
                    } else {
                        restoreSummaryMessage(summary)
                    },
                    kind = MessageKind.SUCCESS,
                    actionLabel = if (attention > 0) tr("去体检", "Check") else null,
                    onAction = if (attention > 0) {
                        { pageName = SettingsPage.HEALTH.name }
                    } else {
                        null
                    },
                )
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

    fun exportXlsxFile() {
        val ledger = activeCar
        if (ledger == null) {
            notify(tr("当前没有可导出的车辆", "No vehicle available to export"), MessageKind.WARNING)
            return
        }
        pendingXlsx = runCatching { LedgerExports.xlsx(ledger) }.getOrElse {
            notify(
                tr("生成 Excel 失败：%s", "Could not build the Excel file: %s", it.message ?: ""),
                MessageKind.ERROR,
            )
            return
        }
        createXlsx.launch(LedgerExports.xlsxFileName())
    }

    /** 导出解析失败时被隔离的原始账本（只用于人工检查/抢救，不能直接还原）。 */
    fun exportRawFile() {
        scope.launch {
            busy = true
            try {
                val bytes = vm.readQuarantinedRaw()
                if (bytes == null) {
                    notify(tr("没有可导出的原始数据", "No raw data available to export"), MessageKind.WARNING)
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
        // UX5：编辑非当前车辆时**不再**顺手切车 —— 点卡片是「编辑」，切车是另一个显式动作
        // （卡片右下角「设为当前」）。保存按 id 写，所以不需要切车也能写对账本。
        val ledger: Ledger? = id?.let { target -> cars.firstOrNull { it.id == target } }
        editing = VehicleEditTarget(
            id = id,
            vehicle = ledger?.vehicle ?: blankVehicle(),
            recordCount = ledger?.records?.size ?: 0,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(page.title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(
                            imageVector = MileNoteIcons.Back,
                            contentDescription = tr("返回", "Back"),
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
                LoadingState(label = tr("正在读取本机数据…", "Loading local data…"))
            }
            return@Scaffold
        }

        // 应用 Scaffold 的 topBar padding，否则列表首项会被顶栏遮住、也无法滚出完整内容。
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // key(page)：切换子页时重置滚动位置，不沿用上一页的 LazyColumn 偏移。
            key(page) {
                when (page) {
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

                        if (cars.size > 1) {
                            item("garage-overview-entry") {
                                SectionCard(
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                ) {
                                    SettingsActionRow(
                                        icon = MileNoteIcons.Car,
                                        title = tr("车库总览", "Garage overview"),
                                        subtitle = tr(
                                            "%d 辆车并列对比支出、里程与能耗（不合并）",
                                            "Compare spend, distance and energy across %d vehicles (never merged)",
                                            cars.size,
                                        ),
                                        onClick = { pageName = SettingsPage.GARAGE.name },
                                    )
                                }
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
                                onAction = { showSetupWizard = true },
                            )
                        }

                        item("vehicles") {
                            if (cars.isEmpty()) {
                                EmptyState(
                                    title = tr("还没有车辆", "No vehicles yet"),
                                    description = tr("添加第一辆车，开始记录每一%s的花费。", "Add your first car and start tracking the cost of every %s.", Units.distanceUnit),
                                    icon = MileNoteIcons.Car,
                                    actionLabel = tr("添加车辆", "Add vehicle"),
                                    onAction = { showSetupWizard = true },
                                )
                            } else {
                                // 竖排：一行一辆、卡片占满内容宽度；间距用 CardGap，比区块间 SectionGap 更紧凑。
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
                                ) {
                                    cars.forEach { car ->
                                        VehicleCard(
                                            ledger = car,
                                            selected = car.id == activeId,
                                            onClick = { openVehicleEditor(car.id) },
                                            // UX5：切车是显式动作，不再和「点卡片编辑」混在一起。
                                            onSelect = if (car.id == activeId) {
                                                null
                                            } else {
                                                { vm.selectVehicle(car.id) }
                                            },
                                        )
                                    }
                                }
                            }
                        }

                    }

                    SettingsPage.UNITS -> SettingsPageList {
                        item("units") {
                            UnitsSection()
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
                        onRenameCategory = { old, new -> vm.renameCategory(old, new) },
                        onHideCategory = { name ->
                            vm.confirm(
                                PendingConfirm(
                                    title = tr("删除费用分类？", "Remove category?"),
                                    message = tr(
                                        "「%s」将从记账与筛选的可选分类中移除，历史记录的分类与金额不受影响；可在「已隐藏（可恢复）」里恢复。",
                                        "“%s” will be removed from the picker. Existing records and amounts are unchanged, and you can restore it from \"Hidden (restorable)\".",
                                        activeCar?.let {
                                            DataLabels.categoryFor(it, name, it.vehicle.energyProfile)
                                        } ?: name,
                                    ),
                                    confirmLabel = tr("删除", "Remove"),
                                    destructive = true,
                                ) { vm.hideCategory(name) },
                            )
                        },
                        onUnhideCategory = { name -> vm.unhideCategory(name) },
                        onResetCategoryLabel = { name -> vm.resetCategoryLabel(name) },
                        onAddProject = { category, name -> vm.addProject(category, name) },
                        onRenameProject = { old, new -> vm.renameServiceProject(old, new) },
                        onHideProject = { category, name ->
                            vm.confirm(
                                PendingConfirm(
                                    title = tr("删除项目？", "Delete item?"),
                                    message = tr(
                                        "「%s」将从记账的项目候选中移除，历史记录里的项目文字不受影响；可在「已隐藏（可恢复）」里恢复。",
                                        "“%s” will be removed from the item picker. Item text in existing records is unchanged, and you can restore it from \"Hidden (restorable)\".",
                                        activeCar?.let { DataLabels.projectFor(it, name) } ?: DataLabels.serviceItem(name),
                                    ),
                                    confirmLabel = tr("删除", "Delete"),
                                    destructive = true,
                                ) { vm.hideServiceProject(category, name) },
                            )
                        },
                        onUnhideProject = { name -> vm.unhideServiceProject(name) },
                        onResetProjectLabel = { name -> vm.resetProjectLabel(name) },
                    )

                    SettingsPage.IMPORT -> ExternalImportPage(
                        // 纯电当前车隐藏「小熊油耗」（只认加油 XLS），其它导入入口保留。
                        ev = activeCar?.isEv == true,
                        demo = state.demo,
                        onOpenImporter = { source -> onOpenImporter(source) },
                    )

                    SettingsPage.HEALTH -> {
                        val ledger = activeCar
                        if (ledger == null) {
                            LoadingState(label = tr("正在读取本机数据…", "Loading local data…"))
                        } else {
                            HealthPage(
                                ledger = ledger,
                                onOpenRecord = { recordId -> vm.openEditor(recordId) },
                                onOpenVehicle = { openVehicleEditor(ledger.id) },
                                onFix = { issue -> vm.fixHealthIssue(issue) },
                                onIgnore = { issue -> vm.ignoreHealthIssue(issue) },
                                onRestoreIgnored = { vm.restoreIgnoredHealthIssues() },
                            )
                        }
                    }

                    SettingsPage.GARAGE -> GarageOverviewPage(
                        cars = cars,
                        activeId = activeId,
                        onSelect = { id ->
                            vm.selectVehicle(id)
                            onOpenDashboard()
                        },
                    )

                    SettingsPage.BACKUP -> BackupRestorePage(
                        carsCount = cars.size,
                        recordsCount = cars.sumOf { it.records.size },
                        lastBackupAt = settings.lastBackupAt,
                        backupFolderName = backupFolderName,
                        backupKeepCount = settings.backupKeepCount,
                        demo = state.demo,
                        loadError = state.loadError,
                        quarantinedRaw = state.quarantinedRaw,
                        replaceSnapshot = state.replaceSnapshot,
                        busy = busy,
                        message = message,
                        onExportBackup = { exportBackupFile() },
                        onChooseBackupFolder = { chooseBackupFolder.launch(null) },
                        onBackupToFolder = { backupToFolder() },
                        onChangeBackupKeep = { vm.setBackupKeepCount(it) },
                        onExportCsv = { exportCsvFile() },
                        onExportXlsx = { exportXlsxFile() },
                        onExportRaw = { exportRawFile() },
                        onRefreshQuarantinedRaw = { vm.refreshQuarantinedRaw() },
                        onRefreshReplaceSnapshot = { vm.refreshReplaceSnapshot() },
                        onRollbackReplaceSnapshot = { vm.rollbackToReplaceSnapshot() },
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
                                onSaveConfig = { url, username, password, allowHttp, encryptionEnabled, encryptionPassword ->
                                    vm.saveWebDavConfig(
                                        url = url,
                                        username = username,
                                        password = password,
                                        allowHttp = allowHttp,
                                        encryptionEnabled = encryptionEnabled,
                                        encryptionPassword = encryptionPassword,
                                    )
                                },
                                onClearConfig = { vm.clearWebDavConfig() },
                                onSyncNow = { vm.webDavSyncNow() },
                                onUpload = { vm.webDavUpload() },
                                onDownloadReplace = { vm.webDavDownload(WebDavDownloadMode.REPLACE) },
                                onResolveConflict = { choice -> vm.resolveWebDavConflict(choice) },
                                onDismissConflict = { vm.dismissWebDavConflict() },
                                onDismissMessage = { vm.dismissWebDavMessage() },
                                onAutoSyncChange = { vm.setWebDavAutoSync(it) },
                                onSubmitDecryptPassword = { vm.submitWebDavDecryptPassword(it) },
                                onCancelDecrypt = { vm.cancelWebDavDecrypt() },
                            )
                        }
                    }

                    SettingsPage.ABOUT -> AboutPage(
                        demo = state.demo,
                        onToggleDemo = {
                            if (state.demo) vm.exitDemo() else vm.enterDemo()
                        },
                        onOpenPrivacy = {
                            cameFromAbout = true
                            pageName = SettingsPage.PRIVACY.name
                        },
                        onOpenLicenses = {
                            cameFromAbout = true
                            pageName = SettingsPage.LICENSES.name
                        },
                    )

                    SettingsPage.PRIVACY -> PrivacyPage()

                    SettingsPage.LICENSES -> LicensesPage()
                }
            }
        }
    }

    if (showSetupWizard) {
        VehicleSetupWizard(
            onDismiss = { showSetupWizard = false },
            onFinish = { vehicle, baseDate, enablePlan ->
                showSetupWizard = false
                vm.addVehicleWithSetup(vehicle, baseDate, enablePlan)
            },
            onUseFullEditor = {
                showSetupWizard = false
                openVehicleEditor(null)
            },
        )
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
                    vm.saveVehicle(
                        vehicle,
                        prunePowertrainReminders = prunePowertrainReminders,
                        vehicleId = target.id,
                    )
                }
            },
            // B35：删除确认已在编辑弹窗内做过，这里直接删，不再弹第二次全局确认。
            onDelete = target.id?.let { id -> { vm.deleteVehicle(id, confirmFirst = false) } },
        )
    }

    backupDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { backupDialog = null },
            title = { Text(dialog.title) },
            text = { Text(dialog.body) },
            confirmButton = {
                TextButton(onClick = { backupDialog = null }) { Text(tr("关闭", "Close")) }
            },
            dismissButton = dialog.shareText?.let { text ->
                {
                    TextButton(
                        onClick = {
                            backupDialog = null
                            shareBackup(text)
                        },
                    ) { Text(tr("分享", "Share")) }
                }
            },
        )
    }
}

/** 目录选择器的起始位置：优先「文档」（默认备份目录 Documents/milenote 在它下面）。 */
private fun defaultBackupPickerUri(): Uri? = runCatching {
    android.provider.DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Documents",
    )
}.getOrNull()

/** 设置子页统一的滚动容器：16/24dp 页边距、内容 max 840dp、底部 96dp 预留。 */
@Composable
internal fun SettingsPageList(
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
internal fun SettingsMenuRow(
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
internal fun SettingsMessageArea(
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
                message = tr("演示模式 · 修改不会保存到真实数据", "Demo mode · Changes are not saved to your real data"),
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
    /** 非当前车时显示的「设为当前」；当前车传 null。 */
    onSelect: (() -> Unit)? = null,
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
        modifier = Modifier.fillMaxWidth(),
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

            // 底部一行固定预留触控目标高度：当前车只有「当前」徽标（~20dp），
            // 其他车有 48dp 的「设为当前」按钮，不预留会让未选中卡片比选中卡片高一截。
            Row(
                modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
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
                } else if (onSelect != null) {
                    TextButton(
                        onClick = onSelect,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                    ) {
                        Text(
                            text = tr("设为当前", "Set as current"),
                            style = MaterialTheme.typography.labelMedium,
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
 * 单位页（1.0 锁死版）。
 *
 * 1.0 之前「单位」只换显示标签、不换算数值：英文界面会把人民币显示成 `$`，切英里会把
 * 42,000 km 标成 42,000 mi。与其保留一组看起来像错的选项，不如 1.0 固定为原始录入单位，
 * 等真正做了汇率 / 单位换算再放开（见 decisions 2026-11-15）。
 */
@Composable
private fun UnitsSection() {
    val scheme = MaterialTheme.colorScheme
    SectionCard(title = tr("显示单位", "Display units")) {
        Text(
            text = tr(
                "当前版本按原始录入单位显示：元 / 公里 / 升。",
                "This version always displays the original units: CNY / km / L.",
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = tr(
                "不做汇率或单位换算：账本里的金额与里程始终是录入时的数值。自动换算会在后续版本评估，" +
                    "在此之前不提供切换，避免「$ 显示人民币」这类误解。",
                "No currency or unit conversion is applied — amounts and distances stay exactly as entered. " +
                    "Automatic conversion will be evaluated in a later release; until then the switch is hidden " +
                    "so CNY amounts are never shown as USD or kilometers as miles.",
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
    onRenameCategory: (old: String, new: String) -> Unit,
    onHideCategory: (String) -> Unit,
    onUnhideCategory: (String) -> Unit,
    onResetCategoryLabel: (String) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRenameProject: (old: String, new: String) -> Unit,
    onHideProject: (category: String, name: String) -> Unit,
    onUnhideProject: (String) -> Unit,
    onResetProjectLabel: (String) -> Unit,
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
                onRenameCategory = onRenameCategory,
                onHideCategory = onHideCategory,
                onUnhideCategory = onUnhideCategory,
                onResetCategoryLabel = onResetCategoryLabel,
                onAddProject = onAddProject,
                onRenameProject = onRenameProject,
                onHideProject = onHideProject,
                onUnhideProject = onUnhideProject,
                onResetProjectLabel = onResetProjectLabel,
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
    demo: Boolean,
    onOpenImporter: (String) -> Unit,
) {
    val unavailable = tr("演示模式下不可用", "Unavailable in demo mode")
    SettingsPageList {
        item("sources") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            ) {
                // 里程志 CSV 回灌：先导出费用 CSV，改完再导入；同「记录编号」原位更新。
                SettingsMenuRow(
                    icon = MileNoteIcons.Records,
                    title = tr("里程志 CSV（回灌）", "MileNote CSV (write-back)"),
                    subtitle = if (demo) {
                        unavailable
                    } else {
                        tr(
                            "导出 → 修改 → 再导入，同编号记录原位更新",
                            "Export → edit → import; same-ID records update in place",
                        )
                    },
                    onClick = if (demo) null else ({ onOpenImporter("milenote") }),
                )
                SectionDivider()
                // 小熊油耗只有加油记录（XLS），纯电车辆不适用；钱迹等入口保持不变。
                if (!ev) {
                    SettingsMenuRow(
                        icon = MileNoteIcons.CategoryFuel,
                        title = tr("小熊油耗", "Bear"),
                        subtitle = if (demo) unavailable else tr("XLS · 导入加油记录", "XLS · Import fuel records"),
                        onClick = if (demo) null else ({ onOpenImporter("bear") }),
                    )
                    SectionDivider()
                }
                SettingsMenuRow(
                    icon = MileNoteIcons.Wallet,
                    title = tr("钱迹记账", "Qianji"),
                    subtitle = if (demo) unavailable else tr("XLSX / JSON · 导入支出记录", "XLSX / JSON · Import expense records"),
                    onClick = if (demo) null else ({ onOpenImporter("qianji") }),
                )

            }
        }

        item("note") {
            Text(
                text = tr(
                    "导入的记录会标记来源；重复记录按现有合并规则处理（合并或跳过）；" +
                        "里程志 CSV 带「记录编号」，改完再导入会原位更新原记录。表格文件上限 10 MB，" +
                        "只在本机解析，不会上传。",
                    "Imported records keep their source label; duplicates follow the usual merge rules " +
                        "(merge or skip). MileNote CSVs carry a record ID, so editing and re-importing updates " +
                        "the original record in place. Spreadsheets are limited to 10 MB and are parsed on " +
                        "this device only — nothing is uploaded.",
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
    lastBackupAt: Long?,
    backupFolderName: String?,
    backupKeepCount: Int,
    demo: Boolean,
    loadError: String?,
    quarantinedRaw: QuarantinedRaw?,
    replaceSnapshot: QuarantinedRaw?,
    busy: Boolean,
    message: SettingsMessage?,
    onExportBackup: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onBackupToFolder: () -> Unit,
    onChangeBackupKeep: (Int) -> Unit,
    onExportCsv: () -> Unit,
    onExportXlsx: () -> Unit,
    onExportRaw: () -> Unit,
    onRefreshQuarantinedRaw: suspend () -> Unit,
    onRefreshReplaceSnapshot: suspend () -> Unit,
    onRollbackReplaceSnapshot: () -> Unit,
    onImportBackup: () -> Unit,
    onDeleteAll: () -> Unit,
    onExitDemo: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    // 距上次备份 ≥ 阈值时在页内提醒（纯展示，不引入通知 / 后台任务）。
    val overdueDays = remember(lastBackupAt) { backupOverdueDays(lastBackupAt) }
    // 每次进入本页都重新检查：被隔离的损坏账本、以及可回滚的「覆盖前快照」。
    LaunchedEffect(Unit) {
        onRefreshQuarantinedRaw()
        onRefreshReplaceSnapshot()
    }
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
        if (demo) {
            item("demo-note") {
                MessageBar(
                    message = tr(
                        "演示模式下不能导出完整备份、还原或删除数据；请先退出演示。",
                        "Full backup export, restore and delete are disabled in demo mode — exit the demo first.",
                    ),
                    kind = MessageKind.INFO,
                )
            }
        }

        item("status") {
            SectionCard(title = tr("数据概览", "Data overview")) {
                Text(
                    text = tr("%d 辆车 · %d 条记录", "%d vehicles · %d records", carsCount, recordsCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = lastBackupAt?.let { tr("上次备份：%s", "Last backup: %s", formatBackupAt(it)) }
                        ?: tr("尚未导出备份", "No backup exported yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (overdueDays != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MessageBar(
                        message = tr(
                            "已有 %d 天没有完整备份（JSON）。本机是唯一副本，建议现在导出一次；" +
                                "CSV / Excel 是报表导出，不能还原。",
                            "No full backup (JSON) for %d days. This device holds the only copy — export one now; " +
                                "CSV / Excel are reports and cannot be restored.",
                            overdueDays,
                        ),
                        kind = MessageKind.WARNING,
                    )
                }
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

        item("folder") {
            SectionCard(
                title = tr("固定目录备份", "Folder backup"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Folder,
                    title = tr("备份文件夹", "Backup folder"),
                    subtitle = when {
                        demo -> tr("演示模式下不可用", "Unavailable in demo mode")
                        backupFolderName != null -> backupFolderName
                        else -> tr(
                            "未设置 · 默认「文档/milenote」，首次备份时确认一次",
                            "Not set — defaults to Documents/milenote after one confirmation",
                        )
                    },
                    enabled = !busy && !demo,
                    onClick = onChooseBackupFolder,
                )
                SectionDivider()
                SettingsActionRow(
                    icon = MileNoteIcons.Export,
                    title = tr("立即备份到此处", "Back up to this folder now"),
                    subtitle = if (backupFolderName == null) {
                        tr(
                            "首次点击会打开目录选择器，自动建好「文档/milenote」",
                            "First tap opens the folder picker and creates Documents/milenote",
                        )
                    } else {
                        tr(
                            "写入 %s · 保留最近 %d 份",
                            "Writes %s · keeps the latest %d",
                            LedgerExports.backupFileName(),
                            backupKeepCount,
                        )
                    },
                    enabled = !busy && !demo,
                    onClick = onBackupToFolder,
                )
                SectionDivider()
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        text = tr("保留份数", "Keep"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SegmentedChoiceRow(
                        options = BACKUP_FILE_KEEP_OPTIONS.map { it to it.toString() },
                        selected = backupKeepCount,
                        onSelect = onChangeBackupKeep,
                    )
                }
                Text(
                    text = tr(
                        "只清理本应用写出的备份文件；同一文件夹里的其他文件不会被删除。" +
                            "Android 11+ 不能选择存储根目录或「下载」根目录。",
                        "Backups go into a milenote subfolder of the selected folder (or the folder itself if it is already named milenote). " +
                            "Only files written by this app are cleaned up; other files are left untouched. " +
                            "On Android 11+ the storage root and the Download root cannot be selected.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
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
                    subtitle = if (demo) {
                        tr("演示模式下不可用", "Unavailable in demo mode")
                    } else {
                        tr("全部车辆与记录，可跨设备还原", "Everything, restorable on another device")
                    },
                    enabled = !busy && !demo,
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
                        title = tr("导出原始数据（损坏文件）", "Export raw data (corrupted file)"),
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
                SectionDivider()
                SettingsActionRow(
                    icon = MileNoteIcons.Chart,
                    title = tr("导出费用 Excel（.xlsx）", "Export expense Excel (.xlsx)"),
                    subtitle = tr(
                        "两张表：费用明细 + 分类汇总，金额/里程可直接求和",
                        "Two sheets: expense details and category summary, with numeric cells",
                    ),
                    enabled = !busy,
                    onClick = onExportXlsx,
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
                    subtitle = if (demo) {
                        tr("演示模式下不可用", "Unavailable in demo mode")
                    } else {
                        tr(
                            "只合并新增，不覆盖现有数据（上限 %d MB）",
                            "Merges new records only; never overwrites (max %d MB)",
                            (BACKUP_MAX_BYTES / (1024 * 1024)).toInt(),
                        )
                    },
                    enabled = !busy && !demo,
                    onClick = onImportBackup,
                )
                if (replaceSnapshot != null) {
                    SectionDivider()
                    SettingsActionRow(
                        icon = MileNoteIcons.Refresh,
                        title = tr("回滚到上次覆盖前", "Roll back to before the last overwrite"),
                        subtitle = tr(
                            "快照：%s（WebDAV 下载、还原或删除前的本机数据）",
                            "Snapshot: %s (local data before a WebDAV download, restore or delete)",
                            formatBackupAt(replaceSnapshot.savedAtMillis),
                        ),
                        enabled = !busy && !demo,
                        onClick = onRollbackReplaceSnapshot,
                    )
                }
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
                    subtitle = if (demo) {
                        tr("演示模式下不可用", "Unavailable in demo mode")
                    } else {
                        tr("清空全部数据，需输入「删除」确认", "Wipes all data; type “删除” to confirm")
                    },
                    danger = true,
                    enabled = !busy && !demo,
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
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
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
                    message = tr("是否前往 GitHub 下载最新 APK？", "Open GitHub to download the latest APK?"),
                    link = result.info.apkUrl ?: result.info.pageUrl,
                    notes = result.info.notes.ifBlank { null },
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
                        "本地优先的多车用车小助手：记油耗、管保养、算清用车开销；" +
                            "数据只保存在本机，无账号、无后端。",
                        "A local-first companion for your car: log fuel and running costs, compute real fuel " +
                            "economy with the full-tank method, and keep service plans on schedule. " +
                            "Your data stays on this device — no account, no backend.",
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
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Lock,
                    title = tr("隐私与数据", "Privacy & data"),
                    subtitle = tr(
                        "数据、权限与联网",
                        "Data, permissions, network",
                    ),
                    onClick = onOpenPrivacy,
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Info,
                    title = tr("开源许可", "Open-source licenses"),
                    subtitle = tr("本应用与第三方许可", "App and third-party licenses"),
                    onClick = onOpenLicenses,
                )
            }
        }

        item("demo") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsActionRow(
                    icon = MileNoteIcons.Sparkle,
                    title = if (demo) tr("退出演示模式", "Exit demo mode") else tr("试用演示模式", "Try demo mode"),
                    subtitle = if (demo) {
                        tr("内置样例，修改不保存", "Built-in samples; changes are not saved")
                    } else {
                        tr("内置样例体验，不写入真实数据", "Try built-in samples; nothing is written")
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
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Release 正文按 Markdown 渲染；内容超过弹层高度时在内部滚动。
                    dialog.notes?.let { notes ->
                        MarkdownText(markdown = notes)
                    }
                    Text(
                        text = dialog.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
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
// 隐私与数据
// ---------------------------------------------------------------------------------------------

/**
 * 隐私与数据页：把「数据存在哪、要哪些权限、会连哪些服务」讲清楚。
 *
 * 不是正式法律文本（正式条款与源码见项目仓库）；文案与
 * `work/docs/legal/privacy-policy.md`、官网「隐私与数据」屏保持同一口径。
 * 注意：不要出现「完全离线 / 绝不上传」这类绝对表述——检查更新、油价、WebDAV
 * 都会联网，只在用户主动触发或对应功能开启时发生。
 */
@Composable
private fun PrivacyPage() {
    val context = LocalContext.current

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsPageList {
        item("local") {
            SectionCard(title = tr("数据存在哪里", "Where your data lives")) {
                PrivacyParagraph(
                    tr(
                        "数据只保存在这台设备的应用私有目录（单文件 JSON，车辆图片内嵌其中）。没有账号体系，" +
                            "开发者没有服务器，也不会收到你的数据、记录或图片。",
                        "Your data is stored only in this app's private storage on this device — a single JSON " +
                            "file with vehicle images embedded. There are no accounts, no developer server, and " +
                            "your data, records or photos are never sent to the developer.",
                    ),
                )
                PrivacyParagraph(
                    tr(
                        "卸载应用会删除本机数据；换手机、清缓存前请先在「备份与还原」导出完整备份。",
                        "Uninstalling removes the data on this device. Before switching phones or clearing " +
                            "storage, export a full backup in Backup & restore.",
                    ),
                    secondary = true,
                )
            }
        }

        item("permissions") {
            SectionCard(
                title = tr("应用权限（共 2 项）", "Permissions (2 total)"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                SettingsMenuRow(
                    icon = MileNoteIcons.Globe,
                    title = tr("网络", "Network"),
                    subtitle = tr(
                        "检查更新、今日油价、你自己配置的 WebDAV 同步",
                        "Update check, live fuel prices, and the WebDAV sync you configure",
                    ),
                    onClick = {},
                    showChevron = false,
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Location,
                    title = tr("粗略定位", "Approximate location"),
                    subtitle = tr(
                        "仅用于把坐标反查成城市名以取城市油价；坐标不保存、不会上传；拒绝授权时回退省级价",
                        "Only used to resolve a city for local fuel prices. Coordinates are not stored or " +
                            "uploaded; without permission the app falls back to province-level prices.",
                    ),
                    onClick = {},
                    showChevron = false,
                )
                SectionDivider()
                Text(
                    text = tr(
                        "不申请相机、相册、通讯录、电话等权限；识别小票使用系统照片选择器（Photo Picker）。",
                        "The app does not request camera, photo-library, contacts or phone permissions; " +
                            "receipt scanning uses the system photo picker.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        item("network") {
            SectionCard(title = tr("会联网的场景", "When the app connects")) {
                PrivacyParagraph(
                    tr(
                        "以下联网只在你主动触发、或对应功能开启时发生，且都不包含数据内容：",
                        "The app connects only when you trigger it or enable the related feature. None of these " +
                            "requests include your data:",
                    ),
                    secondary = true,
                )
                PrivacyBullet(
                    tr(
                        "检查更新：点「检查更新」时才请求 GitHub（api.github.com），只读取版本信息，请求里不含任何数据内容。",
                        "Update check: only when you tap Check for updates; it reads version info from GitHub " +
                            "(api.github.com) and the request contains no data.",
                    ),
                )
                PrivacyBullet(
                    tr(
                        "今日油价：中文界面且车辆有油箱时，按省 / 市名查询公开油价数据集；纯电车辆与英文界面不请求。",
                        "Fuel prices: in Chinese, for vehicles with a fuel tank, the app queries public price " +
                            "datasets by province or city name. EVs and the English UI make no such request.",
                    ),
                )
                PrivacyBullet(
                    tr(
                        "WebDAV 同步：只有你自己填写服务器地址和密码后才启用；远端默认是明文 JSON，可在设置里开启 AES-256-GCM 加密上传。",
                        "WebDAV sync: enabled only after you enter your own server and password. The remote " +
                            "file is plain JSON by default; encrypted (AES-256-GCM) upload can be turned on in " +
                            "Settings.",
                    ),
                )
                PrivacyBullet(
                    tr(
                        "车辆图片链接：只有你手动填写图片 URL 时才加载该链接。",
                        "Vehicle image URL: loaded only if you type an image URL yourself.",
                    ),
                )
                PrivacyParagraph(
                    tr(
                        "以上请求均为 HTTPS（release 版禁止明文 HTTP，例外仅限本机调试地址）。",
                        "All of the above use HTTPS; release builds block cleartext HTTP except for local " +
                            "debug addresses.",
                    ),
                    secondary = true,
                )
            }
        }

        item("on-device") {
            SectionCard(title = tr("端侧处理", "Processed on device")) {
                PrivacyParagraph(
                    tr(
                        "小票识别（Tesseract 引擎）与文件解析（XLS / XLSX / JSON / CSV）都在本机完成。" +
                            "识别用的图片只在内存中处理，识别结束即释放，不落盘、不上传、不写入数据。",
                        "Receipt recognition (Tesseract) and file parsing (XLS / XLSX / JSON / CSV) run entirely " +
                            "on this device. Images used for recognition are processed in memory, released " +
                            "afterwards, never written to disk, uploaded or stored in the data.",
                    ),
                )
            }
        }

        item("tracking") {
            SectionCard(title = tr("广告与统计", "Ads and analytics")) {
                PrivacyParagraph(
                    tr(
                        "无广告、无统计埋点 SDK、无崩溃上报 SDK、无账号体系；备份与导出文件由你自行保管。",
                        "There are no ads, analytics SDKs, crash-reporting SDKs or accounts. Backup and export " +
                            "files are yours to keep.",
                    ),
                )
            }
        }

        item("control") {
            SectionCard(title = tr("你的控制权", "Your controls")) {
                PrivacyBullet(
                    tr(
                        "随时导出：完整备份 JSON、费用 CSV / Excel；导出文件不经过开发者。",
                        "Export anytime: full JSON backup, expense CSV or Excel. Exported files never pass " +
                            "through the developer.",
                    ),
                )
                PrivacyBullet(
                    tr(
                        "随时删除：设置 → 备份与还原 → 删除全部数据；WebDAV 远端文件需你在自己的服务器上删除。",
                        "Delete anytime: Settings → Backup & restore → Delete all data. Remote WebDAV files " +
                            "must be deleted on your own server.",
                    ),
                )
                PrivacyBullet(
                    tr(
                        "WebDAV 加密密码只保存在本机、无法找回；忘记后无法解密远端数据。",
                        "The WebDAV encryption password stays on this device and cannot be recovered; if you " +
                            "forget it, the remote data cannot be decrypted.",
                    ),
                )
            }
        }

        item("note") {
            SectionCard(title = tr("说明", "Notes")) {
                PrivacyParagraph(
                    tr(
                        "本页是应用内的隐私说明，不是正式法律文本。完整条款与源码见项目仓库。",
                        "This page is an in-app explanation, not a formal legal document. " +
                            "Full terms and source code are available in the project repository.",
                    ),
                    secondary = true,
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Code,
                    title = tr("问题与建议", "Questions or feedback"),
                    subtitle = "github.com/Selwiro/MileNoteAndroid/issues",
                    onClick = { openLink("https://github.com/Selwiro/MileNoteAndroid/issues") },
                    showChevron = false,
                )
            }
        }
    }
}

@Composable
private fun PrivacyParagraph(text: String, secondary: Boolean = false) {
    Text(
        text = text,
        style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.size(8.dp))
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(modifier = Modifier.size(8.dp))
}

// ---------------------------------------------------------------------------------------------
// 开源许可
// ---------------------------------------------------------------------------------------------

/**
 * 开源许可页：本应用与随 APK 分发的第三方组件。
 *
 * 许可证全文与组件清单内嵌在 `assets/licenses/`（离线可读，不请求网络）；
 * Tesseract4Android / tessdata 模型为 Apache-2.0；Android SDK 条款链接单独列出。
 */
@Composable
private fun LicensesPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<LicenseDialogState?>(null) }

    fun openAsset(title: String, asset: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { readLicenseAsset(context, asset) }
            dialog = LicenseDialogState(
                title = title,
                text = text ?: tr("许可证文本读取失败，请重试", "Could not load the license text — please try again"),
            )
        }
    }

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsPageList {
        item("app") {
            SectionCard {
                Text(tr("本应用", "This app"), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = tr(
                        "「里程志 MileNote」的源代码以 Apache License 2.0 授权：你可以自由使用、修改、" +
                            "自行构建并再分发（包括免费再分发），只需保留版权与许可声明。",
                        "MileNote's source code is licensed under the Apache License 2.0: you may use, modify, " +
                            "build and redistribute it freely, including for free, as long as you keep the " +
                            "copyright and license notices.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(4.dp))
                SettingsMenuRow(
                    icon = MileNoteIcons.FileText,
                    title = "Apache License 2.0",
                    subtitle = tr("查看许可证全文", "View the full license text"),
                    onClick = { openAsset("Apache License 2.0", "licenses/apache-2.0.txt") },
                )
            }
        }

        item("components") {
            SectionCard(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = tr("第三方组件", "Third-party components"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                SettingsMenuRow(
                    icon = MileNoteIcons.Code,
                    title = tr("组件清单", "Component list"),
                    subtitle = tr("随 APK 分发的运行期依赖，按许可证分组", "Runtime dependencies in the APK, by license"),
                    onClick = { openAsset(tr("第三方组件清单", "Third-party components"), "licenses/third-party-notices.txt") },
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.FileText,
                    title = "BSD 3-Clause License",
                    subtitle = "Protocol Buffers · AndroidX DataStore",
                    onClick = { openAsset("BSD 3-Clause License", "licenses/bsd-3-clause.txt") },
                )
                SectionDivider()
                SettingsMenuRow(
                    icon = MileNoteIcons.Globe,
                    title = tr("Android SDK 许可协议", "Android SDK License"),
                    subtitle = "developer.android.com/studio/terms",
                    onClick = { openLink("https://developer.android.com/studio/terms") },
                    showChevron = false,
                )
            }
        }
    }

    dialog?.let { state ->
        LicenseTextDialog(state = state, onDismiss = { dialog = null })
    }
}

/** 读取 `assets/licenses/` 下的文本；读不到时返回 `null`，由调用方给兜底文案。 */
private fun readLicenseAsset(context: android.content.Context, asset: String): String? =
    runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull()

/** 许可证全文弹层：接近全屏，文本可长按选择复制。 */
@Composable
private fun LicenseTextDialog(state: LicenseDialogState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = MileNoteIcons.Close, contentDescription = tr("关闭", "Close"))
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
internal fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
