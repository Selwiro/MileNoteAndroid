package com.milenote.android.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.domain.format.Format
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.screens.dashboard.DashboardScreen
import com.milenote.android.ui.screens.fuel.FuelScreen
import com.milenote.android.ui.screens.maintenance.MaintenanceScreen
import com.milenote.android.ui.screens.records.RecordsScreen
import com.milenote.android.ui.screens.settings.SettingsScreen

private data class BottomTab(
    val route: String,
    val labelZh: String,
    val labelEn: String,
    val icon: ImageVector,
    val filledIcon: ImageVector,
) {
    /** 当前语言下的 tab 名（getter 在组合时求值，语言切换后自动跟随）。 */
    val label: String
        get() = com.milenote.android.i18n.tr(labelZh, labelEn)
}

/**
 * 底栏 tab 列表：文案在组合内按当前车能量档位求值（燃油「油耗 / Fuel」，纯电与插混「能耗 / Energy」），
 * 不再使用顶层静态文案；pager 索引与 Routes 契约不变。
 */
private fun bottomTabsFor(profile: EnergyProfile): List<BottomTab> {
    val energyUi = profile != EnergyProfile.FUEL_ONLY
    return listOf(
        BottomTab(Routes.DASHBOARD, "总览", "Overview", MileNoteIcons.Home, MileNoteIcons.HomeFilled),
        BottomTab(Routes.RECORDS, "记录", "Records", MileNoteIcons.Records, MileNoteIcons.RecordsFilled),
        BottomTab(
            Routes.FUEL,
            if (energyUi) "能耗" else "油耗",
            if (energyUi) "Energy" else "Fuel",
            MileNoteIcons.Fuel,
            MileNoteIcons.FuelFilled,
        ),
        BottomTab(Routes.MAINTENANCE, "保养", "Maintenance", MileNoteIcons.Maintenance, MileNoteIcons.MaintenanceFilled),
    )
}

private data class TopBarCopy(val title: String, val subtitle: String)

/** 顶栏标题：油耗分析 / 能耗分析按当前车能量档位切换（其余路由与语言无关逻辑不变）。 */
private fun topBarCopy(route: String?, profile: EnergyProfile): TopBarCopy? = when (route) {
    Routes.DASHBOARD -> TopBarCopy(tr("用车总览", "Overview"), "")
    Routes.RECORDS -> TopBarCopy(tr("费用统计", "Analytics"), "")
    Routes.FUEL -> if (profile != EnergyProfile.FUEL_ONLY) {
        TopBarCopy(tr("能耗分析", "Energy"), "")
    } else {
        TopBarCopy(tr("油耗分析", "Fuel"), "")
    }
    Routes.MAINTENANCE -> TopBarCopy(tr("维修保养", "Service"), "")
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MileNoteApp(vm: MileNoteViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 当前车能量档位：底栏 / 顶栏文案与分类显示名在组合内求值（切换车辆后自动更新）。
    val profile = state.activeCar?.vehicle?.energyProfile ?: EnergyProfile.FUEL_ONLY
    val bottomTabs = bottomTabsFor(profile)
    val pagerState = rememberPagerState(pageCount = { bottomTabs.size })
    val scope = rememberCoroutineScope()
    val isTabs = currentRoute == Routes.DASHBOARD
    val currentPage = pagerState.currentPage.coerceIn(0, bottomTabs.lastIndex)
    val showBottomBar = isTabs

    // 从系统分享进入时自动打开导入向导（ImportWizard 会消费 sharedImport 并直接预览）。
    LaunchedEffect(state.sharedImport) {
        if (state.sharedImport != null && currentRoute != Routes.IMPORTER_PATTERN) {
            navController.navigate(Routes.IMPORTER)
        }
    }
    val copy = if (isTabs) topBarCopy(bottomTabs[currentPage].route, profile) else null

    // 四个底部 tab 支持左右滑动切换；在非首个 tab 按返回先回总览。
    BackHandler(enabled = isTabs && currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (copy != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = copy.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val vehicle = state.vehicle
                    if (vehicle != null) {
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.width(8.dp),
                        )
                        AssistChip(
                            onClick = { vm.showVehicleSwitcher(true) },
                            label = {
                                Text(
                                    text = com.milenote.android.i18n.DataLabels.vehicleName(vehicle.name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(MileNoteIcons.Car, contentDescription = null)
                            },
                        )
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.width(4.dp),
                        )
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = tr("设置", "Settings"))
                    }
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MileNoteBottomBar(
                    tabs = bottomTabs,
                    selectedIndex = currentPage,
                    onSelect = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    onAdd = { vm.openEditor() },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.DASHBOARD,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.DASHBOARD) {
                    HomeTabs(
                        vm = vm,
                        pagerState = pagerState,
                        tabs = bottomTabs,
                        onNavigate = { route ->
                            val tabIndex = bottomTabs.indexOfFirst { it.route == route }
                            if (tabIndex >= 0) {
                                scope.launch { pagerState.animateScrollToPage(tabIndex) }
                            } else {
                                navController.navigate(route)
                            }
                        },
                    )
                }
                composable(
                    route = Routes.SETTINGS,
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    SettingsScreen(
                        vm = vm,
                        onNavigate = { navController.navigate(it) },
                        onOpenImporter = { source -> navController.navigate(Routes.importerRoute(source)) },
                    )
                }
                composable(
                    route = Routes.IMPORTER_PATTERN,
                    arguments = listOf(
                        navArgument("source") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    com.milenote.android.ui.screens.importer.ImportWizardScreen(
                        vm = vm,
                        initialSource = entry.arguments?.getString("source"),
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    // 记一笔 / 编辑记录：独立全屏页面（覆盖底栏，返回键先关页面）。
    state.editor?.let { target ->
        com.milenote.android.ui.screens.editor.RecordEditorScreen(
            vm = vm,
            target = target,
            onDismiss = { vm.closeEditor() },
        )
    }

    // 通用确认弹层
    state.confirm?.let { confirm ->
        var input by remember(confirm) { mutableStateOf("") }
        val inputOk = confirm.requireText == null || input == confirm.requireText
        AlertDialog(
            onDismissRequest = { vm.dismissConfirm() },
            title = { Text(confirm.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(confirm.message)
                    if (confirm.requireText != null) {
                        Text(tr("请输入「%s」以确认", "Type \"%s\" to confirm", confirm.requireText))
                        OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.runConfirm() }, enabled = inputOk) {
                    Text(
                        confirm.confirmLabel,
                        color = if (confirm.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { vm.dismissConfirm() }) { Text(tr("取消", "Cancel")) } },
        )
    }

    // 删除保养记录：询问是否恢复该记录完成的提醒
    state.recordDelete?.let { pending ->
        val record = pending.record
        AlertDialog(
            onDismissRequest = { vm.cancelRecordDelete() },
            title = { Text(tr("删除这条记录？", "Delete this record?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${Dates.displayDots(record.date)} · ${com.milenote.android.i18n.DataLabels.category(record.category, profile)}" +
                            " · ¥${Format.money(record.amount)}",
                    )
                    Text(
                        tr(
                            "这条记录完成了保养提醒「%s」。删除后是否恢复提醒？",
                            "This record completed the reminder \"%s\". Restore the reminder after deleting?",
                            pending.reminderTitles
                                .map { com.milenote.android.i18n.DataLabels.serviceItem(it) }
                                .joinToString(if (com.milenote.android.i18n.I18n.english) ", " else "、"),
                        ),
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.confirmRecordDelete(record, restoreReminders = true) }) {
                        Text(tr("删除并恢复提醒", "Delete and restore reminder"))
                    }
                    TextButton(onClick = { vm.confirmRecordDelete(record, restoreReminders = false) }) {
                        Text(tr("仅删除记录", "Delete record only"), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { vm.cancelRecordDelete() }) { Text(tr("取消", "Cancel")) }
                }
            },
        )
    }

    // 车辆切换
    if (state.vehicleSwitcher) {
        ModalBottomSheet(onDismissRequest = { vm.showVehicleSwitcher(false) }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr("切换车辆", "Switch vehicle"), style = MaterialTheme.typography.headlineSmall)
                state.garage?.cars?.forEach { car ->
                    val selected = car.id == state.garage?.activeId
                    TextButton(
                        onClick = {
                            vm.selectVehicle(car.id)
                            vm.showVehicleSwitcher(false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = (if (selected) "✓ " else "") +
                                com.milenote.android.i18n.DataLabels.vehicleName(car.vehicle.name) + " · " +
                                tr("%d 条记录", "%d records", car.records.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = { vm.showVehicleSwitcher(false) }) { Text(tr("关闭", "Close")) }
            }
        }
    }
}


/** 四个底部 tab 的横向分页容器（微信式左右滑动切换）。 */
@Composable
private fun HomeTabs(
    vm: MileNoteViewModel,
    pagerState: PagerState,
    tabs: List<BottomTab>,
    onNavigate: (String) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { page ->
        holder.SaveableStateProvider(tabs[page].route) {
            when (page) {
                0 -> DashboardScreen(vm = vm, onNavigate = onNavigate)
                1 -> RecordsScreen(vm = vm, onNavigate = onNavigate)
                2 -> FuelScreen(vm = vm, onNavigate = onNavigate)
                3 -> MaintenanceScreen(vm = vm, onNavigate = onNavigate)
            }
        }
    }
}

/**
 * 底栏：4 个页面 tab + 中间「记一笔」页面按钮。
 * 选中 tab 把「图标 + 文字」一起包在圆角矩形里；中间按钮为抬高圆形主色按钮，
 * 位于「记录」和「油耗」之间。
 */
@Composable
private fun MileNoteBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.take(2).forEachIndexed { index, tab ->
                BottomBarTab(
                    tab = tab,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
            Box(
                modifier = Modifier.weight(1.12f),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    onClick = onAdd,
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .width(54.dp)
                        .height(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MileNoteIcons.Add,
                            contentDescription = tr("记一笔", "Add entry"),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            tabs.drop(2).forEachIndexed { offset, tab ->
                val index = offset + 2
                BottomBarTab(
                    tab = tab,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomBarTab(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = if (selected) scheme.onSurface else scheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) tab.filledIcon else tab.icon,
            contentDescription = tab.label,
            modifier = Modifier.size(24.dp),
            tint = contentColor,
        )
    }
}
