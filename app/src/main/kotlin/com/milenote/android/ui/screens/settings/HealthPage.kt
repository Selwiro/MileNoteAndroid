package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.HealthFix
import com.milenote.android.domain.calc.HealthIssue
import com.milenote.android.domain.calc.HealthSeverity
import com.milenote.android.domain.calc.dataHealth
import com.milenote.android.domain.model.Ledger
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import com.milenote.android.ui.Today
import androidx.compose.material3.HorizontalDivider
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.theme.MileNoteDimens

/**
 * 数据体检页（F1）：列出 `dataHealth()` 的问题清单。
 *
 * 只读展示 + 三个修复动作（删除重复 / 修复提醒 / 计入成本）；其余问题跳到记录或车辆编辑。
 * 修复动作本身在 ViewModel 里执行，删除重复会给「撤销」。
 */
@Composable
internal fun HealthPage(
    ledger: Ledger,
    onOpenRecord: (String) -> Unit,
    onOpenVehicle: () -> Unit,
    onFix: (HealthIssue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Today.current
    val report = remember(ledger, today) { dataHealth(ledger, today) }
    var confirmDuplicate by remember { mutableStateOf<HealthIssue?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MileNoteDimens.PageHorizontal,
            end = MileNoteDimens.PageHorizontal,
            top = MileNoteDimens.CardGap,
            bottom = MileNoteDimens.PageBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            SectionCard(title = tr("体检结果", "Check result")) {
                if (report.isEmpty) {
                    Text(
                        text = tr("没有发现问题", "No problems found"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tr(
                            "共检查 %d 条记录、%d 条提醒。体检只报确定的错；可疑项会单独标出。",
                            "Checked %d records and %d reminders. Only clear problems are reported; doubtful ones are listed separately.",
                            ledger.records.size,
                            ledger.reminders.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = tr(
                            "%d 项需要处理 · %d 项提示",
                            "%d to fix · %d notes",
                            report.attentionCount,
                            report.infoCount,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tr(
                            "共检查 %d 条记录、%d 条提醒；修复前会保留可撤销的快照。",
                            "Checked %d records and %d reminders; fixes keep an undo snapshot.",
                            ledger.records.size,
                            ledger.reminders.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = tr(
                        "只检查当前车「%s」；切换车辆后重新体检。",
                        "Only the current vehicle \"%s\" is checked; switch vehicles to check another.",
                        DataLabels.vehicleName(ledger.vehicle.name),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!report.isEmpty) {
            listOf(
                HealthSeverity.ERROR to tr("错误（会让统计出错）", "Errors (affect totals)"),
                HealthSeverity.WARN to tr("可疑（建议核对）", "Warnings (please check)"),
                HealthSeverity.INFO to tr("提示", "Notes"),
            ).forEach { (severity, label) ->
                val issues = report.issues.filter { it.severity == severity }
                if (issues.isEmpty()) return@forEach
                item("header-$severity") {
                    Text(
                        text = "$label · ${issues.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item("issues-$severity") {
                    SectionCard {
                        issues.forEachIndexed { index, issue ->
                            if (index > 0) HorizontalDivider()
                            IssueRow(
                                issue = issue,
                                onAction = {
                                    when (issue.fix) {
                                        HealthFix.OPEN_EDITOR -> issue.recordId?.let(onOpenRecord)
                                        HealthFix.OPEN_VEHICLE -> onOpenVehicle()
                                        HealthFix.DELETE_DUPLICATE -> confirmDuplicate = issue
                                        HealthFix.RECOMPUTE_REMINDER,
                                        HealthFix.MARK_FUEL_COUNTED,
                                        -> onFix(issue)
                                        null -> Unit
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmDuplicate?.let { issue ->
        val count = ledger.records.count { it.id == issue.recordId }
        AlertDialog(
            onDismissRequest = { confirmDuplicate = null },
            title = { Text(tr("删除重复记录？", "Delete duplicate records?")) },
            text = {
                Text(
                    tr(
                        "编号 %s 有 %d 条记录：保留较新的一条，删除其余 %d 条。删除后可在底部提示里撤销。",
                        "ID %s has %d records: keep the newest and delete the other %d. You can undo from the message below.",
                        issue.recordId.orEmpty(),
                        count,
                        (count - 1).coerceAtLeast(0),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDuplicate = null
                        onFix(issue)
                    },
                ) {
                    Text(tr("删除", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDuplicate = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

/** 单条问题：标题 + 具体数值 + 右侧动作。 */
@Composable
private fun IssueRow(
    issue: HealthIssue,
    onAction: () -> Unit,
) {
    val actionLabel = when (issue.fix) {
        HealthFix.OPEN_EDITOR -> tr("打开记录", "Open")
        HealthFix.OPEN_VEHICLE -> tr("打开车辆", "Open")
        HealthFix.DELETE_DUPLICATE -> tr("删除多余", "Delete extra")
        HealthFix.RECOMPUTE_REMINDER -> tr("修复", "Repair")
        HealthFix.MARK_FUEL_COUNTED -> tr("计入成本", "Count it")
        null -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = issue.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
