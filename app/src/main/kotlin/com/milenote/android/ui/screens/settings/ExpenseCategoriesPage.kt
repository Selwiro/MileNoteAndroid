@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.PROJECT_CATEGORIES
import com.milenote.android.domain.calc.SERVICE_PROJECT_GROUPS
import com.milenote.android.domain.model.Ledger
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes

/** 项目库分组的展示顺序：保养 / 维修 / 汽车美容 / 保险。 */
private val ProjectCategoryOrder: List<String> = listOf("保养", "维修", "汽车美容", "保险")

/**
 * 项目库分组：优先使用 domain 的 [PROJECT_CATEGORIES]（与 [SERVICE_PROJECT_GROUPS] 同步），
 * 按固定顺序展示；domain 尚未提供时回退到本地四组。
 */
private val ProjectCategories: List<String> =
    ProjectCategoryOrder.filter { it in PROJECT_CATEGORIES }.ifEmpty { ProjectCategoryOrder }

/** 保险默认项目（domain 尚未提供「保险」组时的兜底）。 */
private val FallbackInsuranceProjects: List<String> = listOf("交强险", "车损险", "三者险")

/** 取某分类的内置项目：优先 domain，其次本地兜底。 */
private fun builtinProjects(category: String): List<String> =
    SERVICE_PROJECT_GROUPS.firstOrNull { it.title == category }?.projects
        ?: if (category == "保险") FallbackInsuranceProjects else emptyList()

/** 项目库说明（页面底部注释统一口径）。 */
private fun projectLibraryNote(): String =
    tr(
        "项目用于记账时快速多选；删除项目不会修改已有记录，历史记录里的项目文字保持原样。",
        "Items are quick multi-select choices when adding an entry; deleting one never changes existing records.",
    )

/**
 * 费用分类与项目（设置 → 该子页的页签内容）。
 *
 * 由调用方在页面顶部固定「当前车辆」提示与 [tab] 页签；本组件只渲染当前页签的可滚动内容：
 * - `tab == 0`：费用分类（内置 9 类只读 + 自定义分类增删）；
 * - `tab == 1`：项目库（保养/维修/汽车美容/保险四组，内置只读、自定义增删）。
 *
 * 分类与项目都按车辆隔离（存于当前 [Ledger]）。
 */
@Composable
internal fun ExpenseCategoriesPage(
    tab: Int,
    ledger: Ledger?,
    themeId: String,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRemoveProject: (category: String, name: String) -> Unit,
) {
    val customCategories = remember(ledger?.customCategories) {
        ledger?.customCategories.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val projectsByCategory = ledger?.serviceProjectsByCategory.orEmpty()

    // 当前打开的「新增分类 / 新增项目」弹层；null 表示关闭。
    var addTarget by remember { mutableStateOf<AddNameTarget?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MileNoteDimens.PageHorizontal,
                end = MileNoteDimens.PageHorizontal,
                top = 6.dp,
                bottom = MileNoteDimens.PageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap),
        ) {
            item(key = "tab-$tab") {
                if (tab == 1) {
                    ProjectsCard(
                        projectsByCategory = projectsByCategory,
                        onRequestAdd = { addTarget = it },
                        onAddProject = onAddProject,
                        onRemoveProject = onRemoveProject,
                    )
                } else {
                    CategoriesCard(
                        themeId = themeId,
                        customCategories = customCategories,
                        onRequestAdd = { addTarget = it },
                        onRemoveCategory = onRemoveCategory,
                        onAddCategory = onAddCategory,
                    )
                }
            }
        }

        addTarget?.let { target ->
            AddNameDialog(
                target = target,
                onDismiss = { addTarget = null },
            )
        }
    }
}

/** 费用分类页签：9 类内置分类（只读）+ 自定义分类（可删）+ 新增入口。 */
@Composable
private fun CategoriesCard(
    themeId: String,
    customCategories: List<String>,
    onRequestAdd: (AddNameTarget) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onAddCategory: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard {
        Text(
            text = tr(
                "内置 %d 类固定不变；自定义分类只影响记账时的可选项。",
                "%d built-in categories are fixed; custom categories only add choices when recording.",
                Categories.ALL.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Categories.ALL.forEachIndexed { index, name ->
            if (index > 0) CategoryDivider()
            BuiltinCategoryRow(
                name = name,
                color = parseCategoryColor(Categories.color(themeId, name), scheme.primary),
                icon = MileNoteIcons.Category.getOrNull(index),
            )
        }

        customCategories.forEach { name ->
            CategoryDivider()
            CustomCategoryRow(
                name = name,
                color = parseCategoryColor(Categories.color(themeId, name), scheme.primary),
                onRemove = { onRemoveCategory(name) },
            )
        }

        Spacer(modifier = Modifier.size(12.dp))
        FilledTonalButton(
            onClick = {
                onRequestAdd(
                    AddNameTarget(
                        title = tr("新增费用分类", "Add category"),
                        label = tr("分类名称", "Category name"),
                        placeholder = tr("例如：充电费、洗车卡", "e.g. Charging, Car wash card"),
                        existing = (Categories.ALL + customCategories).toSet(),
                        onAdd = { value -> onAddCategory(value) },
                    ),
                )
            },
            shape = MileNoteShapeTokens.full,
            modifier = Modifier.heightIn(min = MileNoteDimens.ButtonMinHeight),
        ) {
            Icon(
                imageVector = MileNoteIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(tr("新增费用分类", "Add category"))
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = tr(
                "内置分类不可删除；自定义分类删除后，已有记录的分类与金额不受影响。",
                "Built-in categories cannot be deleted; deleting a custom category never changes existing records or amounts.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** 项目库页签：四组项目，内置只读 + 自定义删除 + 分组新增。 */
@Composable
private fun ProjectsCard(
    projectsByCategory: Map<String, List<String>>,
    onRequestAdd: (AddNameTarget) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRemoveProject: (category: String, name: String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard {
        Text(
            text = tr(
                "记账时按分类快速多选，新增只影响当前车辆。",
                "Quick multi-select choices per category when recording; additions affect only this vehicle.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        ProjectCategories.forEachIndexed { index, category ->
            if (index > 0) CategoryDivider()
            ProjectGroupBlock(
                category = category,
                builtin = builtinProjects(category),
                custom = projectsByCategory[category].orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it !in builtinProjects(category) }
                    .distinct(),
                onAdd = {
                    val existing = (builtinProjects(category) + projectsByCategory[category].orEmpty())
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    onRequestAdd(
                        AddNameTarget(
                            title = tr("新增%s项目", "Add %s item", DataLabels.category(category)),
                            label = tr("项目名称", "Item name"),
                            placeholder = when (category) {
                                "保险" -> tr("例如：划痕险、玻璃险", "e.g. Scratch coverage, Glass coverage")
                                "维修" -> tr("例如：四轮定位", "e.g. Wheel alignment")
                                "汽车美容" -> tr("例如：内饰消毒", "e.g. Interior sanitizing")
                                else -> tr("例如：正时皮带检查", "e.g. Timing belt inspection")
                            },
                            existing = existing,
                            onAdd = { value -> onAddProject(category, value) },
                        ),
                    )
                },
                onRemove = { name -> onRemoveProject(category, name) },
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = projectLibraryNote(),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 费用分类行
// ---------------------------------------------------------------------------------------------

/** 内置分类行：色点 + 类别图标 + 名称 +「内置」小标（只读）。 */
@Composable
private fun BuiltinCategoryRow(
    name: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MileNoteDimens.TouchTarget)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon ?: MileNoteIcons.CategoryGoods,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = DataLabels.category(name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = tr("内置", "Built-in"),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MileNoteShapeTokens.full)
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 自定义分类行：色点 + 图标 + 名称 + 删除按钮（调用方已二次确认）。 */
@Composable
private fun CustomCategoryRow(
    name: String,
    color: Color,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MileNoteDimens.TouchTarget)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MileNoteIcons.CategoryGoods,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(MileNoteDimens.TouchTarget),
        ) {
            Icon(
                imageVector = MileNoteIcons.Close,
                contentDescription = tr("删除分类 %s", "Delete category %s", name),
                tint = scheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 项目库
// ---------------------------------------------------------------------------------------------

/** 一个分类的项目分组：内置 chips（只读）+ 自定义 chips（可删）+ 新增按钮。 */
@Composable
private fun ProjectGroupBlock(
    category: String,
    builtin: List<String>,
    custom: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = DataLabels.category(category),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = tr("内置 %d · 自定义 %d", "%d built-in · %d custom", builtin.size, custom.size),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }

        if (builtin.isEmpty() && custom.isEmpty()) {
            Text(
                text = tr("暂无项目，可点击下方新增。", "No items yet — add one below."),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                builtin.forEach { label ->
                    ProjectChip(label = label, removable = false)
                }
                custom.forEach { label ->
                    ProjectChip(
                        label = label,
                        removable = true,
                        onRemove = { onRemove(label) },
                    )
                }
            }
        }

        TextButton(
            onClick = onAdd,
            modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
        ) {
            Icon(
                imageVector = MileNoteIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(tr("新增项目", "Add item"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 项目 chip：内置项目用中性容器只读展示；自定义项目用主色容器并带删除按钮。
 *
 * 内置项目不可删除（不显示 ×），避免误删内置候选。
 */
@Composable
private fun ProjectChip(
    label: String,
    removable: Boolean,
    onRemove: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MileNoteShapeTokens.full,
        color = if (removable) scheme.primaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (removable) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = if (removable) 4.dp else 12.dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = DataLabels.serviceItem(label),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (removable) {
                IconButton(
                    onClick = { onRemove?.invoke() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = MileNoteIcons.Close,
                        contentDescription = tr("删除项目 %s", "Delete item %s", label),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 新增弹层
// ---------------------------------------------------------------------------------------------

/** 新增分类 / 新增项目的弹层目标（携带校验用的已有名称集合）。 */
private class AddNameTarget(
    val title: String,
    val label: String,
    val placeholder: String,
    val existing: Set<String>,
    val onAdd: (String) -> Unit,
)

/** 单字段新增弹层：去空白、去重、长度限制 30 字，错误就地提示。 */
@Composable
private fun AddNameDialog(
    target: AddNameTarget,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MileNoteShapes.extraLarge,
        title = { Text(target.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField(
                    value = value,
                    onValueChange = {
                        value = it.take(30)
                        error = null
                    },
                    label = target.label,
                    placeholder = target.placeholder,
                    isError = error != null,
                    supportingText = error,
                )
                Text(
                    text = tr(
                        "最多 30 个字符；仅影响记账时的可选项，已有记录不会被修改。",
                        "At most 30 characters; only affects choices when recording, existing records are not changed.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = value.trim()
                    when {
                        trimmed.isEmpty() -> error = tr("请填写名称", "Please enter a name")
                        trimmed in target.existing -> error = tr("已存在同名项", "An item with this name already exists")
                        else -> {
                            target.onAdd(trimmed)
                            onDismiss()
                        }
                    }
                },
                shape = MileNoteShapeTokens.full,
            ) {
                Text(tr("添加", "Add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------------------------

/** 分区内的细分隔线。 */
@Composable
private fun CategoryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 解析 Web 口径的分类色：`#RRGGBB` / `hsl(H S% L%)`；无法解析时回退 [fallback]。
 *
 * 内置分类返回 hex（来自 `Categories.color`），自定义分类返回 Web 的 hash → HSL 颜色，
 * 这样分类色点与 Web 版/图表在小色块上保持同一语义。
 */
private fun parseCategoryColor(value: String?, fallback: Color): Color {
    if (value.isNullOrBlank()) return fallback
    return try {
        when {
            value.startsWith("#") -> {
                val hex = value.removePrefix("#")
                when (hex.length) {
                    6 -> Color(0xFF000000L or hex.toLong(16))
                    8 -> Color(hex.toLong(16))
                    else -> fallback
                }
            }

            value.startsWith("hsl(") -> {
                val inner = value.removePrefix("hsl(").removeSuffix(")").trim()
                val parts = inner.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val hue = parts.getOrNull(0)?.toFloatOrNull() ?: return fallback
                val saturation = parts.getOrNull(1)?.removeSuffix("%")?.toFloatOrNull()?.div(100f) ?: 0.40f
                val lightness = parts.getOrNull(2)?.removeSuffix("%")?.toFloatOrNull()?.div(100f) ?: 0.56f
                Color.hsl(hue, saturation, lightness)
            }

            else -> fallback
        }
    } catch (t: Throwable) {
        fallback
    }
}
