package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.icons.MileNoteIcons

/**
 * 「我的」页（底栏 v4 第 4 个 tab）= 原设置首页的菜单。
 *
 * 三张卡片、8 行菜单（单色图标 + 标题 + chevron），点行推入对应设置子页
 * （路由参数为 [SettingsPage.key]）。页面本身无顶栏 / 底栏，由外层 Scaffold 提供；
 * 顶栏标题「我的」+ 车辆胶囊与其他 tab 完全一致（v4 选 B 方案，不再让设置页自带顶栏）。
 *
 * @param vm 全局 ViewModel（只用到账本读取错误 / 演示态提示）。
 * @param onOpenPage 打开设置子页（参数为 [SettingsPage.key]）。
 */
@Composable
fun MineScreen(
    vm: MileNoteViewModel,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        SettingsPageList(narrow = true) {
            // 根页只出现「读账失败 / 演示账本」两类提示；子页操作结果的轻提示落在子页内。
            if (state.loadError != null || state.demo) {
                item("messages") {
                    SettingsMessageArea(
                        loadError = state.loadError,
                        demo = state.demo,
                        message = null,
                        onExitDemo = { vm.exitDemo() },
                        onDismissMessage = {},
                    )
                }
            }

            item("menu-main") {
                SectionCard(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    SettingsMenuRow(
                        icon = MileNoteIcons.Car,
                        title = tr("我的车辆", "My vehicles"),
                        onClick = { onOpenPage(SettingsPage.VEHICLES.key) },
                    )
                    SectionDivider()
                    SettingsMenuRow(
                        icon = MileNoteIcons.Palette,
                        title = tr("外观", "Appearance"),
                        onClick = { onOpenPage(SettingsPage.APPEARANCE.key) },
                    )
                    SectionDivider()
                    SettingsMenuRow(
                        icon = MileNoteIcons.Ruler,
                        title = tr("单位", "Units"),
                        onClick = { onOpenPage(SettingsPage.UNITS.key) },
                    )
                    SectionDivider()
                    SettingsMenuRow(
                        icon = MileNoteIcons.Tag,
                        title = tr("费用分类与项目", "Categories & items"),
                        onClick = { onOpenPage(SettingsPage.CATEGORIES.key) },
                    )
                }
            }

            item("menu-data") {
                SectionCard(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    SettingsMenuRow(
                        icon = MileNoteIcons.Import,
                        title = tr("外部导入", "Import"),
                        onClick = { onOpenPage(SettingsPage.IMPORT.key) },
                    )
                    SectionDivider()
                    SettingsMenuRow(
                        icon = MileNoteIcons.Archive,
                        title = tr("备份与还原", "Backup & restore"),
                        onClick = { onOpenPage(SettingsPage.BACKUP.key) },
                    )
                    SectionDivider()
                    SettingsMenuRow(
                        icon = MileNoteIcons.Cloud,
                        title = tr("WebDAV 同步", "WebDAV sync"),
                        onClick = { onOpenPage(SettingsPage.WEBDAV.key) },
                    )
                }
            }

            item("menu-about") {
                SectionCard(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                ) {
                    SettingsMenuRow(
                        icon = MileNoteIcons.Info,
                        title = tr("关于", "About"),
                        onClick = { onOpenPage(SettingsPage.ABOUT.key) },
                    )
                }
            }
        }
    }
}
