@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.milenote.android.domain.calc.CategoryConfig
import com.milenote.android.domain.calc.PROJECT_CATEGORIES
import com.milenote.android.domain.calc.serviceProjectGroups
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.powertrainKey
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
 * 项目库分组：优先使用 domain 的 [PROJECT_CATEGORIES]（与 `serviceProjectGroups` 同步），
 * 按固定顺序展示；domain 尚未提供时回退到本地四组。
 */
private val ProjectCategories: List<String> =
    ProjectCategoryOrder.filter { it in PROJECT_CATEGORIES }.ifEmpty { ProjectCategoryOrder }

/** 保险默认项目（domain 尚未提供「保险」组时的兜底）。 */
private val FallbackInsuranceProjects: List<String> = listOf("交强险", "车损险", "三者险")

/**
 * 取某分类的内置项目：优先 domain（按动力类型：燃油/HEV 12 项 / PHEV 13 项 / 纯电 6 项，
 * 首保不参与项目库），其次本地兜底。
 */
private fun builtinProjects(category: String, powertrain: String?): List<String> =
    serviceProjectGroups(powertrain).firstOrNull { it.title == category }?.projects
        ?: if (category == "保险") FallbackInsuranceProjects else emptyList()

/** 分类显示名：有账本时走 `DataLabels.categoryFor`（改名 → 能量档位 → 英文表）。 */
private fun categoryDisplay(ledger: Ledger?, name: String, profile: EnergyProfile): String =
    if (ledger != null) DataLabels.categoryFor(ledger, name, profile) else DataLabels.category(name, profile)

/** 分类的「默认显示名」（未改名时的样子），用于「原名：X」提示。 */
private fun categoryDefaultDisplay(name: String, profile: EnergyProfile): String =
    DataLabels.category(name, profile)

/** 项目显示名：有账本时走 `DataLabels.projectFor`（改名 → 英文表）。 */
private fun projectDisplay(ledger: Ledger?, name: String): String =
    if (ledger != null) DataLabels.projectFor(ledger, name) else DataLabels.serviceItem(name)

/** 项目库说明（页面底部注释统一口径）。 */
private fun projectLibraryNote(): String =
    tr(
        "点按项目可改名或删除；项目用于记账时快速多选，改名与删除都不会修改已有记录里的项目文字。",
        "Tap an item to rename or delete it. Items are quick multi-select choices when recording; renaming or deleting never changes the text saved in existing records.",
    )

/**
 * 费用分类与项目（设置 → 该子页的页签内容）。
 *
 * 由调用方在页面顶部固定「当前车辆」提示与 [tab] 页签；本组件只渲染当前页签的可滚动内容：
 * - `tab == 0`：费用分类（内置 9 类可改名、非受保护可隐藏；自定义分类增删；已隐藏可恢复）；
 * - `tab == 1`：项目库（保养/维修/汽车美容/保险四组，内置 + 自定义都可改名/隐藏；已隐藏可恢复）。
 *
 * 语义（用户 2026-11-10 确认）：**只改显示名与新建候选，绝不改写历史记录的分类值/项目文字**。
 * 分类与项目都按车辆隔离（存于当前 [Ledger]）。
 */
@Composable
internal fun ExpenseCategoriesPage(
    tab: Int,
    ledger: Ledger?,
    themeId: String,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onRenameCategory: (old: String, new: String) -> Unit,
    onHideCategory: (String) -> Unit,
    onUnhideCategory: (String) -> Unit,
    onResetCategoryLabel: (String) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRenameProject: (old: String, new: String) -> Unit,
    onHideProject: (category: String, name: String) -> Unit,
    onUnhideProject: (String) -> Unit,
    onResetProjectLabel: (String) -> Unit,
) {
    val powertrain = ledger?.vehicle?.powertrainKey
    val profile = ledger?.vehicle?.energyProfile ?: EnergyProfile.FUEL_ONLY
    val customCategories = remember(ledger?.customCategories) {
        ledger?.customCategories.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    val projectsByCategory = ledger?.serviceProjectsByCategory.orEmpty()
    val hiddenCategories = remember(ledger?.hiddenCategories) {
        ledger?.hiddenCategories.orEmpty()
            .map { Categories.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    val hiddenProjects = remember(ledger?.hiddenServiceProjects) {
        ledger?.hiddenServiceProjects.orEmpty().mapValues { (_, v) -> v.toSet() }
    }

    // 当前打开的新增 / 改名弹层；null 表示关闭。
    var nameTarget by remember { mutableStateOf<NameTarget?>(null) }

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
                        ledger = ledger,
                        powertrain = powertrain,
                        profile = profile,
                        projectsByCategory = projectsByCategory,
                        hiddenProjects = hiddenProjects,
                        onRequestDialog = { nameTarget = it },
                        onAddProject = onAddProject,
                        onRenameProject = onRenameProject,
                        onHideProject = onHideProject,
                        onResetProjectLabel = onResetProjectLabel,
                    )
                } else {
                    CategoriesCard(
                        ledger = ledger,
                        profile = profile,
                        themeId = themeId,
                        customCategories = customCategories,
                        hidden = hiddenCategories,
                        onRequestDialog = { nameTarget = it },
                        onRemoveCategory = onRemoveCategory,
                        onAddCategory = onAddCategory,
                        onRenameCategory = onRenameCategory,
                        onHideCategory = onHideCategory,
                        onResetCategoryLabel = onResetCategoryLabel,
                    )
                }
            }
            if (tab == 0 && hiddenCategories.isNotEmpty()) {
                item(key = "hidden-categories") {
                    HiddenCategoriesCard(
                        ledger = ledger,
                        profile = profile,
                        themeId = themeId,
                        hidden = hiddenCategories,
                        onUnhideCategory = onUnhideCategory,
                    )
                }
            }
            if (tab == 1 && hiddenProjects.values.any { it.isNotEmpty() }) {
                item(key = "hidden-projects") {
                    HiddenProjectsCard(
                        ledger = ledger,
                        profile = profile,
                        hiddenProjects = hiddenProjects,
                        onUnhideProject = onUnhideProject,
                    )
                }
            }
        }

        nameTarget?.let { target ->
            NameDialog(
                target = target,
                onDismiss = { nameTarget = null },
            )
        }
    }
}

/**
 * 费用分类页签：内置分类（可改名、非受保护可隐藏）+ 自定义分类（可删）+ 新增入口。
 *
 * 被隐藏的内置分类不在这里显示，而是进入下方「已隐藏（可恢复）」区。
 */
@Composable
private fun CategoriesCard(
    ledger: Ledger?,
    profile: EnergyProfile,
    themeId: String,
    customCategories: List<String>,
    hidden: Set<String>,
    onRequestDialog: (NameTarget) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (old: String, new: String) -> Unit,
    onHideCategory: (String) -> Unit,
    onResetCategoryLabel: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = ledger?.categoryLabels.orEmpty()
    val visibleBuiltin = Categories.ALL.filter { it !in hidden }
    val visibleCustom = customCategories.filter { Categories.normalize(it) !in hidden }
    val existingNames = (Categories.ALL + customCategories + hidden).toSet()
    SectionCard {
        Text(
            text = tr(
                "内置 %d 类可改名；除油费、保险、保养、维修外可删除。改名与删除只影响显示名和记账候选，已有记录的分类与金额不变。",
                "%d built-in categories can be renamed; all except Fuel, Insurance, Maintenance and Repairs can be removed. Renaming or removing only changes the displayed name and picker choices — existing records keep their category and amount.",
                Categories.ALL.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        visibleBuiltin.forEachIndexed { index, name ->
            if (index > 0) CategoryDivider()
            val key = Categories.normalize(name)
            val color = parseCategoryColor(Categories.color(themeId, key), scheme.primary)
            val icon = MileNoteIcons.Category.getOrNull(Categories.ALL.indexOf(key))
            BuiltinCategoryRow(
                displayName = categoryDisplay(ledger, key, profile),
                originalName = categoryDefaultDisplay(key, profile),
                renamed = labels[key] != null,
                color = color,
                icon = icon,
                protected = CategoryConfig.isProtected(key),
                onRename = {
                    onRequestDialog(
                        NameTarget(
                            title = tr("重命名费用分类", "Rename category"),
                            label = tr("显示名", "Display name"),
                            placeholder = tr("例如：充电开销", "e.g. Charging costs"),
                            initial = labels[key].orEmpty(),
                            existing = existingNames - key,
                            confirmLabel = tr("保存", "Save"),
                            onReset = if (labels[key] != null) {
                                { onResetCategoryLabel(key) }
                            } else {
                                null
                            },
                        ) { value -> onRenameCategory(key, value) },
                    )
                },
                onHide = if (CategoryConfig.isProtected(key)) null else ({ onHideCategory(key) }),
            )
        }

        visibleCustom.forEach { name ->
            CategoryDivider()
            CustomCategoryRow(
                displayName = categoryDisplay(ledger, name, profile),
                color = parseCategoryColor(Categories.color(themeId, name), scheme.primary),
                onRemove = { onRemoveCategory(name) },
            )
        }

        Spacer(modifier = Modifier.size(12.dp))
        FilledTonalButton(
            onClick = {
                onRequestDialog(
                    NameTarget(
                        title = tr("新增费用分类", "Add category"),
                        label = tr("分类名称", "Category name"),
                        placeholder = tr("例如：充电费、洗车卡", "e.g. Charging, Car wash card"),
                        existing = existingNames,
                    ) { value -> onAddCategory(value) },
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
            Spacer(modifier = Modifier.size(6.dp))
            Text(tr("新增费用分类", "Add category"))
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = tr(
                "自定义分类删除后只从记账候选里移除；历史记录的分类与金额始终不受影响。",
                "Removing a custom category only drops it from the picker; existing records and amounts are never changed.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** 项目库页签：四组项目，内置 + 自定义均可改名 / 隐藏 + 分组新增。 */
@Composable
private fun ProjectsCard(
    ledger: Ledger?,
    powertrain: String?,
    profile: EnergyProfile,
    projectsByCategory: Map<String, List<String>>,
    hiddenProjects: Map<String, Set<String>>,
    onRequestDialog: (NameTarget) -> Unit,
    onAddProject: (category: String, name: String) -> Unit,
    onRenameProject: (old: String, new: String) -> Unit,
    onHideProject: (category: String, name: String) -> Unit,
    onResetProjectLabel: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = ledger?.serviceProjectLabels.orEmpty()
    SectionCard {
        Text(
            text = tr(
                "记账时按分类快速多选，新增只影响当前车辆；点按项目可改名或删除。",
                "Quick multi-select choices per category when recording; additions affect only this vehicle. Tap an item to rename or delete it.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        ProjectCategories.forEachIndexed { index, category ->
            if (index > 0) CategoryDivider()
            val builtin = builtinProjects(category, powertrain)
            val hiddenHere = hiddenProjects[category].orEmpty()
            val allRaw = (builtin + projectsByCategory[category].orEmpty())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val visible = allRaw.filter { it !in hiddenHere }
            ProjectGroupBlock(
                title = categoryDisplay(ledger, category, profile),
                builtinCount = visible.count { it in builtin },
                customCount = visible.count { it !in builtin },
                projects = visible,
                builtin = builtin.toSet(),
                labels = labels,
                ledger = ledger,
                onAdd = {
                    onRequestDialog(
                        NameTarget(
                            title = tr("新增%s项目", "Add %s item", categoryDisplay(ledger, category, profile)),
                            label = tr("项目名称", "Item name"),
                            placeholder = when (category) {
                                "保险" -> tr("例如：划痕险、玻璃险", "e.g. Scratch coverage, Glass coverage")
                                "维修" -> tr("例如：四轮定位", "e.g. Wheel alignment")
                                "汽车美容" -> tr("例如：内饰消毒", "e.g. Interior sanitizing")
                                else -> tr("例如：正时皮带检查", "e.g. Timing belt inspection")
                            },
                            existing = allRaw.toSet(),
                        ) { value -> onAddProject(category, value) },
                    )
                },
                onRename = { name ->
                    onRequestDialog(
                        NameTarget(
                            title = tr("重命名项目", "Rename item"),
                            label = tr("显示名", "Display name"),
                            placeholder = tr("例如：发动机机油与机滤", "e.g. Engine oil & filter"),
                            initial = labels[name].orEmpty(),
                            existing = (allRaw - name).toSet(),
                            confirmLabel = tr("保存", "Save"),
                            onReset = if (labels[name] != null) {
                                { onResetProjectLabel(name) }
                            } else {
                                null
                            },
                        ) { value -> onRenameProject(name, value) },
                    )
                },
                onHide = { name -> onHideProject(category, name) },
                onResetLabel = { name -> onResetProjectLabel(name) },
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

/**
 * 已隐藏分类（可恢复）：被隐藏的内置分类，以及历史数据里被隐藏过的自定义分类。
 *
 * 恢复只回到候选与显示；历史记录的分类值一直是原值，无需也不会被改写。
 */
@Composable
private fun HiddenCategoriesCard(
    ledger: Ledger?,
    profile: EnergyProfile,
    themeId: String,
    hidden: Set<String>,
    onUnhideCategory: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = ledger?.categoryLabels.orEmpty()
    SectionCard {
        Text(
            text = tr("已隐藏（可恢复）", "Hidden (restorable)"),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = tr(
                "隐藏只影响记账候选；历史记录里的分类保持原样。点「恢复」可重新出现在记账分类里。",
                "Hiding only affects the picker; categories in existing records stay as they are. Tap Restore to bring one back.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        val ordered = Categories.ALL.filter { it in hidden } + hidden.filter { it !in Categories.ALL }
        ordered.forEachIndexed { index, name ->
            if (index > 0) CategoryDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MileNoteDimens.TouchTarget)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            parseCategoryColor(Categories.color(themeId, name), scheme.primary)
                                .copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MileNoteIcons.Visibility,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = categoryDisplay(ledger, name, profile),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (labels[Categories.normalize(name)] != null) {
                        Text(
                            text = tr(
                                "原名：%s",
                                "Original: %s",
                                categoryDefaultDisplay(Categories.normalize(name), profile),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { onUnhideCategory(name) },
                    modifier = Modifier.heightIn(min = MileNoteDimens.TouchTarget),
                ) {
                    Text(tr("恢复", "Restore"))
                }
            }
        }
    }
}

/** 已隐藏项目（可恢复）：按分类分组展示，恢复只回到候选与显示，不改写历史记录。 */
@Composable
private fun HiddenProjectsCard(
    ledger: Ledger?,
    profile: EnergyProfile,
    hiddenProjects: Map<String, Set<String>>,
    onUnhideProject: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val labels = ledger?.serviceProjectLabels.orEmpty()
    SectionCard {
        Text(
            text = tr("已隐藏（可恢复）", "Hidden (restorable)"),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = tr(
                "删除项目只影响记账候选；历史记录里的项目文字保持原样。点项目可恢复。",
                "Deleting an item only affects the picker; item text in existing records stays as it is. Tap an item to restore it.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        val categories = (ProjectCategories + hiddenProjects.keys.filter { it !in ProjectCategories })
            .filter { hiddenProjects[it].orEmpty().isNotEmpty() }
        categories.forEachIndexed { index, category ->
            if (index > 0) CategoryDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = categoryDisplay(ledger, category, profile),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hiddenProjects[category].orEmpty().forEach { name ->
                        RestoreProjectChip(
                            display = projectDisplay(ledger, name),
                            renamed = labels[name] != null,
                            original = DataLabels.serviceItem(name),
                            onRestore = { onUnhideProject(name) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = tr("内置与自定义项目都可以恢复。", "Both built-in and custom items can be restored."),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 费用分类行
// ---------------------------------------------------------------------------------------------

/** 内置分类行：色点 + 类别图标 + 显示名（可改名）+「改名 / 删除」。受保护分类没有删除按钮。 */
@Composable
private fun BuiltinCategoryRow(
    displayName: String,
    originalName: String,
    renamed: Boolean,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    protected: Boolean,
    onRename: () -> Unit,
    onHide: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MileNoteDimens.TouchTarget)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
        Column(modifier = Modifier.weight(1f)) {
            Row(
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
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (protected) {
                    Text(
                        text = tr("必需", "Required"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MileNoteShapeTokens.full)
                            .background(scheme.surfaceContainerHigh)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (renamed) {
                Text(
                    text = tr("原名：%s", "Original: %s", originalName),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onRename,
            modifier = Modifier.size(MileNoteDimens.ButtonSmallHeight),
        ) {
            Icon(
                imageVector = MileNoteIcons.Edit,
                contentDescription = tr("重命名分类 %s", "Rename category %s", displayName),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (onHide != null) {
            IconButton(
                onClick = onHide,
                modifier = Modifier.size(MileNoteDimens.ButtonSmallHeight),
            ) {
                Icon(
                    imageVector = MileNoteIcons.Delete,
                    contentDescription = tr("删除分类 %s", "Delete category %s", displayName),
                    tint = scheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 自定义分类行：色点 + 图标 + 显示名 + 删除按钮（调用方已二次确认）。 */
@Composable
private fun CustomCategoryRow(
    displayName: String,
    color: Color,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MileNoteDimens.TouchTarget)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(MileNoteDimens.ButtonSmallHeight),
        ) {
            Icon(
                imageVector = MileNoteIcons.Close,
                contentDescription = tr("删除分类 %s", "Delete category %s", displayName),
                tint = scheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 项目库
// ---------------------------------------------------------------------------------------------

/** 一个分类的项目分组：项目 chips（可改名 / 删除）+ 新增按钮。 */
@Composable
private fun ProjectGroupBlock(
    title: String,
    builtinCount: Int,
    customCount: Int,
    projects: List<String>,
    builtin: Set<String>,
    labels: Map<String, String>,
    ledger: Ledger?,
    onAdd: () -> Unit,
    onRename: (String) -> Unit,
    onHide: (String) -> Unit,
    onResetLabel: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = tr(
                    "内置 %d · 自定义 %d",
                    "%d built-in · %d custom",
                    builtinCount,
                    customCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }

        if (projects.isEmpty()) {
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
                projects.forEach { name ->
                    ProjectChip(
                        display = projectDisplay(ledger, name),
                        custom = name !in builtin,
                        onRename = { onRename(name) },
                        onHide = { onHide(name) },
                        onResetLabel = if (labels[name] != null) {
                            { onResetLabel(name) }
                        } else {
                            null
                        },
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
 * 项目 chip：内置用中性容器、自定义用主色容器；点按弹出「改名 / 删除 / 恢复默认名」。
 *
 * 用菜单而不是 chip 内塞两个图标按钮，避免 8dp 间距下触控目标挤在一起。
 */
@Composable
private fun ProjectChip(
    display: String,
    custom: Boolean,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onResetLabel: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MileNoteShapeTokens.full,
            color = if (custom) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (custom) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = display,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = tr("项目操作", "Item actions"),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(tr("改名", "Rename")) },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(tr("删除", "Delete")) },
                onClick = {
                    expanded = false
                    onHide()
                },
            )
            if (onResetLabel != null) {
                DropdownMenuItem(
                    text = { Text(tr("恢复默认名", "Reset name")) },
                    onClick = {
                        expanded = false
                        onResetLabel()
                    },
                )
            }
        }
    }
}

/** 已隐藏项目 chip：点按恢复。 */
@Composable
private fun RestoreProjectChip(
    display: String,
    renamed: Boolean,
    original: String,
    onRestore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onRestore,
        shape = MileNoteShapeTokens.full,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = MileNoteIcons.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = display,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (renamed) {
                Text(
                    text = tr("（原名 %s）", "(was %s)", original),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 新增 / 改名弹层
// ---------------------------------------------------------------------------------------------

/**
 * 新增或改名的单字段弹层目标。
 *
 * @param initial 弹层预填值（改名为原显示名，新增为空）。
 * @param existing 校验用的已有名称集合（改名时排除自身原始值）。
 * @param onReset 非空时底栏多一个「恢复默认名」按钮（仅已改名项）。
 */
private class NameTarget(
    val title: String,
    val label: String,
    val placeholder: String = "",
    val initial: String = "",
    val existing: Set<String> = emptySet(),
    val confirmLabel: String = tr("添加", "Add"),
    val onReset: (() -> Unit)? = null,
    val onConfirm: (String) -> Unit,
)

/** 单字段名称弹层：去空白、去重、长度限制 30 字，错误就地提示。 */
@Composable
private fun NameDialog(
    target: NameTarget,
    onDismiss: () -> Unit,
) {
    var value by remember(target) { mutableStateOf(target.initial) }
    var error by remember(target) { mutableStateOf<String?>(null) }

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
                        "最多 30 个字符；只影响显示名与记账候选，已有记录不会被修改。",
                        "At most 30 characters; only changes the displayed name and picker choices — existing records are not modified.",
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
                            target.onConfirm(trimmed)
                            onDismiss()
                        }
                    }
                },
                shape = MileNoteShapeTokens.full,
            ) {
                Text(target.confirmLabel)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (target.onReset != null) {
                    TextButton(
                        onClick = {
                            target.onReset?.invoke()
                            onDismiss()
                        },
                    ) {
                        Text(tr("恢复默认名", "Reset name"))
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
            }
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
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
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
