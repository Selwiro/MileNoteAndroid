package com.milenote.android.ui.screens.importer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.import.ImportItem
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.EmptyState
import com.milenote.android.ui.components.ListRowCard
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.SectionHeader
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens

/**
 * 导入向导（二级页）：三步完成从文件到账本的导入。
 *
 * 1. 选择来源：小熊油耗 XLS-XLSX / 钱迹 TXT-JSON-CSV；
 * 2. 读取与预览：SAF 选文件（≤10 MB）→ 字段映射 → 逐行预览 / 排除 →
 *    油费冲突选择（默认小熊）→ 钱迹默认分类；
 * 3. 确认：调用 `vm.applyImport` / `vm.restoreBackup`，展示结果。
 *
 * 账本备份 JSON 不在手动来源列表里：只在系统分享 JSON 进来时自动识别并恢复。
 * 只写 UI；解析、合并、校验全部复用 `domain` 纯函数与 [MileNoteViewModel]。
 *
 * @param vm 主 ViewModel（读取 `state.activeCar`、写入导入结果）。
 * @param initialSource 可选初始来源（`bear` / `qianji` / `backup` 及别名），传入后自动打开文件选择器。
 * @param onClose 关闭/完成后回调（由导航层 `popBackStack`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWizardScreen(
    vm: MileNoteViewModel,
    initialSource: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val ledger = ui.activeCar
    // 不用 rememberCoroutineScope：系统文件选择器返回后，remember 的 scope 可能已离开 composition，
    // 再 launch 会抛 “The coroutine scope left the composition”。这里用页面自持 scope，
    // 只在页面真正 dispose 时取消，文件读取仍由 LaunchedEffect 驱动。
    val wizardScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(Unit) {
        onDispose { wizardScope.cancel() }
    }
    val wizard = remember(vm, wizardScope, context) { ImportWizardState(vm, wizardScope, context) }

    // 关闭/完成时一并清理分享状态，避免下次进入向导重复自动导入。
    val closeWizard: () -> Unit = {
        vm.consumeSharedImport()
        onClose()
    }

    // 系统分享（ACTION_SEND）由 Lead 写入 UiState 并导航到本页；读取完立即消费，避免重复触发。
    val shared = ui.sharedImport
    LaunchedEffect(shared) {
        if (shared != null) {
            wizard.loadShared(shared.bytes, shared.fileName, shared.mimeType)
            vm.consumeSharedImport()
        }
    }

    // 冷启动分享：账本加载完成后刷新一次预览（重复/Fuel 冲突依赖现有记录）。
    LaunchedEffect(ledger?.records?.size, ledger?.customCategories?.size) {
        wizard.refreshPreviewFromLedger()
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingUri = uri
    }

    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        // 不要在 load 之前清空 pendingUri：key 变化会取消当前 LaunchedEffect，
        // 导致 load 内的 withContext 抛 LeftCompositionCancellationException。
        // 读取/解析完成（或失败）后再清空。
        wizard.load(uri)
        pendingUri = null
    }

    val initial = remember(initialSource) { ImportSource.fromKey(initialSource) }
    var autoLaunched by remember { mutableStateOf(false) }
    LaunchedEffect(initial) {
        if (initial != null && !autoLaunched && ui.sharedImport == null) {
            autoLaunched = true
            wizard.selectSource(initial)
            runCatching { picker.launch(arrayOf("*/*")) }
                .onFailure {
                    wizard.updateError(
                    tr(
                        "无法打开文件选择器：%s",
                        "Could not open the file picker: %s",
                        it.message ?: tr("请点来源卡片重试", "tap a source card to try again"),
                    ),
                )
                }
        }
    }

    BackHandler {
        wizard.goBack(closeWizard)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = tr("导入数据", "Import data"), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = { wizard.goBack(closeWizard) }) {
                    Icon(imageVector = MileNoteIcons.Back, contentDescription = tr("返回", "Back"))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )
        WizardStepBar(currentStep = wizard.step)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f)) {
            when (wizard.step) {
                0 -> SourceStep(
                    wizard = wizard,
                    onPick = { picker.launch(arrayOf("*/*")) },
                )

                1 -> PreviewStep(
                    wizard = wizard,
                    ledger = ledger,
                    onPick = { picker.launch(arrayOf("*/*")) },
                )

                else -> ResultStep(wizard = wizard)
            }
        }
        WizardBottomBar(wizard = wizard, onClose = closeWizard)
    }
}

// ---------------------------------------------------------------------------------------------
// 步骤条 / 底部按钮
// ---------------------------------------------------------------------------------------------

@Composable
private fun WizardStepBar(currentStep: Int) {
    val stepLabels = listOf(
        tr("选择来源", "Choose source"),
        tr("读取与预览", "Read & preview"),
        tr("完成", "Done"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stepLabels.forEachIndexed { index, label ->
            val done = index < currentStep
            val active = index == currentStep
            val circleColor = when {
                active || done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val contentColor = when {
                active || done -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(color = circleColor, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            imageVector = MileNoteIcons.Check,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                        )
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (index < stepLabels.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(2.dp)
                        .background(
                            color = if (index < currentStep) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun WizardBottomBar(wizard: ImportWizardState, onClose: () -> Unit) {
    var showDuplicatePrompt by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (wizard.step) {
                    0 -> OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(tr("取消", "Cancel"))
                    }

                    1 -> {
                        OutlinedButton(
                            onClick = { wizard.goBack(onClose) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = MileNoteDimens.ButtonMinHeight),
                            enabled = !wizard.busy,
                        ) {
                            Text(tr("上一步", "Back"))
                        }
                        Button(
                            onClick = {
                                if (wizard.source != ImportSource.BACKUP &&
                                    wizard.duplicateTableCount() > 0
                                ) {
                                    showDuplicatePrompt = true
                                } else {
                                    wizard.confirm()
                                }
                            },
                            modifier = Modifier
                                .weight(2f)
                                .heightIn(min = MileNoteDimens.ButtonMinHeight),
                            enabled = wizard.canConfirm(),
                        ) {
                            Text(if (wizard.busy) tr("处理中…", "Working…") else wizard.confirmLabel())
                        }
                    }

                    else -> Button(
                        onClick = onClose,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(tr("完成", "Done"))
                    }
                }
            }
        }
    }

    if (showDuplicatePrompt) {
        AlertDialog(
            onDismissRequest = { showDuplicatePrompt = false },
            title = { Text(tr("发现重复记录", "Duplicate records found")) },
            text = {
                Text(
                    tr(
                        "有 %d 条记录与现有账本重复：忽略会跳过这些行，覆盖会用导入数据替换原记录。",
                        "%d records duplicate the current ledger: ignoring skips them, overwriting " +
                            "replaces the original records with the imported data.",
                        wizard.duplicateTableCount(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDuplicatePrompt = false
                        wizard.confirm(overwriteDuplicates = true)
                    },
                ) {
                    Text(tr("覆盖原记录", "Overwrite"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDuplicatePrompt = false
                        wizard.confirm(overwriteDuplicates = false)
                    },
                ) {
                    Text(tr("忽略重复", "Ignore duplicates"))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 步骤 1：选择来源
// ---------------------------------------------------------------------------------------------

@Composable
private fun SourceStep(wizard: ImportWizardState, onPick: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = MileNoteDimens.PageBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = tr("选择导入来源", "Choose import source"),
                subtitle = tr(
                    "文件只在本机解析，不会上传；表格文件 ≤10 MB，备份 ≤20 MB",
                    "Files are parsed on this device and never uploaded; spreadsheets ≤10 MB, " +
                        "backups ≤20 MB",
                ),
            )
        }
        if (wizard.fromShare) {
            item {
                SharedImportBanner(
                    fileName = wizard.sharedFileName,
                    sourceTitle = wizard.source?.title,
                    onChooseAnotherSource = { wizard.chooseAnotherSource() },
                )
            }
        }
        wizard.error?.let { message ->
            item {
                MessageBar(
                    message = message,
                    kind = MessageKind.ERROR,
                    actionLabel = if (wizard.picked != null) tr("重试", "Retry") else null,
                    onAction = if (wizard.picked != null) ({ wizard.retry() }) else null,
                    onDismiss = { wizard.updateError(null) },
                )
            }
        }
        if (wizard.busy) {
            item { LoadingState(label = wizard.busyMessage ?: tr("正在处理…", "Working…")) }
        }
        MANUAL_IMPORT_SOURCES.forEach { source ->
            item(key = source.key) {
                SourceCard(
                    source = source,
                    onClick = {
                        wizard.selectSource(source)
                        onPick()
                    },
                )
            }
        }
        item {
            Text(
                text = tr(
                    "小熊油耗：XLS / XLSX；钱迹：TXT / JSON / CSV；账本备份 JSON（version 2 多车或旧版单车）" +
                        "仅在系统分享导入时自动识别。",
                    "Bear: XLS / XLSX; Qianji: TXT / JSON / CSV. Ledger backup JSON (version 2 multi-car " +
                        "or legacy single-car) is only auto-detected when imported via system share.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(source: ImportSource, onClick: () -> Unit) {
    ListRowCard(
        title = source.title,
        subtitle = source.description,
        leading = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = source.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        trailing = {
            Icon(
                imageVector = MileNoteIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}

private fun ImportSource.icon(): ImageVector = when (this) {
    ImportSource.BACKUP -> MileNoteIcons.Import
    ImportSource.BEAR -> MileNoteIcons.CategoryFuel
    ImportSource.QIANJI -> MileNoteIcons.Wallet
}

/** 系统分享进入时的顶部提示：文件名 + 识别来源 + 重新选择来源。 */
@Composable
private fun SharedImportBanner(
    fileName: String,
    sourceTitle: String?,
    onChooseAnotherSource: () -> Unit,
) {
    val shownFileName = fileName.ifBlank { tr("分享导入", "Shared import") }
    val detected = if (sourceTitle != null) {
        tr("（已识别为 %s）", " (detected as %s)", sourceTitle)
    } else {
        ""
    }
    MessageBar(
        message = tr("来自分享：%s", "From share: %s", shownFileName) + detected,
        kind = MessageKind.INFO,
        actionLabel = tr("重新选择来源", "Choose another source"),
        onAction = onChooseAnotherSource,
    )
}

// ---------------------------------------------------------------------------------------------
// 步骤 2：读取与预览
// ---------------------------------------------------------------------------------------------

@Composable
private fun PreviewStep(
    wizard: ImportWizardState,
    ledger: com.milenote.android.domain.model.Ledger?,
    onPick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = MileNoteDimens.PageBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        wizard.error?.let { message ->
            item {
                MessageBar(
                    message = message,
                    kind = MessageKind.ERROR,
                    actionLabel = if (wizard.picked != null) tr("重试", "Retry") else null,
                    onAction = if (wizard.picked != null) ({ wizard.retry() }) else null,
                    onDismiss = { wizard.updateError(null) },
                )
            }
        }
        if (wizard.fromShare) {
            item {
                SharedImportBanner(
                    fileName = wizard.sharedFileName,
                    sourceTitle = wizard.source?.title,
                    onChooseAnotherSource = { wizard.chooseAnotherSource() },
                )
            }
        }
        item { FileSummaryCard(wizard = wizard, onPick = onPick) }

        when (wizard.source) {
            ImportSource.BACKUP -> {
                item { BackupSummaryCard(wizard = wizard) }
            }

            ImportSource.BEAR, ImportSource.QIANJI -> {
                item { TableOptionsCard(wizard = wizard, ledger = ledger) }
                item {
                    MappingCard(
                        wizard = wizard,
                        fields = IMPORT_FIELD_ORDER,
                        labels = importFieldLabels(),
                        requiredHint = tr("日期与实付金额是导入的必填字段。", "Date and amount paid are required."),
                    )
                }
                item { TableStatsCard(wizard = wizard) }
                val shown = minOf(wizard.tableItems.size, PREVIEW_ROW_LIMIT)
                items(shown) { index ->
                    TablePreviewRow(wizard = wizard, index = index)
                }
                if (wizard.tableItems.size > shown) {
                    item { PreviewLimitNote(total = wizard.tableItems.size, shown = shown) }
                }
            }

            null -> {
                item {
                    EmptyState(
                        title = tr("还没有选择来源", "No source selected"),
                        description = tr("返回上一步选择要导入的文件类型。", "Go back and choose the file type to import."),
                        icon = MileNoteIcons.Import,
                    )
                }
            }
        }

        if (wizard.busy) {
            item { LoadingState(label = wizard.busyMessage ?: tr("正在处理…", "Working…")) }
        }
    }
}

@Composable
private fun FileSummaryCard(wizard: ImportWizardState, onPick: () -> Unit) {
    val file = wizard.picked
    SectionCard(
        title = tr("已选文件", "Selected file"),
        action = {
            TextButton(onClick = onPick) {
                Text(tr("重新选择", "Choose another"))
            }
        },
    ) {
        Text(
            text = file?.name ?: "—",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (file != null) {
                tr("大小 %s", "Size %s", displaySize(file.sizeBytes))
            } else {
                tr("尚未选择文件", "No file selected")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        wizard.sheetData?.let { data ->
            Text(
                text = tr(
                    "表头 %d 列 · 数据 %d 行",
                    "%d header columns · %d data rows",
                    data.headers.size,
                    data.rows.size,
                ) + if (data.activeSheet.isNotBlank()) {
                    tr(" · 工作表 %s", " · Sheet %s", data.activeSheet)
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (wizard.sheetNames.size > 1) {
            Spacer(modifier = Modifier.height(12.dp))
            WizardDropdown(
                label = tr("工作表", "Worksheet"),
                selected = wizard.sheetData?.activeSheet.orEmpty(),
                options = wizard.sheetNames.map { it to it },
                onSelect = { name -> if (name != null) wizard.switchSheet(name) },
            )
        }
    }
}

@Composable
private fun BackupSummaryCard(wizard: ImportWizardState) {
    SectionCard(
        title = tr("备份摘要", "Backup summary"),
        subtitle = tr("确认后在现有账本上合并", "Merged into the current ledger after confirmation"),
    ) {
        val preview = wizard.backupPreview
        if (preview == null) {
            LoadingState(label = tr("正在解析备份…", "Parsing backup…"))
        } else {
            InfoRow(tr("备份版本", "Backup version"), tr("version %d", "version %d", preview.version))
            InfoRow(tr("车辆", "Vehicles"), tr("%d 辆", "%d vehicles", preview.cars))
            InfoRow(tr("费用记录", "Expense records"), tr("%d 条", "%d records", preview.records))
            InfoRow(tr("提醒", "Reminders"), tr("%d 条", "%d reminders", preview.reminders))
            Spacer(modifier = Modifier.height(8.dp))
            MessageBar(
                message = tr(
                    "按车辆编号合并：已有记录保留、新增记录追加；旧版单车备份会合并到当前车辆。",
                    "Merged by vehicle ID: existing records are kept and new ones appended; legacy " +
                        "single-car backups merge into the current vehicle.",
                ),
                kind = MessageKind.INFO,
            )
        }
    }
}

@Composable
private fun TableOptionsCard(
    wizard: ImportWizardState,
    ledger: com.milenote.android.domain.model.Ledger?,
) {
    SectionCard(title = tr("导入选项", "Import options")) {
        if (wizard.source == ImportSource.QIANJI) {
            WizardDropdown(
                label = tr("无法识别的分类统一归入", "Group unrecognized categories as"),
                selected = wizard.defaultCategory,
                options = defaultCategoryOptions(ledger),
                onSelect = { value -> wizard.changeDefaultCategory(value ?: "") },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        val conflict = wizard.fuelConflict
        if (conflict == null) {
            Text(
                text = tr(
                    "本次未检测到跨来源油费冲突，油费默认按小熊实付计入成本。",
                    "No cross-source fuel conflict detected; fuel costs use Bear's amount paid by default.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MessageBar(
                message = tr(
                    "两个来源都含油费，请选择统计方式（只影响成本统计，原始记录全部保留）。",
                    "Both sources contain fuel expenses. Choose how to count costs (this only affects " +
                        "cost analytics; all original records are kept).",
                ),
                kind = MessageKind.WARNING,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tr(
                    "小熊实付 ¥%s · 钱迹 ¥%s",
                    "Bear paid ¥%s · Qianji ¥%s",
                    Format.money(conflict.bear),
                    Format.money(conflict.qianji),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            FuelBasisOption("bear", tr("以小熊实付为准（默认）", "Use Bear's amount paid (default)"), wizard)
            FuelBasisOption("qianji", tr("以钱迹金额为准", "Use Qianji's amount"), wizard)
            FuelBasisOption("both", tr("两个来源都计入", "Count both sources"), wizard)
        }
    }
}

@Composable
private fun FuelBasisOption(value: String, label: String, wizard: ImportWizardState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = wizard.fuelBasis == value,
            onClick = { wizard.changeFuelBasis(value) },
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MappingCard(
    wizard: ImportWizardState,
    fields: List<String>,
    labels: Map<String, String>,
    requiredHint: String,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        title = tr("字段匹配", "Field mapping"),
        subtitle = tr("空选项表示该字段不导入", "An empty choice means the field is not imported"),
        action = {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) tr("收起", "Collapse") else tr("调整字段匹配", "Edit field mapping"))
            }
        },
    ) {
        if (!expanded) {
            val mapped = fields.count { wizard.mapping[it] != null }
            Text(
                text = tr(
                    "已匹配 %d / %d 个字段；%s",
                    "%d of %d fields mapped; %s",
                    mapped,
                    fields.size,
                    requiredHint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val headers = wizard.sheetData?.headers.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fields.forEach { field ->
                    MappingDropdown(
                        label = labels[field] ?: field,
                        headers = headers,
                        selectedIndex = wizard.mapping[field],
                        onSelect = { index -> wizard.setMapping(field, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TableStatsCard(wizard: ImportWizardState) {
    SectionCard(
        title = tr("预览", "Preview"),
        subtitle = tr("共 %d 行", "%d rows", wizard.tableItems.size),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = tr("有效 %d", "%d valid", wizard.validTableCount()),
                status = StatusKind.DONE,
            )
            if (wizard.invalidTableCount() > 0) {
                StatusPill(
                    text = tr("错误 %d", "%d errors", wizard.invalidTableCount()),
                    status = StatusKind.OVERDUE,
                )
            }
            if (wizard.excludedTableCount() > 0) {
                StatusPill(
                    text = tr("已排除 %d", "%d excluded", wizard.excludedTableCount()),
                    status = StatusKind.NORMAL,
                )
            }
            if (wizard.duplicateTableCount() > 0) {
                StatusPill(
                    text = tr("重复 %d", "%d duplicates", wizard.duplicateTableCount()),
                    status = StatusKind.SOON,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = tr(
                "勾选要导入的行；带错误的行不可导入。「有效」包含重复行，确认时可选择忽略或覆盖；" +
                    "确认后的结果以「导入完成」提示为准；仅显示前 %d 行。",
                "Tick the rows to import; rows with errors can't be imported. \"Valid\" includes " +
                    "duplicates — ignore or overwrite them on confirmation. The final result follows " +
                    "the \"import submitted\" summary; only the first %d rows are shown.",
                PREVIEW_ROW_LIMIT,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (wizard.tableItems.isNotEmpty() && wizard.validTableCount() == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            MessageBar(
                message = tr(
                    "没有可导入的行：请检查「日期 / 实付金额」字段匹配；钱迹可在「导入选项」设置默认分类，" +
                        "或在「调整字段匹配」里把分类指向「二级分类」。",
                    "No importable rows: check the Date / Amount paid mapping. For Qianji, set a default " +
                        "category under Import options, or map Category to Secondary category in Edit " +
                        "field mapping.",
                ),
                kind = MessageKind.WARNING,
            )
        }
    }
}

@Composable
private fun TablePreviewRow(wizard: ImportWizardState, index: Int) {
    val item: ImportItem = wizard.tableItems.getOrNull(index) ?: return
    val hasError = item.error.isNotEmpty()
    val duplicate = index in wizard.duplicates
    val canSelect = !hasError
    val checked = canSelect && !item.exclude
    val note = when {
        hasError -> item.error
        duplicate -> tr("与现有账本重复，确认时选择忽略或覆盖", "Duplicate of the current ledger — ignore or overwrite on confirmation")
        !item.record.note.isNullOrBlank() -> item.record.note.orEmpty()
        else -> tr("可导入", "Ready to import")
    }
    ListRowCard(
        title = tr(
            "%s · %s",
            "%s · %s",
            item.record.date.ifBlank { tr("日期无效", "Invalid date") },
            DataLabels.category(
                item.record.category.ifBlank {
                    item.rawCategory.ifBlank { tr("未知分类", "Unknown category") }
                },
            ),
        ),
        subtitle = tr("第 %d 行 · %s", "Row %d · %s", item.row, importSourceLabel(item.record.source)) +
            (item.record.odometer?.let { tr(" · %s 公里", " · %s km", Format.fmt(it)) } ?: ""),
        supporting = note,
        leading = {
            if (hasError) {
                Icon(
                    imageVector = MileNoteIcons.Warning,
                    contentDescription = tr("错误", "Error"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { value -> wizard.setExcluded(index, !value) },
                )
            }
        },
        trailing = {
            if (hasError) {
                StatusPill(text = tr("错误", "Error"), status = StatusKind.OVERDUE)
            } else {
                MoneyText(
                    amount = Format.money(item.record.amount),
                    size = MoneySize.SMALL,
                    unit = tr("元", "CNY"),
                )
            }
        },
        containerColor = if (hasError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    )
}

// ---------------------------------------------------------------------------------------------
// 步骤 3：完成
// ---------------------------------------------------------------------------------------------

@Composable
private fun ResultStep(wizard: ImportWizardState) {
    val result = wizard.result
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = MileNoteDimens.PageBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        wizard.error?.let { message ->
            item {
                MessageBar(
                    message = message,
                    kind = MessageKind.ERROR,
                    onDismiss = { wizard.updateError(null) },
                )
            }
        }
        item {
            SectionCard {
                if (result == null) {
                    Text(text = tr("没有可展示的导入结果", "No import result to show"), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(text = result.title, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    result.lines.forEach { line ->
                        InfoRow("", line)
                    }
                    if (result.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            MessageBar(
                message = tr(
                    "导入已提交到账本。若数量与预览不同，以费用记录 / 保养计划表为准。",
                    "Import submitted to the ledger. If counts differ from the preview, trust the " +
                        "records and service plan.",
                ),
                kind = MessageKind.INFO,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------------------------

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PreviewLimitNote(total: Int, shown: Int) {
    Text(
        text = tr(
            "仅显示前 %d 行，共 %d 行；确认后全部有效行都会导入。",
            "Showing the first %d of %d rows; all valid rows are imported once confirmed.",
            shown,
            total,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/** 通用字符串下拉（工作表 / 默认分类）。[onSelect] 传 null 表示空值。 */
@Composable
private fun WizardDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = options.firstOrNull { it.first == selected }?.second
                        ?: selected.ifBlank { tr("不导入", "Do not import") },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text = text) },
                        onClick = {
                            expanded = false
                            onSelect(value.ifBlank { null })
                        },
                    )
                }
            }
        }
    }
}

/** 字段映射下拉：选择表头列下标，`null` 表示不导入。 */
@Composable
private fun MappingDropdown(
    label: String,
    headers: List<String>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selectedIndex?.let { headerLabel(headers, it) } ?: tr("不导入", "Do not import")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(tr("不导入", "Do not import")) },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
                headers.forEachIndexed { index, _ ->
                    DropdownMenuItem(
                        text = { Text(headerLabel(headers, index)) },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}
