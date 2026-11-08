package com.milenote.android.ui.screens.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.PlanRow
import com.milenote.android.domain.calc.ReminderLevel
import com.milenote.android.domain.calc.ServiceProgress
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.groupReminders
import com.milenote.android.domain.calc.reminderState
import com.milenote.android.domain.calc.servicePlanRows
import com.milenote.android.domain.calc.serviceProgress
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.CategoryBadge
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MoneySize
import com.milenote.android.ui.components.MoneyText
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.SecondaryTabs
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import java.time.LocalDate

/**
 * 维修保养页（Web `remindersPage`）。
 *
 * 结构：
 * - 顶部「待办与记录 / 计划」两个固定页签（共用 [SecondaryTabs]）；
 * - 待办与记录：待办列表 + 扁平维修保养记录（右上角胶囊按维修 / 保养 / 汽车美容筛选）；
 * - 计划：一张平铺的保养计划表（不按状态分组，离到期越近越靠上、已完成的沉底），
 *   右上角「编辑计划」；点行看详情并完成。
 *
 * 只输出内容，不含 Scaffold / 顶栏 / 底栏；页边距 16dp，底部由外层 Scaffold 预留。
 * 弹层：计划行详情、完成并记录、提醒编辑、启用默认方案、全屏方案编辑。
 *
 * @param vm 全局 ViewModel（记录、提醒、方案）。
 * @param onNavigate 顶层路由跳转（保留以兼容导航签名；导入入口已移到设置页，本页不再使用）。
 */
@Composable
fun MaintenanceScreen(
    vm: MileNoteViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger = state.activeCar
    if (ledger == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingState(label = tr("正在加载账本…", "Loading ledger…"))
        }
        return
    }

    val today = Dates.today()
    val km = remember(ledger) { currentKm(ledger) }
    val groups = remember(ledger.reminders, today) { groupReminders(ledger.reminders, today) }
    val overdueCount = remember(groups.near, km, today) {
        groups.near.count { reminderState(it, km, today).level == ReminderLevel.OVERDUE }
    }
    val rows = remember(ledger, today, km) {
        val base = runCatching { servicePlanRows(ledger, today, km) }
            .getOrDefault(emptyList<PlanRow>())
        sortPlanRows(base, km, today)
    }
    val history = remember(ledger.records) {
        ledger.records
            .filter { it.category in SERVICE_RECORD_CATEGORIES }
            .sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.odometer ?: 0.0 })
    }
    val configured = remember(ledger) {
        ledger.servicePlanConfigured == true ||
            !ledger.servicePlan.isNullOrEmpty() ||
            ledger.reminders.any { it.source == "默认保养方案" }
    }

    val exportCalendar = rememberCalendarExportAction()
    var completeTarget by remember { mutableStateOf<Reminder?>(null) }
    var addReminder by remember { mutableStateOf(false) }
    var editReminder by remember { mutableStateOf<Reminder?>(null) }
    var planDetail by remember { mutableStateOf<PlanRow?>(null) }
    var showDefaultPlan by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var historyFilter by rememberSaveable { mutableStateOf("全部") }

    // 记录筛选：旧分类「维修保养」先归一成「保养」再比较，「全部」不过滤。
    val filteredHistory = remember(history, historyFilter) {
        if (historyFilter == "全部") {
            history
        } else {
            history.filter { Categories.normalize(it.category) == historyFilter }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SecondaryTabs(
            labels = listOf(tr("待办与记录", "Tasks & records"), tr("计划", "Plan")),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = MileNoteDimens.PageHorizontal,
                    end = MileNoteDimens.PageHorizontal,
                    top = MileNoteDimens.CardGap,
                    bottom = MileNoteDimens.PageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
            ) {
                item(key = "todo") {
                    SectionCard(
                        title = tr("待办事项", "To-dos"),
                        subtitle = if (overdueCount > 0) {
                            tr(
                                "%d 项 · %d 项已到期",
                                "%d items · %d overdue",
                                groups.near.size,
                                overdueCount,
                            )
                        } else {
                            tr(
                                "%d 项 · 当前 %s",
                                "%d items · current %s",
                                groups.near.size,
                                distanceText(km),
                            )
                        },
                        action = {
                            TextButton(onClick = { addReminder = true }) { Text(tr("添加提醒", "Add reminder")) }
                        },
                    ) {
                        if (groups.near.isEmpty()) {
                            PendingEmptyState(onAdd = { addReminder = true })
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                groups.near.forEachIndexed { index, reminder ->
                                    ReminderCard(
                                        reminder = reminder,
                                        km = km,
                                        today = today,
                                        onComplete = { completeTarget = it },
                                        onEdit = { editReminder = it },
                                    )
                                    if (index != groups.near.lastIndex) FlatDivider()
                                }
                            }
                        }
                    }
                }
                item(key = "history") {
                    SectionCard(
                        title = tr("维修保养记录", "Maintenance records"),
                        subtitle = tr(
                            "%d 次 · 当前 %s",
                            "%d entries · current %s",
                            filteredHistory.size,
                            distanceText(km),
                        ),
                        action = {
                            ServiceHistoryFilter(
                                filter = historyFilter,
                                onSelect = { historyFilter = it },
                            )
                        },
                    ) {
                        ServiceHistoryFlatList(
                            records = filteredHistory,
                            filterActive = historyFilter != "全部" && history.isNotEmpty(),
                            onEdit = { vm.openEditor(recordId = it.id) },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = MileNoteDimens.PageHorizontal,
                    end = MileNoteDimens.PageHorizontal,
                    top = MileNoteDimens.CardGap,
                    bottom = MileNoteDimens.PageBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(MileNoteDimens.CardGap),
            ) {
                item(key = "plan") {
                    SectionCard(
                        title = tr("保养计划", "Service plan"),
                        subtitle = tr(
                            "按最近一次保养记录计算下次到期",
                            "Next due date is based on the latest service record",
                        ),
                        action = {
                            TextButton(onClick = { vm.openServicePlanEditor() }) { Text(tr("编辑计划", "Edit plan")) }
                        },
                    ) {
                        if (!configured) {
                            EnableDefaultPlanGuide(
                                onEnableDefault = { showDefaultPlan = true },
                                onCustomize = { vm.openServicePlanEditor() },
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (rows.isEmpty()) {
                            Text(
                                text = tr(
                                    "暂无计划项目。启用默认方案或编辑自定义方案后，这里会显示完整周期。",
                                    "No plan items yet. Enable the default plan or edit a custom plan to " +
                                        "see the full schedule here.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            PlanTableHeader()
                            rows.forEachIndexed { index, row ->
                                PlanTableRow(
                                    row = row,
                                    km = km,
                                    today = today,
                                    onClick = { planDetail = row },
                                )
                                if (index != rows.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 13.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = tr("点项目行查看详情、完成并记录", "Tap an item to see details and log a service"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 弹层 ----
    planDetail?.let { row ->
        PlanRowDetailSheet(
            row = row,
            km = km,
            today = today,
            onDismiss = { planDetail = null },
            onComplete = { reminder ->
                planDetail = null
                completeTarget = reminder
            },
        )
    }
    completeTarget?.let { reminder ->
        CompleteServiceDialog(
            ledger = ledger,
            reminder = reminder,
            today = today,
            currentKm = km,
            onDismiss = { completeTarget = null },
            onConfirm = { record ->
                vm.completeService(reminder, record)
                completeTarget = null
            },
        )
    }
    if (addReminder || editReminder != null) {
        ReminderEditorDialog(
            reminder = editReminder,
            onExportCalendar = exportCalendar,
            onDismiss = {
                addReminder = false
                editReminder = null
            },
            onSave = { reminder ->
                vm.saveReminder(reminder)
                addReminder = false
                editReminder = null
            },
            onDelete = editReminder?.let { target ->
                {
                    vm.deleteReminder(target)
                    editReminder = null
                }
            },
        )
    }
    if (showDefaultPlan) {
        DefaultServicePlanDialog(
            ledger = ledger,
            onDismiss = { showDefaultPlan = false },
            onConfirm = { baseDate, baseKm, includeFirst ->
                vm.useDefaultServicePlan(baseDate, baseKm, includeFirst)
                showDefaultPlan = false
            },
        )
    }
    if (state.showServicePlanEditor) {
        ServicePlanEditorDialog(
            ledger = ledger,
            onDismiss = { vm.closeServicePlanEditor() },
            onSave = { entries, baseDate, baseKm, includeFirst ->
                vm.saveServicePlan(entries, baseDate, baseKm, includeFirst)
            },
        )
    }
}

@Composable
private fun ServiceHistoryFlatList(
    records: List<Record>,
    filterActive: Boolean,
    onEdit: (Record) -> Unit,
) {
    if (records.isEmpty()) {
        Text(
            text = if (filterActive) {
                tr(
                    "没有符合条件的记录。点右上角「筛选」可切回全部。",
                    "No matching records. Tap Filter at the top right to show all.",
                )
            } else {
                tr(
                    "还没有维修保养记录。完成维修、保养或汽车美容后，记下日期、地点、里程、项目和费用。",
                    "No maintenance records yet. After a repair, service, or detailing job, log the " +
                        "date, location, mileage, items, and cost.",
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        records.forEachIndexed { index, record ->
            ServiceHistoryCard(
                record = record,
                onClick = { onEdit(record) },
            )
            if (index != records.lastIndex) FlatDivider()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 维修保养记录筛选
// ---------------------------------------------------------------------------------------------

/** 记录卡筛选选项（「全部」不过滤；旧分类「维修保养」先归一成「保养」再比较）。 */
private val SERVICE_HISTORY_FILTERS = listOf("全部", "维修", "保养", "汽车美容")

/** 记录卡右上角筛选胶囊 + 下拉菜单（全部 / 维修 / 保养 / 汽车美容）。 */
@Composable
private fun ServiceHistoryFilter(filter: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (filter == "全部") tr("筛选", "Filter") else DataLabels.category(filter),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(
                    imageVector = MileNoteIcons.ChevronDown,
                    contentDescription = tr("筛选维修保养记录", "Filter maintenance records"),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SERVICE_HISTORY_FILTERS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(if (option == "全部") tr("全部", "All") else DataLabels.category(option))
                    },
                    trailingIcon = historyFilterCheckIcon(selected = option == filter),
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 筛选菜单选中勾。 */
@Composable
private fun historyFilterCheckIcon(selected: Boolean): (@Composable () -> Unit)? =
    if (!selected) {
        null
    } else {
        {
            Icon(
                imageVector = MileNoteIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }

// ---------------------------------------------------------------------------------------------
// 待办卡片
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReminderCard(
    reminder: Reminder,
    km: Double,
    today: LocalDate,
    onComplete: (Reminder) -> Unit,
    onEdit: (Reminder) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMileNoteSemanticColors.current
    val state = reminderState(reminder, km, today)
    val progress = serviceProgress(reminder, km, today)
    val railColor = when (state.level) {
        ReminderLevel.OVERDUE -> scheme.error
        ReminderLevel.SOON -> semantic.warning.content
        ReminderLevel.DONE -> semantic.success.content
        ReminderLevel.NORMAL -> scheme.primary
    }
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(railColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CategoryBadge(categoryName = inferServiceCategory(reminder))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = DataLabels.serviceItem(reminder.title), style = MaterialTheme.typography.titleMedium)
                    // 日期行 / 里程行各自单行省略：不换行，超宽时在卡片右侧截断。
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Format.reminderDeadline(reminder, state, Units.distanceUnit).split('\n').forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                StatusPill(
                    text = reminderLevelLabel(state.level),
                    status = reminderLevelStatus(state.level),
                )
            }
            if (progress != null) {
                ServiceProgressBar(progress = progress)
            }
            reminderNote(reminder)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!reminder.done) {
                    FilledTonalButton(
                        onClick = { onComplete(reminder) },
                        modifier = Modifier.height(MileNoteDimens.ButtonSmallHeight),
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr("完成并记录", "Complete & log"), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                IconButton(onClick = { onEdit(reminder) }) {
                    Icon(
                        imageVector = MileNoteIcons.Edit,
                        contentDescription = tr("编辑", "Edit"),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingEmptyState(onAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MileNoteIcons.Bell,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = scheme.onSurfaceVariant,
        )
        Text(text = tr("未来一年内暂无待办", "No to-dos in the next 12 months"), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onAdd) {
            Text(tr("＋ 添加维修保养提醒", "＋ Add maintenance reminder"))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 保养计划表
// ---------------------------------------------------------------------------------------------

/**
 * 计划行排序（用户口径：离保养时间越近的越靠上，越远的越靠下，完成的在最底下）。
 *
 * 分组顺序：
 * 1. 有本轮待做提醒（`current != null`）的行——其中已到期（[ReminderLevel.OVERDUE]）最先，
 *    其余按到期日期升序（无日期视为 `"9999"` 排后）、到期里程升序、标题；
 * 2. 没有待做提醒的未启用 / 待补充行；
 * 3. 本轮已完成（`status == "done"`）的行，沉底。
 */
private fun sortPlanRows(rows: List<PlanRow>, km: Double, today: LocalDate): List<PlanRow> =
    rows.sortedWith(
        compareBy<PlanRow> { row ->
            when {
                row.current != null -> 0
                row.status == "done" -> 2
                else -> 1
            }
        }
            .thenBy { row ->
                // 待做行里「已到期」最优先，其余按离到期的远近排。
                // `row.current` 来自 domain 模块，跨模块不能智能转换，先取局部变量。
                val current = row.current
                if (current != null && reminderState(current, km, today).level == ReminderLevel.OVERDUE) {
                    0
                } else {
                    1
                }
            }
            .thenBy { row -> row.current?.dueDate?.takeIf { it.isNotEmpty() } ?: "9999" }
            .thenBy { row -> row.current?.dueKm ?: Double.MAX_VALUE }
            .thenBy { row -> row.title },
    )

@Composable
private fun EnableDefaultPlanGuide(onEnableDefault: () -> Unit, onCustomize: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(MileNoteDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = tr("尚未启用保养方案", "Service plan not enabled"), style = MaterialTheme.typography.titleMedium)
            Text(
                text = tr(
                    "可一键启用默认 12 项周期（首保 + 11 项常规保养），也可以逐项自定义里程与时长。",
                    "Enable the 12 default intervals (first service + 11 routine services) in one tap, " +
                        "or customize mileage and duration for each item.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onEnableDefault) {
                    Text(tr("启用默认方案", "Enable default plan"))
                }
                TextButton(
                    onClick = onCustomize,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(tr("自己设置", "Set up manually"))
                }
            }
        }
    }
}

/** 计划表列宽：周期 / 日期里程两列定宽，项目列自适应。 */
private val PlanCycleColumnWidth = 84.dp
private val PlanDateColumnWidth = 96.dp
private val PlanTableRowHeight = 48.dp

@Composable
private fun PlanTableHeader() {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = tr("项目", "Item"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = tr("周期", "Interval"),
                modifier = Modifier.width(PlanCycleColumnWidth),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = tr("日期 / 里程", "Date / mileage"),
                modifier = Modifier.width(PlanDateColumnWidth),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(18.dp))
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/**
 * 计划表一行：不按状态分组，统一「日期在上、公里在下」。
 * 没有日期时公里在单元格内垂直居中；左侧色点 + 细进度条保留状态含义。
 */
@Composable
private fun PlanTableRow(
    row: PlanRow,
    km: Double,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalMileNoteSemanticColors.current
    val current = row.current
    val progress = current?.let { serviceProgress(it, km, today) }
    val level = current?.let { reminderState(it, km, today).level }
    val accent = when {
        row.status == "overdue" -> scheme.error
        row.status == "done" -> semantic.success.content
        row.status == "unconfigured" -> scheme.outline
        level == ReminderLevel.SOON -> semantic.warning.content
        else -> scheme.primary
    }
    val dateText = when {
        current != null -> current.dueDate?.takeIf { it.isNotEmpty() }?.let { Format.dateDots(it) }
        row.status == "done" && row.completedDate.isNotEmpty() -> Format.dateDots(row.completedDate)
        else -> null
    }
    val kmText = when {
        current != null -> current.dueKm?.let { distanceText(it) }
        row.status == "done" -> row.completedKm?.let { distanceText(it) }
        else -> null
    }
    val cycleKmText = row.intervalKm?.takeIf { it != 0.0 }?.let { Format.km(it, Units.distanceUnit) }
    val cycleMonthText = row.intervalMonths?.takeIf { it != 0 }?.let { months ->
        val years = months / 12
        if (months % 12 == 0) {
            if (years == 1) tr("%d 年", "%d year", years) else tr("%d 年", "%d years", years)
        } else {
            if (months == 1) tr("%d 个月", "%d month", months) else tr("%d 个月", "%d months", months)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PlanTableRowHeight)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accent, CircleShape),
                )
                Text(
                    text = DataLabels.serviceItem(row.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.status == "unconfigured") scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest),
            ) {
                val fraction = when {
                    progress != null -> (progress.percent / 100.0).toFloat().coerceIn(0f, 1f)
                    row.status == "done" -> 1f
                    else -> 0f
                }
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(accent),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(PlanCycleColumnWidth),
            verticalArrangement = Arrangement.Center,
        ) {
            if (cycleKmText != null) {
                Text(
                    text = cycleKmText,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (cycleMonthText != null) {
                Text(
                    text = cycleMonthText,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (cycleKmText == null && cycleMonthText == null) {
                Text(
                    text = tr("单次", "One-off"),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(PlanDateColumnWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                if (dateText != null) {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.status == "overdue") scheme.error else scheme.onSurface,
                        maxLines = 1,
                    )
                }
                if (kmText != null) {
                    Text(
                        text = kmText,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (dateText == null && kmText == null) {
                    Text(
                        text = if (row.status == "unconfigured") {
                            tr("未设置", "Not set")
                        } else {
                            tr("待补充", "To be filled")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Icon(
            imageVector = MileNoteIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** 计划行详情弹层：周期、下次日期/里程、进度，以及完成入口（编辑计划在计划卡右上角）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanRowDetailSheet(
    row: PlanRow,
    km: Double,
    today: LocalDate,
    onDismiss: () -> Unit,
    onComplete: (Reminder) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val current = row.current
    val progress = current?.let { serviceProgress(it, km, today) }
    val dateText = when {
        current != null -> current.dueDate?.takeIf { it.isNotEmpty() }?.let { Format.dateDots(it) }
        row.status == "done" && row.completedDate.isNotEmpty() -> Format.dateDots(row.completedDate)
        else -> null
    } ?: "—"
    val kmText = when {
        current != null -> current.dueKm?.let { distanceText(it) }
        row.status == "done" -> row.completedKm?.let { distanceText(it) }
        else -> null
    } ?: "—"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = DataLabels.serviceItem(row.title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = Format.cycleText(row.intervalKm, row.intervalMonths, Units.distanceUnit),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = planStatusLabel(row.status), status = planStatusKind(row.status))
            }
            HorizontalDivider(color = scheme.outlineVariant)
            when {
                current != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        PlanDetailValue(label = tr("下次到期", "Next due date"), value = dateText)
                        PlanDetailValue(label = tr("到期里程", "Due mileage"), value = kmText)
                    }
                    progress?.let { p ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ServiceProgressBar(progress = p)
                            Text(
                                text = progressSummary(p),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { onComplete(current) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MileNoteDimens.ButtonMinHeight),
                    ) {
                        Icon(
                            imageVector = MileNoteIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("完成并记录", "Complete & log"))
                    }
                }
                row.status == "done" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        PlanDetailValue(label = tr("上次完成", "Last completed"), value = dateText)
                        PlanDetailValue(label = tr("完成里程", "Completed mileage"), value = kmText)
                    }
                    Text(
                        text = tr(
                            "本轮已完成；完成下一轮时会重新计算。",
                            "This cycle is complete; the next one is recalculated when you log it.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        text = tr(
                            "还没有设置周期或完成记录，暂无法计算下次到期。",
                            "No interval or completion record yet, so the next due date can't be calculated.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanDetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

/** 进度摘要：已用 90% · 还剩 1,000 公里 / 已超 1,000 公里。 */
private fun progressSummary(progress: ServiceProgress): String {
    val unit = if (progress.kind == "km") Units.distanceUnit else tr("天", "days")
    val remaining = progress.left?.let {
        if (it >= 0) {
            tr("还剩 %s %s", "%s %s left", Format.fmt(it), unit)
        } else {
            tr("已超 %s %s", "%s %s overdue", Format.fmt(-it), unit)
        }
    }
    val used = tr("已用 %s%%", "%s%% used", Format.fmt(progress.percent))
    return listOfNotNull(used, remaining).joinToString(" · ")
}

@Composable
private fun ServiceHistoryCard(record: Record, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val projects = record.projects?.takeIf { it.isNotBlank() }
    // 地点为空时用「-」占位，保证第三行存在、编辑按钮有对齐基准。
    val location = record.location?.takeIf { it.isNotBlank() } ?: "-"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryBadge(categoryName = record.category)
        // 左侧三行保持紧凑；文字过多时单行省略，不换行。
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = buildString {
                    append(fullDateLabel(record.date))
                    record.odometer?.let {
                        append(" · ")
                        append(distanceText(it))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = projects?.let { raw ->
                    raw.split("、").joinToString(tr("、", ", ")) { DataLabels.serviceItem(it) }
                } ?: DataLabels.category(record.category),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 右列：金额严格与日期顶部平齐；铅笔贴住地点行下沿。
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            MoneyText(
                amount = Format.money(record.amount),
                size = MoneySize.MEDIUM,
                unit = Units.money,
            )
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = tr("编辑", "Edit")) { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MileNoteIcons.Edit,
                    contentDescription = tr("编辑", "Edit"),
                    modifier = Modifier.size(16.dp),
                    tint = scheme.primary,
                )
            }
        }
    }
}

/** `2026-10-01` → `2026.10.01`（记录卡第一行日期）。 */
private fun fullDateLabel(isoDate: String): String = Format.dateDots(isoDate)

/** 扁平列表行的细分割线（不再使用内层卡片）。 */
@Composable
private fun FlatDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

// ---------------------------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------------------------

@Composable
private fun ServiceProgressBar(progress: ServiceProgress, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    LinearProgressIndicator(
        progress = { (progress.percent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MileNoteShapeTokens.full),
        color = if (progress.overdue) scheme.error else scheme.primary,
        trackColor = scheme.surfaceContainerHighest,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
