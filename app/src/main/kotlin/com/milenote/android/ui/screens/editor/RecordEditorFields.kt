package com.milenote.android.ui.screens.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.Categories
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteCategoryNames
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.moneyLarge

/**
 * 「记一笔」弹层字段区（拆自 [RecordEditorSheet]，全部以 [RecordEditorState] 为唯一数据源）。
 *
 * 字段顺序（任务约定）：分类 → 金额 → 日期 → 总里程 → 加油分组 → 服务分组 → 备注。
 */

/**
 * 金额输入：大号 tabular 数字、货币符号前缀、错误就地显示在下方。
 *
 * 打开页面时不自动聚焦（避免一进入就弹输入法）；用户点击输入框才弹键盘。
 *
 * 分类为油费时，尾部显示「选择小票照片识别」相机按钮（OCR 入口从加油量字段移到这里，
 * 避免出现两个相机图标）；非油费不显示。
 */
@Composable
internal fun AmountField(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
    onOcrClick: (() -> Unit)? = null,
) {
    val error = form.amountError()
    OutlinedTextField(
        value = form.amount,
        onValueChange = {
            form.amount = it
            form.onFuelFieldChanged("amount")
        },
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.moneyLarge,
        label = {
            Text(
                text = if (form.isManual) {
                    tr("费用金额 · %s（可选）", "Amount · %s (optional)", Units.money)
                } else {
                    tr("实付金额 · %s", "Amount paid · %s", Units.money)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        prefix = {
            Text(text = Units.currencySymbol, style = MaterialTheme.typography.labelLarge)
        },
        isError = error != null,
        supportingText = error?.let {
            { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        },
        trailingIcon = if (form.isFuel && !form.electricView && onOcrClick != null) {
            {
                IconButton(onClick = onOcrClick) {
                    Icon(
                        imageVector = MileNoteIcons.Camera,
                        contentDescription = tr("选择小票照片识别", "Scan receipt"),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = MileNoteShapes.medium,
    )
}

/** 日期：只读输入框 + 点击弹 M3 DatePicker。 */
@Composable
internal fun DateField(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxWidth()) {
        FormField(
            value = form.date,
            onValueChange = {},
            label = tr("日期", "Date"),
            readOnly = true,
            trailing = {
                Icon(
                    imageVector = MileNoteIcons.Calendar,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    onClickLabel = tr("选择日期", "Select date"),
                    role = Role.Button,
                ) { form.showDatePicker = true },
        )
    }
}

/** 分类：9 类 + 自定义 chip 网格 +「新增分类」。 */
/** 固定分类顺序（两行 × 5，最后一项为「新增分类」）。 */
private val EditorCategoryOrder = listOf(
    "油费", "保险", "保养", "维修", "汽车美容",
    "过路费", "停车费", "罚单", "汽车用品",
)

private const val AddCategoryCell = "__add_category__"

/**
 * 费用分类：置顶、无标题，5 列图标在上文字在下的网格。
 * 内置分类按固定顺序排列，自定义分类排在「汽车用品」之后，「新增分类」固定最后。
 */
@Composable
internal fun CategoryField(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    val customCategories = form.categoryOptions.filter {
        Categories.normalize(it) !in MileNoteCategoryNames
    }
    val cells = EditorCategoryOrder.map { it to false } +
        customCategories.map { it to false } +
        (AddCategoryCell to true)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        cells.chunked(5).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.forEach { (name, isAdd) ->
                    CategoryCell(
                        label = if (isAdd) {
                            tr("新增分类", "Add category")
                        } else {
                            DataLabels.category(name, form.energyProfile)
                        },
                        icon = when {
                            isAdd -> MileNoteIcons.Add
                            else -> {
                                val index = MileNoteCategoryNames.indexOf(name)
                                MileNoteIcons.Category.getOrNull(index) ?: MileNoteIcons.CategoryGoods
                            }
                        },
                        selected = !isAdd && Categories.normalize(form.category) == name,
                        onClick = {
                            if (isAdd) form.showCategoryDialog = true else form.category = name
                        },
                    )
                }
                repeat(5 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CategoryCell(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .weight(1f)
            .background(
                color = if (selected) scheme.primaryContainer else Color.Transparent,
                shape = MileNoteShapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** 总里程（选填，Decimal）。 */
@Composable
internal fun OdometerField(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    FormField(
        value = form.odometer,
        onValueChange = { form.odometer = it },
        label = tr("总里程（选填）", "Odometer (optional)"),
        suffix = Units.distanceUnit,
        keyboardType = KeyboardType.Decimal,
        leadingIcon = MileNoteIcons.Road,
        modifier = modifier,
    )
}

/** 能源分组：燃油=燃油标号下拉 + 加油量 / 油价；纯电=充电量 / 电价 + 充满 / 漏记。 */
@Composable
internal fun FuelFields(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                value = if (form.electricView) form.kwh else form.liters,
                onValueChange = {
                    if (form.electricView) {
                        form.kwh = it
                        form.onFuelFieldChanged("kwh")
                    } else {
                        form.liters = it
                        form.onFuelFieldChanged("liters")
                    }
                },
                label = if (form.electricView) tr("充电量", "Charge amount") else tr("加油量", "Fuel amount"),
                suffix = if (form.electricView) Units.energyUnit else Units.volumeUnit,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = form.unitPrice,
                onValueChange = {
                    form.unitPrice = it
                    form.onFuelFieldChanged("unitPrice")
                },
                label = if (form.electricView) tr("电价", "Unit price") else tr("油价", "Unit price"),
                suffix = if (form.electricView) Units.pricePerEnergy else Units.pricePerVolume,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                value = form.payable,
                onValueChange = {
                    form.payable = it
                    form.onFuelFieldChanged("payable")
                },
                label = tr("应付金额", "Amount due"),
                suffix = Units.money,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = form.discount,
                onValueChange = {
                    form.discount = it
                    form.onFuelFieldChanged("discount")
                },
                label = tr("优惠金额", "Discount"),
                suffix = Units.money,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
        }
        // 方案 3：两个勾选按自然宽度靠左，燃油标号紧凑下拉靠右。
        // 上 12 / 下 0：补偿容器间距（油费分组内部 8dp、外层到备注 12dp），让视觉上下等距。
        Row(
            modifier = Modifier.padding(top = 12.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CheckRow(
                label = if (form.electricView) tr("本次充满", "Full charge this time") else tr("本次加满", "Filled up this time"),
                checked = form.full,
                onCheckedChange = { form.full = it },
                fillWidth = false,
            )
            CheckRow(
                label = tr("此前漏记", "Missed earlier"),
                checked = form.missed,
                onCheckedChange = { form.missed = it },
                fillWidth = false,
            )
            if (form.hasFuel) {
                Spacer(modifier = Modifier.weight(1f))
                CompactFuelGradeDropdown(
                    form = form,
                    modifier = Modifier.width(86.dp),
                )
            }
        }
    }
}

/** 紧凑燃油标号下拉（方案 3：靠右，不占整行）。 */
@Composable
private fun CompactFuelGradeDropdown(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = MileNoteShapes.medium,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(
                // 插混：下拉兼作「加油 / 充电」切换（选中「充电」后能量字段变成充电量 / 电价）。
                text = if (form.charging && form.chargeOption) tr("充电", "Charge") else form.fuelGrade,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = MileNoteIcons.ChevronDown,
                contentDescription = if (form.chargeOption) tr("燃油标号或充电", "Fuel grade or charging") else tr("燃油标号", "Fuel grade"),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FuelGradeOptions.forEach { grade ->
                DropdownMenuItem(
                    text = { Text(fuelGradeLabel(grade)) },
                    onClick = {
                        form.switchChargingMode(false)
                        form.fuelGrade = grade
                        expanded = false
                    },
                    trailingIcon = if (!form.charging && form.fuelGrade == grade) {
                        {
                            Icon(
                                imageVector = MileNoteIcons.Check,
                                contentDescription = tr("已选中", "Selected"),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            if (form.chargeOption) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(tr("充电", "Charge")) },
                    onClick = {
                        form.switchChargingMode(true)
                        expanded = false
                    },
                    trailingIcon = if (form.charging) {
                        {
                            Icon(
                                imageVector = MileNoteIcons.Check,
                                contentDescription = tr("已选中", "Selected"),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 历史手填油耗：油耗 + 行驶里程（仅编辑 `kind == "manual"` 记录时显示）。 */
@Composable
internal fun ManualFuelFields(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            value = form.rate,
            onValueChange = { form.rate = it },
            label = tr("油耗 *", "Fuel economy *"),
            suffix = Units.consumption,
            keyboardType = KeyboardType.Decimal,
            leadingIcon = MileNoteIcons.Fuel,
        )
        FormField(
            value = form.distance,
            onValueChange = { form.distance = it },
            label = tr("本次行驶里程（用于平均油耗）", "Distance this time (for fuel economy)"),
            suffix = Units.distanceUnit,
            keyboardType = KeyboardType.Decimal,
        )
        Text(
            text = tr("不填行驶里程时只保存油耗，不参与综合平均。", "If distance is left empty, only the fuel economy value is saved and it is excluded from the average."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 项目多选区（保养 / 维修 / 汽车美容 / 保险共用）：标题 + 可多选 chips + 自定义项目输入。
 *
 * @param title 区块标题（服务类带 `*`，保险为「保险项目（可多选）」且不强制）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectPickerSection(
    form: RecordEditorState,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            form.projectOptions.forEach { project ->
                val selected = form.projects.contains(project)
                FilterChip(
                    selected = selected,
                    onClick = { form.toggleProject(project) },
                    label = { Text(DataLabels.serviceItem(project), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = MileNoteIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = form.newProjects,
                onValueChange = { form.newProjects = it.take(100) },
                modifier = Modifier.weight(1f),
                label = { Text(tr("新增其他项目", "Add other items"), style = MaterialTheme.typography.bodyMedium) },
                placeholder = {
                    Text(tr("多个用顿号分隔", "Separate multiple items with commas"), style = MaterialTheme.typography.bodyMedium)
                },
                singleLine = true,
                shape = MileNoteShapes.medium,
            )
            Button(
                onClick = { form.addCustomProjects() },
                enabled = form.newProjects.isNotBlank(),
                shape = MileNoteShapeTokens.full,
                modifier = Modifier.height(56.dp),
            ) {
                Text(tr("添加", "Add"))
            }
        }
        Text(
            text = tr("保存记录后，新增项目会留在本车的可选列表中。", "New items stay in this vehicle's selectable list after saving."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 服务类分组（保养 / 维修 / 汽车美容）：地点 + 项目多选（必选）。 */
@Composable
internal fun ServiceFields(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            value = form.location,
            onValueChange = { form.location = it.take(200) },
            label = tr("地点 / 门店（选填）", "Location / shop (optional)"),
            placeholder = tr("例如：4S 店 / 修理厂", "e.g. 4S dealership / repair shop"),
            leadingIcon = MileNoteIcons.Location,
        )
        ProjectPickerSection(
            form = form,
            title = tr(
                "%s项目 *（可多选）",
                "%s items * (multi-select)",
                DataLabels.category(form.effectiveCategory, form.energyProfile),
            ),
        )
    }
}

/** 保险分组：只有项目多选（可选），没有地点/保养提醒/金额 0 等逻辑。 */
@Composable
internal fun InsuranceFields(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    ProjectPickerSection(
        form = form,
        title = tr("保险项目（可多选）", "Insurance items (multi-select)"),
        modifier = modifier,
    )
}

/** 备注：多行、最多 1000 字（与 Web `maxlength` 一致）。 */
@Composable
internal fun NoteField(
    form: RecordEditorState,
    modifier: Modifier = Modifier,
) {
    FormField(
        value = form.note,
        onValueChange = { form.note = it.take(1000) },
        label = tr("备注（选填）", "Note (optional)"),
        placeholder = tr("加油站、保养项目，或路上的小事…", "Gas station, service items, or anything else from the road…"),
        singleLine = false,
        minLines = 3,
        supportingText = "${form.note.length}/1000",
        modifier = modifier,
    )
}

/** 整行可点的复选行（48dp 触控，Web `.check` 的 M3 版本）。 */
@Composable
internal fun CheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
) {
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 燃油标号展示文案（柴油补全为「0# 柴油」）。 */
private fun fuelGradeLabel(grade: String): String = if (grade == "0#") tr("0# 柴油", "0# diesel") else grade
