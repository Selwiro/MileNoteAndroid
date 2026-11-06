@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.milenote.android.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车辆编辑全屏对话框（新增 / 编辑同一套表单）。
 *
 * 字段与 Web 版「管理车辆 → 车辆信息」一致：昵称*、车牌号、品牌/车型、当前总里程、
 * 记账起始里程、提车日期（历史字段名 `companionDate`，用于「陪伴天数」）、车辆图片与显示方式。
 *
 * 图片走 Photo Picker（无需存储权限）→ [VehicleImageCodec] 压缩成 WebP dataURL；
 * 也支持填 http(s) 图片链接（显示失败时回退默认插画）。保存交给调用方：
 * 新增走 `vm.addVehicle(vehicle)`、编辑走 `vm.saveVehicle(vehicle)`。
 *
 * @param initial 初始车辆（新增时传 `blankVehicle()`）。
 * @param title 顶栏标题（「添加车辆」/「编辑车辆」）。
 * @param recordCount 该车已有记录数（删除提示用）。
 * @param onDismiss 关闭弹层。
 * @param onSave 校验通过后回传车辆对象。
 * @param onDelete 删除该车（VM 内部会再弹一次确认）；新增时为 null。
 */
@Composable
internal fun VehicleEditorDialog(
    initial: Vehicle,
    title: String,
    recordCount: Int,
    onDismiss: () -> Unit,
    onSave: (Vehicle) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(initial) { mutableStateOf(initial.name) }
    var plate by remember(initial) { mutableStateOf(initial.plate) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var fuelGrade by remember(initial) { mutableStateOf(initial.fuelGrade ?: "92#") }
    var currentKmText by remember(initial) {
        mutableStateOf(if (initial.currentKm > 0.0) Format.fmt(initial.currentKm) else "")
    }
    var startKmText by remember(initial) { mutableStateOf(initial.startKm?.let { Format.fmt(it) } ?: "") }
    var companionDate by remember(initial) {
        mutableStateOf(initial.companionDate?.takeIf { Dates.isValid(it) })
    }
    var image by remember(initial) { mutableStateOf(initial.image) }
    var imageFit by remember(initial) {
        mutableStateOf(if (initial.imageFit == "cover") "cover" else "contain")
    }
    var imageBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    fun notifyError(text: String) {
        hint = null
        error = text
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            imageBusy = true
            val result = runCatching {
                withContext(Dispatchers.IO) { VehicleImageCodec.toDataUrl(context.contentResolver, uri) }
            }
            imageBusy = false
            result
                .onSuccess {
                    image = it
                    hint = "车辆图片已更新（已压缩为本机 WebP）"
                    error = null
                }
                .onFailure { notifyError("图片处理失败：${it.message ?: "请换一张图片"}") }
        }
    }

    fun save() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            notifyError("请填写车辆昵称")
            return
        }
        val km = currentKmText.trim().replace(",", "").replace("，", "").toDoubleOrNull()
        if (currentKmText.isNotBlank() && (km == null || km < 0.0 || !km.isFinite())) {
            notifyError("当前总里程需为非负数字")
            return
        }
        val startKm = startKmText.trim().replace(",", "").replace("，", "").toDoubleOrNull()
        if (startKmText.isNotBlank() && (startKm == null || startKm < 0.0 || !startKm.isFinite())) {
            notifyError("记账起始里程需为非负数字")
            return
        }
        if (km != null && startKm != null && startKm > km) {
            notifyError("记账起始里程不能大于当前总里程")
            return
        }
        val next = initial.copy(
            name = trimmedName,
            plate = plate.trim(),
            model = model.trim(),
            fuelGrade = fuelGrade,
            currentKm = km ?: initial.currentKm,
            startKm = startKm,
            companionDate = companionDate,
            image = image,
            imageFit = imageFit,
        )
        onSave(next)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            // 全屏对话框是独立窗口，自己的 inset 自己处理（状态栏 / 导航栏 / 键盘）。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MileNoteDimens.TopBarHeight)
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(MileNoteDimens.TouchTarget)) {
                        Icon(
                            imageVector = MileNoteIcons.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { save() },
                        modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MileNoteDimens.PageHorizontal,
                        end = MileNoteDimens.PageHorizontal,
                        top = 16.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    hint?.let { text ->
                        item("hint") {
                            MessageBar(
                                message = text,
                                kind = MessageKind.SUCCESS,
                                onDismiss = { hint = null },
                            )
                        }
                    }
                    error?.let { text ->
                        item("error") {
                            MessageBar(
                                message = text,
                                kind = MessageKind.ERROR,
                                onDismiss = { error = null },
                            )
                        }
                    }

                    item("image") {
                        ImageEditor(
                            image = image,
                            imageFit = imageFit,
                            imageBusy = imageBusy,
                            onPick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onUrl = { showUrlDialog = true },
                            onReset = {
                                image = null
                                hint = "已恢复默认车辆插画"
                                error = null
                            },
                        )
                    }

                    item("name") {
                        FormField(
                            value = name,
                            onValueChange = { name = it },
                            label = "车辆昵称 *",
                            placeholder = "例如：小蓝",
                            isError = error != null && name.isBlank(),
                            supportingText = if (name.length >= 50) "最多 50 个字符" else null,
                        )
                    }
                    item("plate") {
                        FormField(
                            value = plate,
                            onValueChange = { plate = it.take(20) },
                            label = "车牌号（选填）",
                            placeholder = "例如：沪A·12345",
                        )
                    }
                    item("model") {
                        FormField(
                            value = model,
                            onValueChange = { model = it.take(80) },
                            label = "品牌 / 车型（选填）",
                            placeholder = "例如：比亚迪 海豚",
                        )
                    }
                    item("fuelGrade") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "燃油标号",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("92#", "95#", "98#", "0#").forEach { grade ->
                                    FilterChip(
                                        selected = fuelGrade == grade,
                                        onClick = { fuelGrade = grade },
                                        label = { Text(if (grade == "0#") "0# 柴油" else grade) },
                                    )
                                }
                            }
                            Text(
                                text = "用于「今日油价」卡显示的油品；选择柴油显示 0# 价格。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item("currentKm") {
                        FormField(
                            value = currentKmText,
                            onValueChange = { currentKmText = it },
                            label = "当前总里程",
                            suffix = "公里",
                            keyboardType = KeyboardType.Decimal,
                            supportingText = "保存后作为当前里程；记录中的更大里程仍会被采用",
                        )
                    }
                    item("startKm") {
                        FormField(
                            value = startKmText,
                            onValueChange = { startKmText = it },
                            label = "记账起始里程（选填）",
                            suffix = "公里",
                            keyboardType = KeyboardType.Decimal,
                            supportingText = "对应记账起点，用于平均每公里成本；留空时取最早记录里程",
                        )
                    }
                    item("date") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DateField(
                                label = "提车日期（选填）",
                                value = companionDate,
                                onClick = { showDatePicker = true },
                                onClear = { companionDate = null },
                            )
                            Text(
                                text = "用于总览的「爱车陪伴天数」，起始日计为第 1 天；留空则不显示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (image != null) {
                        item("fit") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("图片显示方式", style = MaterialTheme.typography.titleSmall)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    listOf("contain" to "完整展示", "cover" to "铺满画框")
                                        .forEachIndexed { index, (value, label) ->
                                            SegmentedButton(
                                                selected = imageFit == value,
                                                onClick = { imageFit = value },
                                                shape = SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = 2,
                                                ),
                                                label = {
                                                    Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        maxLines = 1,
                                                    )
                                                },
                                            )
                                        }
                                }
                                Text(
                                    text = if (imageFit == "cover") {
                                        "铺满画框：图片裁切填满车辆位，适合照片。"
                                    } else {
                                        "完整展示：保留整张图片，适合透明底或抠图车辆图。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item("actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { save() },
                                shape = MileNoteShapeTokens.full,
                                modifier = Modifier.fillMaxWidth().height(MileNoteDimens.ButtonMinHeight),
                            ) {
                                Text(if (onDelete == null) "添加车辆" else "保存车辆信息")
                            }
                            if (onDelete != null) {
                                OutlinedButton(
                                    onClick = {
                                        onDelete.invoke()
                                        onDismiss()
                                    },
                                    shape = MileNoteShapeTokens.full,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(MileNoteDimens.ButtonMinHeight),
                                ) {
                                    Icon(
                                        imageVector = MileNoteIcons.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "删除这辆车",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Text(
                                    text = "将删除该车的 ${recordCount} 条费用、提醒与保养记录；建议先导出完整备份。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUrlDialog) {
        ImageUrlDialog(
            current = image,
            onConfirm = { url ->
                image = url
                hint = "已使用图片链接"
                error = null
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    if (showDatePicker) {
        val initialMillis = Dates.parse(companionDate)?.let { it.toEpochDay() * 86_400_000L }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            companionDate = Dates.iso(LocalDate.ofEpochDay(millis / 86_400_000L))
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 图片编辑区：预览 + 选择图片 / 图片链接 / 恢复默认。 */
@Composable
private fun ImageEditor(
    image: String?,
    imageFit: String,
    imageBusy: Boolean,
    onPick: () -> Unit,
    onUrl: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MileNoteShapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            VehicleImage(
                image = image,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (imageFit == "cover") ContentScale.Crop else ContentScale.Fit,
            )
            if (imageBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.36f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onPick,
                shape = MileNoteShapeTokens.full,
                enabled = !imageBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Camera,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("选择图片", maxLines = 1)
            }
            OutlinedButton(
                onClick = onUrl,
                shape = MileNoteShapeTokens.full,
                modifier = Modifier.weight(1f),
            ) {
                Text("图片链接", maxLines = 1)
            }
        }
        if (image != null) {
            TextButton(onClick = onReset) { Text("恢复默认插画") }
        }
        Text(
            text = "支持 PNG / JPG / WebP。图片在本机压缩到 1000×700 以内并以 WebP 存入账本，不上传服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 图片链接输入弹窗（http/https）。 */
@Composable
private fun ImageUrlDialog(
    current: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(
            if (isRemoteVehicleImage(current) && current != null) current else "",
        )
    }
    var urlError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = { Text("填写图片链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        urlError = null
                    },
                    label = { Text("图片地址") },
                    placeholder = { Text("https://example.com/car.png") },
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = if (urlError != null) {
                        { Text(urlError.orEmpty()) }
                    } else {
                        null
                    },
                    shape = MileNoteShapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "链接需可公开访问；加载失败时自动显示默认插画，不影响账本数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = text.trim()
                    if (!isRemoteVehicleImage(trimmed)) {
                        urlError = "请输入以 http:// 或 https:// 开头的地址"
                    } else {
                        onConfirm(trimmed)
                    }
                },
                shape = MileNoteShapeTokens.full,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 提车日期行：点击打开 DatePicker，右侧可清除。 */
@Composable
private fun DateField(
    label: String,
    value: String?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MileNoteShapes.medium,
        color = Color.Transparent,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = value?.let { Format.dateDots(it) } ?: "未填写",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value == null) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
            if (value != null) {
                IconButton(onClick = onClear, modifier = Modifier.size(MileNoteDimens.TouchTarget)) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = "清除提车日期",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(MileNoteDimens.TouchTarget))
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MileNoteIcons.Calendar,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
