package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.milenote.android.data.settings.backupAgeDays
import com.milenote.android.data.settings.backupOverdueDays
import com.milenote.android.data.settings.formatBackupAt
import com.milenote.android.domain.calc.Companion
import com.milenote.android.domain.calc.Dates
import com.milenote.android.i18n.tr
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.LocalMileNoteSemanticColors
import com.milenote.android.ui.theme.metricValue

/**
 * 「我的」页（底栏 v4 第 4 个 tab）：**数据概览卡 + 三个分组菜单 + 页脚**。
 *
 * 2026-11-12 改版（方案 A / 卡片 M1）：
 * - 顶部「数据概览」卡只讲**数据本身**（车辆 / 记录 / 相伴天数 + 上次备份 / 同步状态），
 *   不做"本地账号/个人资料"身份叙事——App 没有账号，`无账号` 也不需要在页面上自我介绍；
 * - 菜单拆成「车辆与偏好 / 数据与同步 / 应用」三个分组、8 个入口；行右侧不挂当前值
 *   （车辆数 / 皮肤 / 单位 / 备份时间 / WebDAV 开关 / 版本号都不再挤在菜单行上，2026-11-12 用户反馈）；
 * - 「隐私与数据」「开源许可」是低频只读内容，只保留「关于」页内入口，根页不再重复（2026-11-12 去重）；
 * - 页脚只留品牌字标（不写「数据默认保存在本机」这类说明，数据口径在「隐私与数据」子页）。
 *
 * 页面本身无顶栏 / 底栏，由外层 Scaffold 提供；顶栏标题「我的」+ 车辆胶囊与其他 tab 一致。
 * 行点击推入设置子页（路由参数为 [SettingsPage.key]）。
 *
 * @param vm 全局 ViewModel（账本、设置、WebDAV 状态、读账错误 / 演示态）。
 * @param onOpenPage 打开设置子页（参数为 [SettingsPage.key]）。
 */
@Composable
fun MineScreen(
    vm: MileNoteViewModel,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val garage = state.garage
    val cars = garage?.cars.orEmpty()

    // ---- 数据概览卡的账本级口径（全部 O(车)，不遍历记录） ----
    val earliestCompanionDate = remember(garage) {
        cars.mapNotNull { it.vehicle.companionDate }
            .filter { Dates.isValid(it) }
            .minOrNull()
    }
    val companionDays = remember(earliestCompanionDate) { Companion.days(earliestCompanionDate) }
    val sinceLabel = remember(earliestCompanionDate) {
        earliestCompanionDate?.take(7)?.replace('-', '.')
    }
    val lastBackupAt = settings.lastBackupAt
    val backupDays = remember(lastBackupAt) { backupAgeDays(lastBackupAt) }

    val backupLabel = backupAgeLabel(backupDays, lastBackupAt)
    val backupMissing = backupDays == null
    // 超期只做页面内提醒（≥30 天）；未备份走「尚未备份」的既有警告色，不叠加。
    val overdueDays = remember(lastBackupAt) { backupOverdueDays(lastBackupAt) }
    val syncOn = state.webDav.configured
    val syncText = if (syncOn) tr("同步已开启", "Sync on") else tr("同步未开启", "Sync off")
    val statusText = when {
        backupMissing -> tr("尚未备份 · %s", "No backup yet · %s", syncText)
        overdueDays != null -> tr("已 %d 天未备份 · %s", "%d days without a backup · %s", overdueDays, syncText)
        else -> tr("上次备份 %s · %s", "Last backup %s · %s", backupLabel, syncText)
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsPageList(narrow = true) {
            // 根页只出现「读账失败 / 演示账本」两类提示；子页操作结果的轻提示落在子页内。
            if (state.loadError != null || state.demo) {
                item("messages") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.loadError?.let { loadError ->
                            MessageBar(
                                message = loadError,
                                kind = MessageKind.ERROR,
                                actionLabel = tr("重新读取账本", "Re-read the ledger"),
                                onAction = vm::reloadLedger,
                            )
                        }
                        if (state.demo) {
                            MessageBar(
                                message = tr(
                                    "演示账本 · 修改不会保存到真实账本",
                                    "Demo ledger · Changes are not saved to your real ledger",
                                ),
                                kind = MessageKind.DEMO,
                                actionLabel = tr("退出演示", "Exit demo"),
                                onAction = { vm.exitDemo() },
                            )
                        }
                    }
                }
            }

            // 读账失败（garage == null）时不画卡：概览数字都依赖 garage，显示 0 会被当成真值。
            if (garage != null) {
                item("overview") {
                    MineDataCard(
                        carsCount = cars.size,
                        recordsCount = cars.sumOf { it.records.size },
                        companionDays = companionDays,
                        sinceLabel = sinceLabel,
                        statusText = statusText,
                        statusWarn = backupMissing || overdueDays != null,
                        showStatus = !state.demo,
                    )
                }
            }

            item("menu-main") {
                MineGroup(
                    label = tr("车辆与偏好", "Vehicles & preferences"),
                ) {
                    MineValueRow(
                        icon = MileNoteIcons.Car,
                        title = tr("我的车辆", "My vehicles"),
                        onClick = { onOpenPage(SettingsPage.VEHICLES.key) },
                    )
                    SectionDivider()
                    MineValueRow(
                        icon = MileNoteIcons.Palette,
                        title = tr("外观", "Appearance"),
                        onClick = { onOpenPage(SettingsPage.APPEARANCE.key) },
                    )
                    SectionDivider()
                    MineValueRow(
                        icon = MileNoteIcons.Ruler,
                        title = tr("单位", "Units"),
                        onClick = { onOpenPage(SettingsPage.UNITS.key) },
                    )
                    SectionDivider()
                    MineValueRow(
                        icon = MileNoteIcons.Tag,
                        title = tr("费用分类与项目", "Categories & items"),
                        onClick = { onOpenPage(SettingsPage.CATEGORIES.key) },
                    )
                }
            }

            item("menu-data") {
                MineGroup(
                    label = tr("数据与同步", "Data & sync"),
                ) {
                    MineValueRow(
                        icon = MileNoteIcons.Import,
                        title = tr("外部导入", "Import"),
                        onClick = { onOpenPage(SettingsPage.IMPORT.key) },
                    )
                    SectionDivider()
                    MineValueRow(
                        icon = MileNoteIcons.Archive,
                        title = tr("备份与还原", "Backup & restore"),
                        onClick = { onOpenPage(SettingsPage.BACKUP.key) },
                    )
                    SectionDivider()
                    MineValueRow(
                        icon = MileNoteIcons.Cloud,
                        title = tr("WebDAV 同步", "WebDAV sync"),
                        onClick = { onOpenPage(SettingsPage.WEBDAV.key) },
                    )
                }
            }

            item("menu-about") {
                MineGroup(
                    label = tr("应用", "App"),
                ) {
                    MineValueRow(
                        icon = MileNoteIcons.Info,
                        title = tr("关于", "About"),
                        onClick = { onOpenPage(SettingsPage.ABOUT.key) },
                    )
                }
            }

            item("footer") {
                MineFooter()
            }
        }
    }
}

/**
 * 数据概览卡（M1）：不写"本机账本 / 无账号"这类身份自述，只讲数据规模与数据安全。
 *
 * @param carsCount 车辆数（账本级）。
 * @param recordsCount 记录总数（账本级）。
 * @param companionDays 最早相伴日到今天的天数；null 时显示 `—`。
 * @param sinceLabel 起记时间 `yyyy.MM`；null（空账本）时不显示右上角。
 * @param statusText 数据安全状态行（上次备份 / 同步）。
 * @param statusWarn 尚未备份时状态行用警告色（文字与圆点一起变色）。
 * @param showStatus 演示账本下隐藏状态行，避免与本机真实备份/同步状态混搭。
 */
@Composable
private fun MineDataCard(
    carsCount: Int,
    recordsCount: Int,
    companionDays: Int?,
    sinceLabel: String?,
    statusText: String,
    statusWarn: Boolean,
    showStatus: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val secondary = scheme.onPrimaryContainer.copy(alpha = 0.62f)
    val semantic = LocalMileNoteSemanticColors.current
    val statusColor = if (statusWarn) semantic.warning.content else semantic.success.content

    SectionCard(
        containerColor = scheme.primaryContainer,
        borderColor = scheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("数据概览", "Data overview"),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (sinceLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tr("自 %s 起", "Since %s", sinceLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MineStatCell(
                label = tr("车辆", "Vehicles"),
                value = carsCount.toString(),
                modifier = Modifier.weight(1f),
            )
            MineStatCell(
                label = tr("记录", "Records"),
                value = recordsCount.toString(),
                modifier = Modifier.weight(1f),
            )
            MineStatCell(
                label = tr("相伴天数", "Days together"),
                value = companionDays?.toString() ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        if (showStatus) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = 0.12f))
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(statusColor, CircleShape),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 概览卡三格：标签在上（12/16）、数值在下（24/32 SemiBold + tabular）。 */
@Composable
private fun RowScope.MineStatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = scheme.onPrimaryContainer.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.metricValue.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/** 分组 = 小标题 + 一张菜单卡（`SectionCard` 与设置子页同一口径）。 */
@Composable
private fun MineGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        SectionCard(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        ) {
            content()
        }
    }
}

/**
 * 菜单行：单色图标 + 标题 + chevron（整行可点）。
 *
 * 右侧只保留 chevron 作为「可进入」的提示，不再显示车辆数 / 当前皮肤 / 备份时间等当前值：
 * 这些信息要么在子页里能直接看到，要么在概览卡里已有，堆在菜单行上反而打断阅读（2026-11-12 用户反馈）。
 */
@Composable
private fun MineValueRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = MileNoteIcons.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 页脚：只留品牌字标（版本在「关于」，数据口径在隐私页，不在这里重复）。 */
@Composable
private fun MineFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tr("里程志 MileNote", "MileNote"),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 备份时间的相对显示：今天 / 昨天 / N 天前；≥30 天退回绝对日期 `yyyy.MM.dd`。
 *
 * 与行内宽度（132dp）配套：短文案；`lastBackupAt` 为 null 时显示「尚未备份」。
 */
private fun backupAgeLabel(days: Int?, lastBackupAt: Long?): String = when {
    days == null -> tr("尚未备份", "No backup yet")
    days <= 0 -> tr("今天", "Today")
    days == 1 -> tr("昨天", "Yesterday")
    days < 30 -> tr("%d 天前", "%d days ago", days)
    else -> formatBackupAt(lastBackupAt ?: 0L).take(10).replace('-', '.')
}
