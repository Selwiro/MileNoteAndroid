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
import com.milenote.android.domain.calc.DEFAULT_SERVICE_PLAN
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.resolveServicePlan
import com.milenote.android.domain.calc.serviceProjectOptions
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
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
                Text(text = "完成并记录", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${reminder.title} · 记入「$category」",
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
                        label = "日期",
                        placeholder = "YYYY-MM-DD",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = odometer,
                        onValueChange = { odometer = it },
                        label = "总里程",
                        suffix = "公里",
                        keyboardType = KeyboardType.Decimal,
                        supportingText = "当前 ${Format.km(currentKm)}",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(text = "保养项目", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    projectOptions.forEach { project ->
                        CategoryChip(
                            name = project,
                            selected = project in selectedProjects,
                            onClick = {
                                selectedProjects = if (project in selectedProjects) {
                                    selectedProjects - project
                                } else {
                                    selectedProjects + project
                                }
                            },
                        )
                    }
                }
                FormField(
                    value = customProject,
                    onValueChange = { customProject = it },
                    label = "其他项目（选填，、分隔）",
                    placeholder = "如：雨刮片、四轮定位",
                )
                FormField(
                    value = location,
                    onValueChange = { location = it },
                    label = "地点（选填）",
                    placeholder = "门店 / 修理厂",
                    leadingIcon = MileNoteIcons.Location,
                )
                FormField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = "费用（元，免费填 0）",
                    suffix = "元",
                    keyboardType = KeyboardType.Decimal,
                )
                FormField(
                    value = note,
                    onValueChange = { note = it },
                    label = "备注（选填）",
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
                        dateValue == null || dateValue.isEmpty() -> "日期无效"
                        odometer.isNotBlank() && (odometerValue == null || odometerValue < 0) ->
                            "里程必须是有效非负数"
                        reminder.intervalKm != null && odometerValue == null ->
                            "该提醒按公里数循环，请填写总里程"
                        projects.isEmpty() -> "请填写维修保养项目"
                        amountValue == null || amountValue < 0 -> "金额必须大于或等于 0"
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
                Text("保存并完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
                text = if (reminder == null) "添加提醒" else "编辑提醒",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = title,
                    onValueChange = { title = it },
                    label = "提醒事项",
                    placeholder = "如：更换雨刮片",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = dueDate,
                        onValueChange = { dueDate = it },
                        label = "到期日期",
                        placeholder = "YYYY-MM-DD",
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = dueKm,
                        onValueChange = { dueKm = it },
                        label = "到期里程",
                        suffix = "公里",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                FormField(
                    value = note,
                    onValueChange = { note = it },
                    label = "备注（选填）",
                    singleLine = false,
                    minLines = 2,
                )
                Text(
                    text = "请至少设置到期日期或到期里程。",
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
                        Text("导出日历")
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = "删除提醒",
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
                        title.isBlank() -> "请填写提醒事项"
                        dateValue == null -> "日期无效"
                        dateValue.isEmpty() && kmValue == null -> "请至少设置到期日期或到期里程。"
                        dueKm.isNotBlank() && (kmValue == null || kmValue < 0) -> "里程必须是有效非负数"
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = { Text(text = "启用默认保养方案", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "将按默认 12 项周期（首保 + 11 项常规保养）生成提醒，" +
                        "以提车日期和记账起始里程为基准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FormField(
                    value = baseDate,
                    onValueChange = { baseDate = it },
                    label = "基准日期",
                    placeholder = "YYYY-MM-DD",
                    supportingText = "默认提车日期",
                )
                FormField(
                    value = baseKm,
                    onValueChange = { baseKm = it },
                    label = "基准总里程",
                    suffix = "公里",
                    keyboardType = KeyboardType.Decimal,
                    supportingText = "默认记账起始里程",
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
                        text = "包含首保（已完成的首保不会重复创建）",
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
                        dateValue == null || dateValue.isEmpty() -> "请填写有效基准日期"
                        kmValue == null || kmValue < 0 -> "请填写有效基准里程"
                        else -> null
                    }
                    error = nextError
                    if (nextError == null && dateValue != null && kmValue != null) {
                        onConfirm(dateValue, kmValue, includeFirst)
                    }
                },
            ) {
                Text("启用方案")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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

private fun defaultPlanDraftRows(): List<PlanDraftRow> = DEFAULT_SERVICE_PLAN.map { plan ->
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
    val overrides = ledger.servicePlan.orEmpty().associateBy { it.key }
    val resolved = runCatching { resolveServicePlan(ledger.servicePlan.orEmpty()) }
        .getOrDefault(DEFAULT_SERVICE_PLAN)
    return resolved.map { plan ->
        val override = overrides[plan.key]
        val default = DEFAULT_SERVICE_PLAN.firstOrNull { it.key == plan.key }
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
            return PlanBuildResult(null, "项目名称不能为空或重复")
        }
        val km = if (row.kmText.isBlank()) null else parseDecimal(row.kmText)
        if (row.kmText.isNotBlank() && (km == null || km <= 0)) {
            return PlanBuildResult(null, "$title：公里数须为正数")
        }
        val monthsRaw = if (row.monthsText.isBlank()) null else parseDecimal(row.monthsText)
        if (row.monthsText.isNotBlank()) {
            if (monthsRaw == null || monthsRaw % 1.0 != 0.0 || monthsRaw < 1 || monthsRaw > 600) {
                return PlanBuildResult(null, "$title：时长须为 1–600 的整月")
            }
        }
        if (!row.builtin && km == null && monthsRaw == null) {
            return PlanBuildResult(null, "$title：自定义项目至少填写公里数或时长")
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
                            contentDescription = "关闭",
                        )
                    }
                    Text(
                        text = "编辑保养计划",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { rows = defaultPlanDraftRows() }) {
                        Text("恢复默认")
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
                    Text(text = "起算基准", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormField(
                            value = baseDate,
                            onValueChange = { baseDate = it },
                            label = "基准日期",
                            placeholder = "YYYY-MM-DD",
                            modifier = Modifier.weight(1f),
                        )
                        FormField(
                            value = baseKm,
                            onValueChange = { baseKm = it },
                            label = "基准总里程",
                            suffix = "公里",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "默认使用提车日期和记账起始里程。已有保养记录的项目优先按最近一次实际保养起算。",
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
                                text = "包含首保",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "已完成的首保不会重复创建",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = "项目与周期", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "默认项目留空沿用灰字提示的周期；自定义项目至少填写一项。12 个月为 1 年。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PlanEditorTable(
                        rows = rows,
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
                        Text("新增自定义项目")
                    }
                    Text(
                        text = "保存后更新未完成项目，保留已完成记录。不勾选首保时，不新增或修改已有首保提醒。",
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
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val dateValue = normalizeDateInput(baseDate)
                            val kmValue = parseDecimal(baseKm)
                            val build = buildPlanEntries(rows)
                            val nextError = when {
                                dateValue == null || dateValue.isEmpty() -> "请填写有效基准日期"
                                kmValue == null || kmValue < 0 -> "请填写有效基准里程"
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
                        Text("保存并应用方案")
                    }
                }
            }
        }
    }
}

/** 编辑计划表格：项目 / 周期公里 / 周期月数 三列对齐。 */
@Composable
private fun PlanEditorTable(
    rows: List<PlanDraftRow>,
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
                text = "项目",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = "周期公里",
                modifier = Modifier.width(96.dp),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            Text(
                text = "周期月数",
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
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.firstOnly) {
                    Text(
                        text = "仅一次 · 含机油机滤",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            } else {
                PlanEditorCell(
                    value = row.title,
                    onValueChange = { value -> onUpdate { it.copy(title = value) } },
                    placeholder = "项目名称",
                    keyboardType = KeyboardType.Text,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PlanEditorCell(
            value = row.kmText,
            onValueChange = { value -> onUpdate { it.copy(kmText = value) } },
            placeholder = row.defaultKm?.let { Format.fmt(it) } ?: "选填",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.width(96.dp),
        )
        PlanEditorCell(
            value = row.monthsText,
            onValueChange = { value -> onUpdate { it.copy(monthsText = value) } },
            placeholder = row.defaultMonths?.toString() ?: "选填",
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
                    contentDescription = "删除自定义项目",
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
