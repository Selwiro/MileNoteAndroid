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
import androidx.compose.material3.Checkbox
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
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_FUEL
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.normalizePowertrain
import com.milenote.android.domain.model.powertrainKey
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
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
 * 字段与 Web 版「管理车辆 → 车辆信息」一致：昵称*、车牌号、品牌/车型、动力类型（燃油 / 纯电）、
 * 「燃油标号」（仅燃油车显示；纯电隐藏，切回燃油时保留原选值）、当前总里程、记账起始里程、
 * 提车日期（历史字段名 `companionDate`，用于「陪伴天数」）、车辆图片与显示方式。
 *
 * 动力类型写入 `Vehicle.powertrain`（`"fuel"` / `"ev"`）；纯电车保存时清空 `fuelCostSource`
 * （避免旧版小熊/钱迹口径把电费判成 0），燃油车保持原值。若动力类型相对 [initial] 改变且
 * 该车已有记录（[recordCount] > 0），保存前弹确认框：历史记录保留原单位、能耗分析只统计
 * 当前动力类型；可勾选「一并删除不再适用的保养提醒」（默认勾选，已完成提醒与记录不删）。
 *
 * 图片走 Photo Picker（无需存储权限）→ [VehicleImageCodec] 压缩成 WebP dataURL；
 * 也支持填 http(s) 图片链接（显示失败时回退默认插画）。保存交给调用方：
 * 新增走 `vm.addVehicle(vehicle)`、编辑走 `vm.saveVehicle(vehicle, prunePowertrainReminders)`。
 *
 * @param initial 初始车辆（新增时传 `blankVehicle()`）。
 * @param title 顶栏标题（「添加车辆」/「编辑车辆」）。
 * @param recordCount 该车已有记录数（删除提示 / 动力类型切换确认用）。
 * @param onDismiss 关闭弹层。
 * @param onSave 校验通过后回传车辆对象与「是否一并删除不再适用的保养提醒」。
 * @param onDelete 删除该车（VM 内部会再弹一次确认）；新增时为 null。
 */
@Composable
internal fun VehicleEditorDialog(
    initial: Vehicle,
    title: String,
    recordCount: Int,
    onDismiss: () -> Unit,
    onSave: (Vehicle, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(initial) { mutableStateOf(initial.name) }
    var plate by remember(initial) { mutableStateOf(initial.plate) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    // 动力类型：缺省/null 视为燃油；只在本对话框内切换，确认保存后才写回账本。
    var powertrainKey by remember(initial) { mutableStateOf(initial.powertrainKey) }
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
    // 动力类型改变且有历史记录时的待确认保存；非空即显示确认框。
    var pendingPowertrainSave by remember { mutableStateOf<Vehicle?>(null) }
    var prunePowertrainReminders by remember { mutableStateOf(true) }

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
                    hint = tr("车辆图片已更新（已压缩为本机 WebP）", "Vehicle image updated (compressed to WebP on this device)")
                    error = null
                }
                .onFailure { notifyError(tr("图片处理失败：%s", "Image processing failed: %s", it.message ?: tr("请换一张图片", "Please try another image"))) }
        }
    }

    fun save() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            notifyError(tr("请填写车辆昵称", "Please enter a vehicle name"))
            return
        }
        val km = currentKmText.trim().replace(",", "").replace("，", "").toDoubleOrNull()
        if (currentKmText.isNotBlank() && (km == null || km < 0.0 || !km.isFinite())) {
            notifyError(tr("当前总里程需为非负数字", "Current mileage must be a non-negative number"))
            return
        }
        val startKm = startKmText.trim().replace(",", "").replace("，", "").toDoubleOrNull()
        if (startKmText.isNotBlank() && (startKm == null || startKm < 0.0 || !startKm.isFinite())) {
            notifyError(tr("记账起始里程需为非负数字", "Start mileage must be a non-negative number"))
            return
        }
        if (km != null && startKm != null && startKm > km) {
            notifyError(tr("记账起始里程不能大于当前总里程", "Start mileage cannot be greater than current mileage"))
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
            powertrain = normalizePowertrain(powertrainKey),
            // 插混 / 纯电不保留旧版油费成本口径（否则钱迹来源的加油/充电记录会被判成 0）；燃油保持原值。
            fuelCostSource = if (powertrainKey != POWERTRAIN_FUEL) null else initial.fuelCostSource,
        )
        if (powertrainKey != initial.powertrainKey && recordCount > 0) {
            prunePowertrainReminders = true
            pendingPowertrainSave = next
        } else {
            onSave(next, false)
        }
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
                            contentDescription = tr("关闭", "Close"),
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
                        Text(tr("保存", "Save"), style = MaterialTheme.typography.labelLarge)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MileNoteDimens.PageHorizontal,
                        end = MileNoteDimens.PageHorizontal,
                        top = 16.dp,
                        bottom = MileNoteDimens.PageBottom,
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
                                hint = tr("已恢复默认车辆插画", "Default vehicle illustration restored")
                                error = null
                            },
                        )
                    }

                    item("name") {
                        FormField(
                            value = name,
                            onValueChange = { name = it.take(50) },
                            label = tr("车辆昵称 *", "Vehicle name *"),
                            placeholder = tr("例如：小蓝", "e.g. Blue"),
                            isError = error != null && name.isBlank(),
                            supportingText = if (name.length >= 50) tr("最多 50 个字符", "At most 50 characters") else null,
                        )
                    }
                    item("plate") {
                        FormField(
                            value = plate,
                            onValueChange = { plate = it.take(20) },
                            label = tr("车牌号（选填）", "Plate (optional)"),
                            placeholder = tr("例如：沪A·12345", "e.g. AB·12345"),
                        )
                    }
                    item("model") {
                        FormField(
                            value = model,
                            onValueChange = { model = it.take(80) },
                            label = tr("品牌 / 车型（选填）", "Make / model (optional)"),
                            placeholder = tr("例如：比亚迪 海豚", "e.g. BYD Dolphin"),
                        )
                    }
                    item("powertrain") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = tr("动力类型", "Powertrain"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                listOf(
                                    POWERTRAIN_FUEL to tr("燃油", "Fuel"),
                                    POWERTRAIN_PHEV to tr("插电混动", "Plug-in hybrid"),
                                    POWERTRAIN_EV to tr("纯电", "EV"),
                                ).forEachIndexed { index, (key, label) ->
                                    SegmentedButton(
                                        selected = powertrainKey == key,
                                        onClick = { powertrainKey = key },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = 3,
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
                                text = tr(
                                    "插电混动同时记加油与充电，能耗分油耗 / 电耗两个口径；纯电只用充电量记账。保养方案与能耗分析随动力类型切换。",
                                    "Plug-in hybrids record both refuelling and charging (fuel and energy economy are reported separately); EVs only record charging. The service plan and energy analysis follow the powertrain.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 「燃油标号」对燃油与插混有意义；纯电隐藏但保留选值，切回燃油/插混时原样显示。
                    if (powertrainKey != POWERTRAIN_EV) {
                        item("fuelGrade") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = tr("燃油标号", "Fuel grade"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("92#", "95#", "98#", "0#").forEach { grade ->
                                        FilterChip(
                                            selected = fuelGrade == grade,
                                            onClick = { fuelGrade = grade },
                                            label = { Text(if (grade == "0#") tr("0# 柴油", "0# Diesel") else grade) },
                                        )
                                    }
                                }
                                Text(
                                    text = tr(
                                        "用于「今日油价」卡显示的油品；选择柴油显示 0# 价格。纯电车辆不显示此项。",
                                        "Fuel type shown on the today's fuel price card; choosing diesel shows the 0# price. Hidden for EVs.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item("currentKm") {
                        FormField(
                            value = currentKmText,
                            onValueChange = { currentKmText = it },
                            label = tr("当前总里程", "Current mileage"),
                            suffix = Units.distanceUnit,
                            keyboardType = KeyboardType.Decimal,
                            supportingText = tr(
                                "保存后作为当前里程；记录中的更大里程仍会被采用",
                                "Used as current mileage after saving; larger mileage in records still wins",
                            ),
                        )
                    }
                    item("startKm") {
                        FormField(
                            value = startKmText,
                            onValueChange = { startKmText = it },
                            label = tr("记账起始里程（选填）", "Start mileage (optional)"),
                            suffix = Units.distanceUnit,
                            keyboardType = KeyboardType.Decimal,
                            supportingText = tr(
                                "对应记账起点，用于平均每%s成本；留空时取最早记录里程",
                                "Data starting point for average cost per %s; empty uses the earliest record mileage",
                                Units.distanceUnit,
                            ),
                        )
                    }
                    item("date") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DateField(
                                label = tr("提车日期（选填）", "Delivery date (optional)"),
                                value = companionDate,
                                onClick = { showDatePicker = true },
                                onClear = { companionDate = null },
                            )
                            Text(
                                text = tr(
                                    "用于总览的「爱车陪伴天数」，起始日计为第 1 天；留空则不显示。",
                                    "Shown as days together in Overview, counting the delivery date as day 1; hidden when empty.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (image != null) {
                        item("fit") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(tr("图片显示方式", "Image fit"), style = MaterialTheme.typography.titleSmall)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    listOf("contain" to tr("完整展示", "Fit"), "cover" to tr("铺满画框", "Fill"))
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
                                        tr("铺满画框：图片裁切填满车辆位，适合照片。", "Fill: the image is cropped to fill the vehicle slot; good for photos.")
                                    } else {
                                        tr("完整展示：保留整张图片，适合透明底或抠图车辆图。", "Fit: keeps the whole image; good for cut-outs on transparent backgrounds.")
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
                                Text(if (onDelete == null) tr("添加车辆", "Add vehicle") else tr("保存车辆信息", "Save vehicle"))
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
                                        text = tr("删除这辆车", "Delete this vehicle"),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                Text(
                                    text = tr(
                                            "将删除该车的 %d 条费用、提醒与保养记录；建议先导出完整备份。",
                                            "This will delete %d expenses, reminders and service records of this vehicle; export a backup first.",
                                            recordCount,
                                        ),
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

    // 动力类型切换确认：已有记录时说明历史单位与能耗口径，并可选清理不再适用的提醒。
    pendingPowertrainSave?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingPowertrainSave = null },
            shape = MileNoteShapes.extraLarge,
            title = { Text(tr("切换动力类型？", "Switch powertrain?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr(
                            "历史记录保留原单位，能耗分析只统计当前动力类型的记录；已完成的保养提醒与费用记录不受影响。",
                            "Existing records keep their original units. Energy analysis only counts records of the current powertrain. Completed service reminders and expenses are kept.",
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = prunePowertrainReminders,
                            onCheckedChange = { prunePowertrainReminders = it },
                        )
                        Text(
                            text = tr(
                                "一并删除不再适用的保养提醒",
                                "Also delete service reminders that no longer apply",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPowertrainSave = null
                        onSave(pending, prunePowertrainReminders)
                    },
                ) {
                    Text(tr("保存", "Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPowertrainSave = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }

    if (showUrlDialog) {
        ImageUrlDialog(
            current = image,
            onConfirm = { url ->
                image = url
                hint = tr("已使用图片链接", "Image URL applied")
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
                    Text(tr("确定", "OK"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(tr("取消", "Cancel")) }
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
                Text(tr("选择图片", "Choose image"), maxLines = 1)
            }
            OutlinedButton(
                onClick = onUrl,
                shape = MileNoteShapeTokens.full,
                modifier = Modifier.weight(1f),
            ) {
                Text(tr("图片链接", "Image URL"), maxLines = 1)
            }
        }
        if (image != null) {
            TextButton(onClick = onReset) { Text(tr("恢复默认插画", "Restore default art")) }
        }
        Text(
            text = tr(
                "支持 PNG / JPG / WebP。图片在本机压缩到 1000×700 以内并以 WebP 保存在本机，不上传服务器。",
                "Supports PNG / JPG / WebP. Images are compressed to fit 1000×700 and stored as WebP in your local data; nothing is uploaded.",
            ),
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
        title = { Text(tr("填写图片链接", "Enter image URL")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        urlError = null
                    },
                    label = { Text(tr("图片地址", "Image address")) },
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
                    text = tr(
                        "链接需可公开访问；加载失败时自动显示默认插画，不影响本机数据。",
                        "The link must be publicly accessible; if it fails to load, the default illustration is shown and your data is unaffected.",
                    ),
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
                        urlError = tr("请输入以 http:// 或 https:// 开头的地址", "Enter an address starting with http:// or https://")
                    } else {
                        onConfirm(trimmed)
                    }
                },
                shape = MileNoteShapeTokens.full,
            ) {
                Text(tr("确定", "OK"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
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
                    text = value?.let { Format.dateDots(it) } ?: tr("未填写", "Not set"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value == null) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
            if (value != null) {
                IconButton(onClick = onClear, modifier = Modifier.size(MileNoteDimens.TouchTarget)) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("清除提车日期", "Clear delivery date"),
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
