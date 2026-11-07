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
 * 金额输入：大号 tabular 数字、`¥` 前缀、错误就地显示在下方。
 *
 * 打开页面时不自动聚焦（避免一进入就弹输入法）；用户点击输入框才弹键盘。
 *
 * 分类为油费时，尾部显示「拍照识别小票」相机按钮（OCR 入口从加油量字段移到这里，
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
                text = if (form.isManual) "费用金额 · 元（可选）" else "实付金额 · 元",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        prefix = {
            Text(text = "¥", style = MaterialTheme.typography.labelLarge)
        },
        isError = error != null,
        supportingText = error?.let {
            { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        },
        trailingIcon = if (form.isFuel && onOcrClick != null) {
            {
                IconButton(onClick = onOcrClick) {
                    Icon(
                        imageVector = MileNoteIcons.Camera,
                        contentDescription = "拍照识别小票",
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
            label = "日期",
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
                    onClickLabel = "选择日期",
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
                        label = if (isAdd) "新增分类" else name,
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
        label = "总里程（选填）",
        suffix = "公里",
        keyboardType = KeyboardType.Decimal,
        leadingIcon = MileNoteIcons.Road,
        modifier = modifier,
    )
}

/** 油费分组：燃油标号下拉 + 加油量 / 油价 + 应付 / 优惠 + 加满 / 漏记。 */
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
                value = form.liters,
                onValueChange = {
                    form.liters = it
                    form.onFuelFieldChanged("liters")
                },
                label = "加油量",
                suffix = "升",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = form.unitPrice,
                onValueChange = {
                    form.unitPrice = it
                    form.onFuelFieldChanged("unitPrice")
                },
                label = "油价",
                suffix = "元/升",
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
                label = "应付金额",
                suffix = "元",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = form.discount,
                onValueChange = {
                    form.discount = it
                    form.onFuelFieldChanged("discount")
                },
                label = "优惠金额",
                suffix = "元",
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
                label = "本次加满",
                checked = form.full,
                onCheckedChange = { form.full = it },
                fillWidth = false,
            )
            CheckRow(
                label = "此前漏记",
                checked = form.missed,
                onCheckedChange = { form.missed = it },
                fillWidth = false,
            )
            Spacer(modifier = Modifier.weight(1f))
            CompactFuelGradeDropdown(
                form = form,
                modifier = Modifier.width(78.dp),
            )
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
                text = form.fuelGrade,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = MileNoteIcons.ChevronDown,
                contentDescription = "燃油标号",
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
                        form.fuelGrade = grade
                        expanded = false
                    },
                    trailingIcon = if (form.fuelGrade == grade) {
                        {
                            Icon(
                                imageVector = MileNoteIcons.Check,
                                contentDescription = "已选中",
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
            label = "百公里油耗 *",
            suffix = "升/百公里",
            keyboardType = KeyboardType.Decimal,
            leadingIcon = MileNoteIcons.Fuel,
        )
        FormField(
            value = form.distance,
            onValueChange = { form.distance = it },
            label = "本次行驶里程（用于平均油耗）",
            suffix = "公里",
            keyboardType = KeyboardType.Decimal,
        )
        Text(
            text = "不填行驶里程时只保存油耗，不参与综合平均。",
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
                    label = { Text(project, style = MaterialTheme.typography.bodyMedium) },
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
                label = { Text("新增其他项目", style = MaterialTheme.typography.bodyMedium) },
                placeholder = {
                    Text("多个用顿号分隔", style = MaterialTheme.typography.bodyMedium)
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
                Text("添加")
            }
        }
        Text(
            text = "保存记录后，新增项目会留在本车的可选列表中。",
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
            label = "地点 / 门店（选填）",
            placeholder = "例如：4S 店 / 修理厂",
            leadingIcon = MileNoteIcons.Location,
        )
        ProjectPickerSection(
            form = form,
            title = "${form.effectiveCategory}项目 *（可多选）",
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
        title = "保险项目（可多选）",
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
        label = "备注（选填）",
        placeholder = "加油站、保养项目，或路上的小事…",
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
private fun fuelGradeLabel(grade: String): String = if (grade == "0#") "0# 柴油" else grade
