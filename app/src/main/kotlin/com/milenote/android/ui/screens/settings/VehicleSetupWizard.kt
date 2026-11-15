package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_FUEL
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankVehicle
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import java.time.LocalDate

/**
 * 建车向导（F6）：首次使用 3 步把车建对，避免跳过动力类型 / 里程导致油耗、每公里成本与保养方案全错。
 *
 * 每步都可跳过；跳过全部 = 现在的空白车。第 3 步可选「启用默认保养方案」（燃油 12 / 插混 13 / 纯电 6 项）。
 * 不改账本结构，只写现有 [Vehicle] 字段。
 *
 * @param onFinish 回传车辆、提车日期（保养方案基准日期）与是否启用默认方案。
 * @param onUseFullEditor 「直接填全部信息」→ 打开现有车辆编辑弹窗。
 */
@Composable
internal fun VehicleSetupWizard(
    onDismiss: () -> Unit,
    onFinish: (vehicle: Vehicle, baseDate: String?, enablePlan: Boolean) -> Unit,
    onUseFullEditor: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var powertrain by rememberSaveable { mutableStateOf(POWERTRAIN_FUEL) }
    var currentKmText by rememberSaveable { mutableStateOf("") }
    var startKmText by rememberSaveable { mutableStateOf("") }
    var companionDate by rememberSaveable { mutableStateOf<String?>(null) }
    var enablePlan by rememberSaveable { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }

    val currentKm = parseKm(currentKmText)
    val startKm = parseKm(startKmText)
    val currentInvalid = currentKmText.isNotBlank() && currentKm == null
    val startInvalid = startKmText.isNotBlank() && startKm == null
    val orderInvalid = currentKm != null && startKm != null && startKm > currentKm
    val canUsePlan = (currentKm ?: startKm)?.let { it > 0 } == true
    val canFinish = !currentInvalid && !startInvalid && !orderInvalid

    fun finish() {
        if (!canFinish) return
        val vehicle = blankVehicle(powertrain).copy(
            currentKm = currentKm ?: 0.0,
            startKm = startKm,
            companionDate = companionDate,
        )
        onFinish(vehicle, companionDate, enablePlan && canUsePlan)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("设置我的车", "Set up my car"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = tr("第 %d / 3 步", "Step %d of 3", step + 1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MileNoteIcons.Close,
                            contentDescription = tr("关闭", "Close"),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (step) {
                        0 -> PowertrainStep(selected = powertrain, onSelect = { powertrain = it })
                        1 -> MileageStep(
                            title = tr("现在总里程", "Current mileage"),
                            hint = tr(
                                "选填，但建议填写：每公里成本、提醒剩余里程都按它算。",
                                "Optional but recommended: cost per km and reminder mileage use it.",
                            ),
                            value = currentKmText,
                            onValueChange = { currentKmText = it },
                            invalid = currentInvalid,
                            invalidText = tr("请输入有效数字", "Enter a valid number"),
                        )
                        else -> {
                            MileageStep(
                                title = tr("记账起始里程", "Starting mileage"),
                                hint = tr(
                                    "选填：从哪一公里开始记账。不知道可以跳过。",
                                    "Optional: where your records start. Skip it if unsure.",
                                ),
                                value = startKmText,
                                onValueChange = { startKmText = it },
                                invalid = startInvalid || orderInvalid,
                                invalidText = if (orderInvalid) {
                                    tr("起始里程不能大于当前里程", "Starting mileage cannot exceed current mileage")
                                } else {
                                    tr("请输入有效数字", "Enter a valid number")
                                },
                            )
                            DateRow(
                                value = companionDate,
                                onClick = { showDatePicker = true },
                                onClear = { companionDate = null },
                            )
                            PlanToggle(
                                checked = enablePlan && canUsePlan,
                                enabled = canUsePlan,
                                powertrain = powertrain,
                                onCheckedChange = { enablePlan = it },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onUseFullEditor,
                        modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                    ) {
                        Text(tr("直接填全部信息", "Enter everything"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (step > 0) {
                        TextButton(
                            onClick = { step -= 1 },
                            modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                        ) {
                            Text(tr("上一步", "Back"))
                        }
                    }
                    TextButton(
                        onClick = {
                            if (step < 2) step += 1 else finish()
                        },
                        enabled = step < 2 || canFinish,
                        modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                    ) {
                        Text(if (step < 2) tr("下一步", "Next") else tr("完成", "Finish"))
                    }
                }
            }
        }
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

@Composable
private fun PowertrainStep(selected: String, onSelect: (String) -> Unit) {
    Text(
        text = tr("这辆车怎么补能？", "How does this car get energy?"),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = tr(
            "决定记一笔的字段、能耗口径与保养方案项数；选错会让后面的数字都不对。",
            "This decides the entry fields, energy metrics and service plan; a wrong choice makes later numbers wrong.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    listOf(
        POWERTRAIN_FUEL to (tr("燃油 / 油电混动", "Petrol / hybrid") to tr("只加油，按满箱法算油耗", "Refuel only; tank-to-tank fuel economy")),
        POWERTRAIN_PHEV to (tr("插混 / 增程", "Plug-in hybrid / EREV") to tr("加油 + 充电，两条能耗分别算", "Refuel and charge; two energy streams")),
        POWERTRAIN_EV to (tr("纯电", "Electric") to tr("只充电，按满电法算电耗", "Charge only; full-charge energy economy")),
    ).forEach { (key, labels) ->
        val isSelected = key == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    shape = MaterialTheme.shapes.medium,
                )
                .clickable { onSelect(key) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (isSelected) MileNoteIcons.CheckCircle else MileNoteIcons.Circle,
                contentDescription = null,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(text = labels.first, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = labels.second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MileageStep(
    title: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    invalid: Boolean,
    invalidText: String,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid,
        label = { Text(tr("公里数（选填）", "Mileage (optional)")) },
        supportingText = if (invalid) {
            { Text(invalidText) }
        } else {
            null
        },
    )
}

@Composable
private fun DateRow(value: String?, onClick: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tr("提车日期（选填）", "Delivery date (optional)"), style = MaterialTheme.typography.titleSmall)
            Text(
                text = value?.let { Format.dateDots(it) } ?: tr("用于保养方案的时间基准", "Used as the time base for the service plan"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value != null) {
            TextButton(onClick = onClear) { Text(tr("清除", "Clear")) }
        }
    }
}

@Composable
private fun PlanToggle(
    checked: Boolean,
    enabled: Boolean,
    powertrain: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val count = when (powertrain) {
        POWERTRAIN_EV -> 6
        POWERTRAIN_PHEV -> 13
        else -> 12
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tr("启用默认保养方案", "Enable the default service plan"), style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (enabled) {
                    tr(
                        "按提车日期 / 起始里程生成 %d 项周期（含首保）。",
                        "Creates %d intervals (including the first service) from the delivery date / starting mileage.",
                        count,
                    )
                } else {
                    tr("先填一个里程才能生成方案", "Enter a mileage first to create the plan")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 宽松解析：容忍千分位与全角逗号；空串返回 null（表示未填）。 */
private fun parseKm(text: String): Double? {
    val cleaned = text.trim().replace(",", "").replace("，", "")
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}
