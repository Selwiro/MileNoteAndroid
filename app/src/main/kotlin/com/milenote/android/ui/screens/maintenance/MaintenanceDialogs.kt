package com.milenote.android.ui.screens.maintenance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.defaultServicePlan
import com.milenote.android.domain.calc.resolveServicePlan
import com.milenote.android.domain.calc.serviceProjectOptions
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.normalizePowertrain
import com.milenote.android.domain.model.powertrainKey
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.components.CategoryChip
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.LocalDate

// ---------------------------------------------------------------------------------------------
// 完成并记录（Web `recordDialog(category='保养', serviceReminder=...)`）
// ---------------------------------------------------------------------------------------------

/**
 * 「完成并记录」弹层：为一条提醒写一条保养/维修/美容记录，并交给
 * `vm.completeService(reminder, record)` 完成本轮、生成下一轮周期。
 *
 * 默认值：日期今天、总里程当前里程、项目取提醒 `projects`、分类按提醒标题推断。
 *
 * @param onConfirm 校验通过后的记录（调用方负责 `vm.completeService` 并关闭）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompleteServiceDialog(
    ledger: Ledger,
    reminder: Reminder,
    today: LocalDate,
    currentKm: Double,
    onDismiss: () -> Unit,
    onConfirm: (Record) -> Unit,
) {
    val category = remember(reminder) { inferServiceCategory(reminder) }
    val projectOptions = remember(ledger, category) { serviceProjectOptions(ledger, category) }
    val initialProjects = remember(reminder) {
        reminder.projects?.takeIf { it.isNotEmpty() } ?: listOf(reminder.title)
    }
    var selectedProjects by remember(reminder) {
        mutableStateOf(initialProjects.toSet())
    }
    var customProject by remember(reminder) { mutableStateOf("") }
    var date by remember(reminder) { mutableStateOf(Dates.iso(today)) }
    var odometer by remember(reminder) { mutableStateOf(formatDecimalInput(currentKm)) }
    var location by remember(reminder) { mutableStateOf("") }
    var amount by remember(reminder) { mutableStateOf("") }
    var note by remember(reminder) { mutableStateOf("") }
    var error by remember(reminder) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = {
            Column {
                Text(text = tr("完成并记录", "Complete & log"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = tr(
                        "%s · 记入「%s」",
                        "%s · logged under \"%s\"",
                        DataLabels.projectFor(ledger, reminder.title),
                        DataLabels.categoryFor(ledger, category, ledger.vehicle.energyProfile),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .height(440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = date,
                        onValueChange = { date = it },
                        label = tr("日期", "Date"),
                        placeholder = "YYYY-MM-DD",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = odometer,
                        onValueChange = { odometer = it },
                        label = tr("总里程", "Odometer"),
                        suffix = Units.distanceUnit,
                        keyboardType = KeyboardType.Decimal,
                        supportingText = tr("当前 %s", "Current %s", distanceText(currentKm)),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(text = tr("保养项目", "Service items"), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    projectOptions.forEach { project ->
                        CategoryChip(
                            name = DataLabels.projectFor(ledger, project),
                            selected = project in selectedProjects,
                            onClick = {
                                selectedProjects = if (project in selectedProjects) {
                                    selectedProjects - project
                                } else {
                                    selectedProjects + project
                                }
                            },
                            ev = ledger.isEv,
                        )
                    }
                }
                FormField(
                    value = customProject,
                    onValueChange = { customProject = it },
                    label = tr("其他项目（选填，、分隔）", "Other items (optional, comma-separated)"),
                    placeholder = tr("如：雨刮片、四轮定位", "e.g. wiper blades, wheel alignment"),
                )
                FormField(
                    value = location,
                    onValueChange = { location = it },
                    label = tr("地点（选填）", "Location (optional)"),
                    placeholder = tr("门店 / 修理厂", "Shop / garage"),
                    leadingIcon = MileNoteIcons.Location,
                )
                FormField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = tr("费用（%s，免费填 0）", "Cost (%s, enter 0 if free)", Units.money),
                    suffix = Units.money,
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = note,
                    onValueChange = { note = it },
                    label = tr("备注（选填）", "Note (optional)"),
                    singleLine = false,
                    minLines = 2,
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = MaterialTheme.shapes.large,
                onClick = {
                    val dateValue = normalizeDateInput(date)
                    val odometerValue = if (odometer.isBlank()) null else parseDecimal(odometer)
                    val amountValue = if (amount.isBlank()) 0.0 else parseDecimal(amount)
                    val projects = buildList {
                        addAll(selectedProjects)
                        addAll(customProject.split(Regex("[、,，;；\\s]+")))
                    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    val nextError = when {
                        dateValue == null || dateValue.isEmpty() -> tr("日期无效", "Invalid date")
                        odometer.isNotBlank() && (odometerValue == null || odometerValue < 0) ->
                            tr("里程必须是有效非负数", "Mileage must be a valid non-negative number")
                        reminder.intervalKm != null && odometerValue == null ->
                            tr(
                                "该提醒按里程循环，请填写总里程",
                                "This reminder repeats by mileage — enter the odometer",
                            )
                        projects.isEmpty() -> tr("请填写维修保养项目", "Enter at least one service item")
                        amountValue == null || amountValue < 0 ->
                            tr("金额必须大于或等于 0", "Amount must be 0 or greater")
                        else -> null
                    }
                    error = nextError
                    if (nextError == null && dateValue != null && amountValue != null) {
                        onConfirm(
                            Record(
                                id = Ids.newId(),
                                date = dateValue,
                                category = category,
                                amount = amountValue,
                                odometer = odometerValue,
                                location = location.trim().ifEmpty { null },
                                projects = projects.joinToString("、"),
                                note = note.trim(),
                                source = "手动记录",
                                kind = "expense",
                            ),
                        )
                    }
                },
            ) {
                Text(tr("保存并完成", "Save & complete"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// 添加 / 编辑提醒（Web 提醒弹窗）
// ---------------------------------------------------------------------------------------------

/**
 * 提醒编辑弹层：标题 / 到期日期 / 到期里程 / 备注；保存 `vm.saveReminder`，
 * 编辑态提供删除（调用方接 `vm.deleteReminder`，二次确认由全局层处理）。
 *
 * @param reminder null 表示新建。
 */
@Composable
internal fun ReminderEditorDialog(
    reminder: Reminder?,
    onDismiss: () -> Unit,
    onSave: (Reminder) -> Unit,
    onDelete: (() -> Unit)? = null,
    onExportCalendar: ((Reminder) -> Unit)? = null,
) {
    var title by remember(reminder) { mutableStateOf(reminder?.title.orEmpty()) }
    var dueDate by remember(reminder) { mutableStateOf(reminder?.dueDate.orEmpty()) }
    var dueKm by remember(reminder) { mutableStateOf(formatDecimalInput(reminder?.dueKm)) }
    var note by remember(reminder) { mutableStateOf(reminder?.note.orEmpty()) }
    var error by remember(reminder) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = {
            Text(
                text = if (reminder == null) tr("添加提醒", "Add reminder") else tr("编辑提醒", "Edit reminder"),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = title,
                    onValueChange = { title = it },
                    label = tr("提醒事项", "Reminder"),
                    placeholder = tr("如：更换雨刮片", "e.g. Replace wiper blades"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = dueDate,
                        onValueChange = { dueDate = it },
                        label = tr("到期日期", "Due date"),
                        placeholder = "YYYY-MM-DD",
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = dueKm,
                        onValueChange = { dueKm = it },
                        label = tr("到期里程", "Due mileage"),
                        suffix = Units.distanceUnit,
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                FormField(
                    value = note,
                    onValueChange = { note = it },
                    label = tr("备注（选填）", "Note (optional)"),
                    singleLine = false,
                    minLines = 2,
                )
                Text(
                    text = tr("请至少设置到期日期或到期里程。", "Set at least a due date or due mileage."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (onExportCalendar != null && reminder != null && Dates.isValid(dueDate.trim())) {
                    TextButton(
                        onClick = {
                            onExportCalendar(
                                reminder.copy(
                                    title = title.trim().ifEmpty { reminder.title },
                                    dueDate = dueDate.trim(),
                                    dueKm = parseDecimal(dueKm),
                                    note = note.trim(),
                                ),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Calendar,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr("导出日历", "Export to calendar"))
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = tr("删除提醒", "Delete reminder"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                shape = MaterialTheme.shapes.large,
                onClick = {
                    val dateValue = normalizeDateInput(dueDate)
                    val kmValue = if (dueKm.isBlank()) null else parseDecimal(dueKm)
                    val nextError = when {
                        title.isBlank() -> tr("请填写提醒事项", "Enter a reminder title")
                        dateValue == null -> tr("日期无效", "Invalid date")
                        dateValue.isEmpty() && kmValue == null ->
                            tr("请至少设置到期日期或到期里程。", "Set at least a due date or due mileage.")
                        dueKm.isNotBlank() && (kmValue == null || kmValue < 0) ->
                            tr("里程必须是有效非负数", "Mileage must be a valid non-negative number")
                        else -> null
                    }
                    error = nextError
                    if (nextError == null && dateValue != null) {
                        val base = reminder ?: Reminder(id = Ids.newId(), source = "手动提醒")
                        onSave(
                            base.copy(
                                title = title.trim(),
                                dueDate = dateValue,
                                dueKm = kmValue,
                                note = note.trim(),
                                projects = base.projects ?: listOf(title.trim()),
                            ),
                        )
                    }
                },
            ) {
                Text(tr("保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// 启用默认保养方案（基准输入弹层）
// ---------------------------------------------------------------------------------------------

/**
 * 「启用默认方案」弹层：输入基准日期 / 基准里程 / 是否包含首保，
 * 确认后调用 `vm.useDefaultServicePlan(baseDate, baseKm, includeFirst)`。
 */
@Composable
internal fun DefaultServicePlanDialog(
    ledger: Ledger,
    onDismiss: () -> Unit,
    onConfirm: (baseDate: String, baseKm: Double, includeFirst: Boolean) -> Unit,
) {
    var baseDate by remember(ledger.id) {
        mutableStateOf(
            ledger.vehicle.companionDate?.takeIf { Dates.isValid(it) } ?: Dates.iso(Dates.today()),
        )
    }
    var baseKm by remember(ledger.id) { mutableStateOf(formatDecimalInput(ledger.vehicle.startKm ?: 0.0)) }
    var includeFirst by remember(ledger.id) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 项数随动力类型变化：燃油/HEV 12 项、PHEV 13 项、纯电 6 项（均含首保）。
    val planSize = remember(ledger.id) { defaultServicePlan(ledger.vehicle.powertrainKey).size }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = {
            Text(
                text = tr("启用默认保养方案", "Enable default service plan"),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = tr(
                        "将按默认 %d 项周期（首保 + %d 项常规保养）生成提醒，以提车日期和记账起始里程为基准。",
                        "Creates reminders for the %d default intervals (first service + %d routine " +
                            "services), based on the delivery date and the data's starting mileage.",
                        planSize,
                        planSize - 1,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormField(
                    value = baseDate,
                    onValueChange = { baseDate = it },
                    label = tr("基准日期", "Base date"),
                    placeholder = "YYYY-MM-DD",
                    supportingText = tr("默认提车日期", "Defaults to delivery date"),
                )
                FormField(
                    value = baseKm,
                    onValueChange = { baseKm = it },
                    label = tr("基准总里程", "Base odometer"),
                    suffix = Units.distanceUnit,
                    keyboardType = KeyboardType.Decimal,
                    supportingText = tr("默认记账起始里程", "Defaults to starting mileage"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeFirst = !includeFirst },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeFirst,
                        onCheckedChange = { includeFirst = it },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tr(
                            "包含首保（已完成的首保不会重复创建）",
                            "Include first service (a completed first service won't be recreated)",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                shape = MaterialTheme.shapes.large,
                onClick = {
                    val dateValue = normalizeDateInput(baseDate)
                    val kmValue = parseDecimal(baseKm)
                    val nextError = when {
                        dateValue == null || dateValue.isEmpty() ->
                            tr("请填写有效基准日期", "Enter a valid base date")
                        kmValue == null || kmValue < 0 ->
                            tr("请填写有效基准里程", "Enter a valid base odometer")
                        else -> null
                    }
                    error = nextError
                    if (nextError == null && dateValue != null && kmValue != null) {
                        onConfirm(dateValue, kmValue, includeFirst)
                    }
                },
            ) {
                Text(tr("启用方案", "Enable plan"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// 保养方案编辑器（全屏 Dialog，Web `.plan-dialog` / `servicePlanFormHTML`）
// ---------------------------------------------------------------------------------------------

/** 方案编辑器一行草稿（留空表示沿用默认周期）。 */
private data class PlanDraftRow(
    val key: String,
    val title: String,
    val builtin: Boolean,
    val firstOnly: Boolean,
    val projects: List<String>?,
    val defaultKm: Double?,
    val defaultMonths: Int?,
    val kmText: String,
    val monthsText: String,
)

/** 构建方案条目的结果。 */
private data class PlanBuildResult(val entries: List<PlanEntry>?, val error: String?)

/** 默认草稿行：按动力类型取内置方案（燃油/HEV 12 项 / PHEV 13 项 / 纯电 6 项）。 */
private fun defaultPlanDraftRows(powertrain: String?): List<PlanDraftRow> = defaultServicePlan(powertrain).map { plan ->
    PlanDraftRow(
        key = plan.key,
        title = plan.title.orEmpty(),
        builtin = true,
        firstOnly = plan.firstOnly == true,
        projects = plan.projects,
        defaultKm = plan.intervalKm,
        defaultMonths = plan.intervalMonths,
        kmText = "",
        monthsText = "",
    )
}

private fun initialPlanDraftRows(ledger: Ledger): List<PlanDraftRow> {
    val powertrain = ledger.vehicle.powertrainKey
    val defaults = defaultServicePlan(powertrain)
    val overrides = ledger.servicePlan.orEmpty().associateBy { it.key }
    val resolved = runCatching { resolveServicePlan(ledger.servicePlan.orEmpty(), powertrain) }
        .getOrDefault(defaults)
    return resolved.map { plan ->
        val override = overrides[plan.key]
        val default = defaults.firstOrNull { it.key == plan.key }
        PlanDraftRow(
            key = plan.key,
            title = plan.title.orEmpty(),
            builtin = default != null,
            firstOnly = plan.firstOnly == true,
            projects = plan.projects ?: default?.projects,
            defaultKm = default?.intervalKm,
            defaultMonths = default?.intervalMonths,
            kmText = override?.intervalKm?.let { formatDecimalInput(it) } ?: "",
            monthsText = override?.intervalMonths?.toString() ?: "",
        )
    }
}

private fun buildPlanEntries(rows: List<PlanDraftRow>): PlanBuildResult {
    val titles = mutableSetOf<String>()
    val entries = mutableListOf<PlanEntry>()
    for (row in rows) {
        val title = row.title.trim()
        if (title.isEmpty() || !titles.add(title)) {
            return PlanBuildResult(null, tr("项目名称不能为空或重复", "Item names must be non-empty and unique"))
        }
        val km = if (row.kmText.isBlank()) null else parseDecimal(row.kmText)
        if (row.kmText.isNotBlank() && (km == null || km <= 0)) {
            return PlanBuildResult(
                null,
                tr(
                    "%s：里程须为正数",
                    "%s: mileage must be a positive number",
                    DataLabels.serviceItem(title),
                ),
            )
        }
        val monthsRaw = if (row.monthsText.isBlank()) null else parseDecimal(row.monthsText)
        if (row.monthsText.isNotBlank()) {
            if (monthsRaw == null || monthsRaw % 1.0 != 0.0 || monthsRaw < 1 || monthsRaw > 600) {
                return PlanBuildResult(
                    null,
                    tr(
                        "%s：时长须为 1–600 的整月",
                        "%s: duration must be a whole number of months from 1 to 600",
                        DataLabels.serviceItem(title),
                    ),
                )
            }
        }
        if (!row.builtin && km == null && monthsRaw == null) {
            return PlanBuildResult(
                null,
                tr(
                    "%s：自定义项目至少填写里程或时长",
                    "%s: custom items need at least a mileage or duration",
                    DataLabels.serviceItem(title),
                ),
            )
        }
        entries += PlanEntry(
            key = row.key,
            title = title,
            intervalKm = km,
            intervalMonths = monthsRaw?.toInt(),
            firstOnly = row.firstOnly,
            projects = row.projects,
        )
    }
    return PlanBuildResult(entries, null)
}

/**
 * 保养方案全屏编辑器（28dp 顶角 + 关闭/保存）。
 *
 * 显式消费 VM 的 `showServicePlanEditor` 状态：保存成功时 VM 置 false 关闭，
 * 失败（校验错误）保持打开并保留编辑内容，错误经全局 Snackbar 展示。
 *
 * @param onSave 调用 `vm.saveServicePlan(entries, baseDate, baseKm, includeFirst)`。
 */
@Composable
internal fun ServicePlanEditorDialog(
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSave: (entries: List<PlanEntry>, baseDate: String, baseKm: Double, includeFirst: Boolean) -> Unit,
) {
    var rows by remember(ledger.id) { mutableStateOf(initialPlanDraftRows(ledger)) }
    var baseDate by remember(ledger.id) {
        mutableStateOf(
            ledger.servicePlanBase?.date?.takeIf { Dates.isValid(it) }
                ?: ledger.vehicle.companionDate?.takeIf { Dates.isValid(it) }
                ?: Dates.iso(Dates.today()),
        )
    }
    var baseKm by remember(ledger.id) {
        mutableStateOf(formatDecimalInput(ledger.servicePlanBase?.km ?: ledger.vehicle.startKm ?: 0.0))
    }
    var includeFirst by remember(ledger.id) {
        mutableStateOf(ledger.servicePlanBase?.includeFirst ?: true)
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun updateRow(key: String, transform: (PlanDraftRow) -> PlanDraftRow) {
        rows = rows.map { if (it.key == key) transform(it) else it }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // 顶栏：关闭 / 标题 / 恢复默认
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MileNoteIcons.Close,
                            contentDescription = tr("关闭", "Close"),
                        )
                    }
                    Text(
                        text = tr("编辑保养计划", "Edit service plan"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { rows = defaultPlanDraftRows(ledger.vehicle.powertrainKey) }) {
                        Text(tr("恢复默认", "Reset to default"))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = tr("起算基准", "Start baseline"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            value = baseDate,
                            onValueChange = { baseDate = it },
                            label = tr("基准日期", "Base date"),
                            placeholder = "YYYY-MM-DD",
                            modifier = Modifier.weight(1f),
                        )
                        FormField(
                            value = baseKm,
                            onValueChange = { baseKm = it },
                            label = tr("基准总里程", "Base odometer"),
                            suffix = Units.distanceUnit,
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = tr(
                            "默认使用提车日期和记账起始里程。已有保养记录的项目优先按最近一次实际保养起算。",
                            "Defaults to the delivery date and starting mileage. Items with service " +
                                "records count from the latest actual service.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeFirst = !includeFirst },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = includeFirst,
                            onCheckedChange = { includeFirst = it },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = tr("包含首保", "Include first service"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = tr(
                                    "已完成的首保不会重复创建",
                                    "A completed first service won't be recreated",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = tr("项目与周期", "Items & intervals"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = tr(
                            "默认项目留空沿用灰字提示的周期；自定义项目至少填写一项。12 个月为 1 年。",
                            "Leave a default item blank to keep the greyed-out interval; custom items " +
                                "need at least one value. 12 months = 1 year.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PlanEditorTable(
                        rows = rows,
                        ledger = ledger,
                        powertrain = ledger.vehicle.powertrainKey,
                        onUpdateRow = { key, transform -> updateRow(key, transform) },
                        onRemoveRow = { key -> rows = rows.filterNot { it.key == key } },
                    )

                    OutlinedButton(
                        onClick = {
                            rows = rows + PlanDraftRow(
                                key = "custom-${Ids.newId()}",
                                title = "",
                                builtin = false,
                                firstOnly = false,
                                projects = null,
                                defaultKm = null,
                                defaultMonths = null,
                                kmText = "",
                                monthsText = "",
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MileNoteDimens.ButtonMinHeight),
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("新增自定义项目", "Add custom item"))
                    }
                    Text(
                        text = tr(
                            "保存后更新未完成项目，保留已完成记录。不勾选首保时，不新增或修改已有首保提醒。",
                            "Saving updates open items and keeps completed records. When first service " +
                                "is unchecked, existing first-service reminders are left untouched.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(tr("取消", "Cancel"))
                    }
                    Button(
                        onClick = {
                            val dateValue = normalizeDateInput(baseDate)
                            val kmValue = parseDecimal(baseKm)
                            val build = buildPlanEntries(rows)
                            val nextError = when {
                                dateValue == null || dateValue.isEmpty() ->
                                    tr("请填写有效基准日期", "Enter a valid base date")
                                kmValue == null || kmValue < 0 ->
                                    tr("请填写有效基准里程", "Enter a valid base odometer")
                                else -> build.error
                            }
                            error = nextError
                            if (nextError == null && build.entries != null &&
                                dateValue != null && kmValue != null
                            ) {
                                onSave(build.entries, dateValue, kmValue, includeFirst)
                            }
                        },
                        modifier = Modifier
                            .weight(2f)
                            .height(MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(tr("保存并应用方案", "Save & apply plan"))
                    }
                }
            }
        }
    }
}

/** 编辑计划表格：项目 / 周期里程 / 周期月数 三列对齐。 */
@Composable
private fun PlanEditorTable(
    rows: List<PlanDraftRow>,
    ledger: Ledger,
    powertrain: String?,
    onUpdateRow: (String, (PlanDraftRow) -> PlanDraftRow) -> Unit,
    onRemoveRow: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr("项目", "Item"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = tr("周期%s", "Interval %s", Units.distanceUnit),
                modifier = Modifier.width(96.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            Text(
                text = tr("周期月数", "Interval months"),
                modifier = Modifier.width(84.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            Spacer(modifier = Modifier.width(32.dp))
        }
        HorizontalDivider(color = scheme.outlineVariant)
        rows.forEachIndexed { index, row ->
            PlanEditorTableRow(
                row = row,
                ledger = ledger,
                powertrain = powertrain,
                onUpdate = { transform -> onUpdateRow(row.key, transform) },
                onRemove = if (row.builtin) null else ({ onRemoveRow(row.key) }),
            )
            if (index != rows.lastIndex) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun PlanEditorTableRow(
    row: PlanDraftRow,
    ledger: Ledger,
    powertrain: String?,
    onUpdate: ((PlanDraftRow) -> PlanDraftRow) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (row.builtin) {
                Text(
                    text = DataLabels.projectFor(ledger, row.title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.firstOnly) {
                    Text(
                        text = when (normalizePowertrain(powertrain)) {
                            POWERTRAIN_EV -> tr("仅一次 · 含三电诊断", "One-off · includes EV system check")
                            POWERTRAIN_PHEV -> tr(
                                "仅一次 · 含机油机滤与三电诊断",
                                "One-off · includes oil & filter and EV system check",
                            )
                            else -> tr("仅一次 · 含机油机滤", "One-off · includes oil & filter")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            } else {
                PlanEditorCell(
                    value = row.title,
                    onValueChange = { value -> onUpdate { it.copy(title = value) } },
                    placeholder = tr("项目名称", "Item name"),
                    keyboardType = KeyboardType.Text,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PlanEditorCell(
            value = row.kmText,
            onValueChange = { value -> onUpdate { it.copy(kmText = value) } },
            placeholder = row.defaultKm?.let { Format.fmt(it) } ?: tr("选填", "Optional"),
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(96.dp),
        )
        PlanEditorCell(
            value = row.monthsText,
            onValueChange = { value -> onUpdate { it.copy(monthsText = value) } },
            placeholder = row.defaultMonths?.toString() ?: tr("选填", "Optional"),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(84.dp),
        )
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Delete,
                    contentDescription = tr("删除自定义项目", "Delete custom item"),
                    modifier = Modifier.size(18.dp),
                    tint = scheme.error,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(32.dp))
        }
    }
}

/** 表格里的紧凑输入框：灰字占位显示默认周期，聚焦时边框变主色。 */
@Composable
private fun PlanEditorCell(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.End,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = MileNoteShapes.small
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(scheme.surfaceContainerLowest)
            .border(
                width = 1.dp,
                color = if (focused) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = scheme.onSurface,
            textAlign = textAlign,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}
