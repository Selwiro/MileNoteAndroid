package com.milenote.android.ui.screens.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Dates
import com.milenote.android.ui.EditorTarget
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.screens.ocr.OcrCaptureScreen
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.Instant
import java.time.ZoneOffset

/**
 * 「记一笔 / 记录加油 / 编辑记录」全屏独立页面（原底部弹层，M2 核心表单）。
 *
 * 由导航层在 `state.editor != null` 时打开，返回 / 保存 / 删除后关闭：
 * ```kotlin
 * state.editor?.let { target ->
 *     RecordEditorScreen(vm = vm, target = target, onDismiss = { vm.closeEditor() })
 * }
 * ```
 *
 * 结构与交互：
 * - 顶部栏：返回箭头（contentDescription「返回」）+ 标题；系统返回键/手势也走 [onDismiss]。
 * - 可滚动表单：金额 → 日期（M3 DatePicker）→ 分类 chip 网格 → 总里程 → 加油/服务/保险分组 → 备注；
 * - 固定底部操作栏：编辑态「删除」+「取消」+「保存记录」，`navigationBarsPadding` 保证不被手势条遮挡；
 * - 保存前用 domain `validateRecord` 校验，错误就地显示（金额下方 + 顶部提示条）；
 * - 油费「实付金额」尾部的相机按钮打开全屏 [OcrCaptureScreen]，识别结果按 Web 保守规则只回填空字段；
 * - 新增自定义分类立即写回账本（[MileNoteViewModel.addCustomCategory]），记录本身也带该分类名。
 *
 * 页面内自带 `statusBarsPadding()` / `imePadding()`；若导航层用 Scaffold `innerPadding`
 * 再包一层，需要去掉其中一侧的 inset 避免双重留白。
 *
 * @param vm 全局 ViewModel（保存 / 删除记录、持久化自定义分类）。
 * @param target 打开目标（既有记录 / 预选分类 / 关联保养提醒）。
 * @param onDismiss 关闭页面（返回箭头 / 系统返回 / 保存删除成功后调用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditorScreen(
    vm: MileNoteViewModel,
    target: EditorTarget,
    onDismiss: () -> Unit,
) {
    val uiState by vm.state.collectAsStateWithLifecycle()
    val ledger = uiState.activeCar
    if (ledger == null) {
        // 账本不存在（理论上不会发生），安全关闭，避免页面卡死。
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val form = remember(ledger.id, target) { RecordEditorState(ledger = ledger, target = target) }
    var showOcr by remember { mutableStateOf(false) }

    // 系统返回键 / 预测式返回手势：关闭页面（未保存内容不做二次确认，与 Web 关闭弹窗一致）。
    BackHandler(onBack = onDismiss)

    RecordEditorBody(
        form = form,
        onDismiss = onDismiss,
        onSave = {
            val record = form.attemptSave()
            if (record != null) {
                vm.saveRecord(record)
                onDismiss()
            }
        },
        onDelete = {
            form.original?.let(vm::deleteRecord)
            onDismiss()
        },
        onOcrClick = { showOcr = true },
    )

    if (form.showDatePicker) {
        EditorDatePickerDialog(
            initialDate = form.date,
            onDismiss = { form.showDatePicker = false },
            onPick = {
                form.date = it
                form.dateTouched = true
            },
        )
    }
    if (form.showCategoryDialog) {
        AddCategoryDialog(
            onDismiss = { form.showCategoryDialog = false },
            onConfirm = { name ->
                form.addDraftCategory(name)?.let(vm::addCustomCategory)
                form.showCategoryDialog = false
            },
        )
    }
    if (showOcr) {
        // 全屏 OCR：CameraX 预览 + 相册兜底 + ML Kit 识别，图片只在内存中处理。
        Dialog(
            onDismissRequest = { showOcr = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                decorFitsSystemWindows = false,
            ),
        ) {
            OcrCaptureScreen(
                onResult = { receipt ->
                    form.applyOcr(receipt)
                    showOcr = false
                },
                onClose = { showOcr = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 兼容旧调用点的过渡别名（MileNoteApp 尚未切到全屏路由时保持构建绿）。
 *
 * TODO(lead)：导航接好 `RecordEditorScreen` 后删除。
 */
@Deprecated("请改用 RecordEditorScreen(vm, target, onDismiss)。")
@Composable
fun RecordEditorSheet(
    vm: MileNoteViewModel,
    target: EditorTarget,
    onDismiss: () -> Unit,
) {
    RecordEditorScreen(vm = vm, target = target, onDismiss = onDismiss)
}

/** 全屏页面主体：顶部栏 + 可滚动字段区 + 固定底部操作栏。 */
@Composable
private fun RecordEditorBody(
    form: RecordEditorState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onOcrClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            EditorHeader(title = form.title, onBack = onDismiss)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val error = form.currentError()
                if (error != null) {
                    MessageBar(message = error, kind = MessageKind.ERROR)
                }
                if (!form.isManual) {
                    CategoryField(form = form)
                }
                AmountField(
                    form = form,
                    onOcrClick = onOcrClick,
                )
                DateField(form = form)
                OdometerField(form = form)
                if (form.isFuel) {
                    FuelFields(form = form)
                }
                if (form.isManual) {
                    ManualFuelFields(form = form)
                }
                if (form.isInsurance) {
                    InsuranceFields(form = form)
                }
                if (form.isService) {
                    ServiceFields(form = form)
                }
                NoteField(form = form)
                form.ocrNotice?.let { notice ->
                    MessageBar(
                        message = notice,
                        kind = MessageKind.INFO,
                        onDismiss = { form.ocrNotice = null },
                    )
                }
                form.ocrWarnings.forEach { warning ->
                    MessageBar(message = warning, kind = MessageKind.WARNING)
                }
                if (form.isService) {
                    Text(
                        text = "维修保养费用会计入用车成本；所选项目按最近保养日期和里程更新下次期限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            EditorActionBar(
                isEditing = form.original != null,
                onDelete = onDelete,
                onCancel = onDismiss,
                onSave = onSave,
            )
        }
    }
}

/** 顶部栏：返回箭头 + 标题（56dp 高，底部 1dp 分隔线）。 */
@Composable
private fun EditorHeader(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MileNoteIcons.Back,
                    contentDescription = "返回",
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            // 右侧占位，保证标题左对齐时与返回按钮视觉平衡。
            Spacer(modifier = Modifier.size(48.dp))
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 底部固定操作栏：删除（编辑态）+ 取消 + 保存；含手势条 inset。 */
@Composable
private fun EditorActionBar(
    isEditing: Boolean,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isEditing) {
                TextButton(onClick = onDelete) {
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onSave,
                shape = MileNoteShapeTokens.full,
                modifier = Modifier.height(48.dp),
            ) {
                Text("保存记录", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 日期选择：material3 DatePicker（日期语义始终是 `YYYY-MM-DD`，无时刻）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDatePickerDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val initialMillis = remember(initialDate) {
        Dates.parse(initialDate)
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onPick(Dates.iso(picked))
                    }
                    onDismiss()
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
        DatePicker(state = pickerState)
    }
}

/** 新增费用分类：单字段 AlertDialog（与 Web `category-dialog` 一致）。 */
@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = {
            Text("新增费用分类", style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "新增分类会保存到本车账本，之后记账可直接选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(40)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分类名称（最多 40 字）") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let {
                        { Text(it, style = MaterialTheme.typography.bodySmall) }
                    },
                    shape = MileNoteShapes.medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        error = "请输入分类名称"
                    } else {
                        onConfirm(trimmed)
                    }
                },
                shape = CircleShape,
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
