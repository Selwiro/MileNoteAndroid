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

/** 体检清单每块最多渲染多少条（避免一次组合上万行）。 */
internal const val HealthIssueChunkSize: Int = 40

/** 体检清单最多展示多少条；超出的只提示数量。 */
internal const val HealthIssueShownMax: Int = 400

/** 最多展示多少条（给界面算「还有 N 项未列出」用）。 */
internal fun healthIssuesShown(total: Int): Int = total.coerceAtMost(HealthIssueShownMax)

/**
 * 把一类问题切成小块（每块不超过 [HealthIssueChunkSize]、总量不超过 [HealthIssueShownMax]）。
 *
 * 纯函数便于单测：批量脏数据（整批未来日期 / 里程倒挂）可能一次产生上万条，
 * 必须保证界面每次只组合有限行。
 */
internal fun healthIssueChunks(issues: List<HealthIssue>): List<List<HealthIssue>> =
    issues.take(HealthIssueShownMax).chunked(HealthIssueChunkSize)

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
                            "共检查 %d 条记录、%d 条提醒；只有「删除重复」可以撤销，其他修复会立即写入。",
                            "Checked %d records and %d reminders; only duplicate deletion can be undone — other fixes are applied immediately.",
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
            // 每条问题一个动作；抽出来给分块渲染复用。
            val onIssueAction: (HealthIssue) -> Unit = { issue ->
                when (issue.fix) {
                    HealthFix.OPEN_EDITOR -> issue.recordId?.let(onOpenRecord)
                    HealthFix.OPEN_VEHICLE -> onOpenVehicle()
                    HealthFix.DELETE_DUPLICATE -> confirmDuplicate = issue
                    HealthFix.RECOMPUTE_REMINDER,
                    HealthFix.MARK_FUEL_COUNTED,
                    -> onFix(issue)
                    null -> Unit
                }
            }
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
                // 分块渲染：体检规则是「每条记录最多一条」，一次批量脏数据导入可能产生上万条。
                // 全塞进一个 Lazy 项会一次组合上万行（卡死 / ANR），这里每块最多 40 条、
                // 总共最多展示 400 条，其余只提示数量（先修复或导出核对）。
                healthIssueChunks(issues).forEachIndexed { chunkIndex, chunk ->
                    item("issues-$severity-$chunkIndex") {
                        SectionCard {
                            chunk.forEachIndexed { index, issue ->
                                if (index > 0) HorizontalDivider()
                                IssueRow(issue = issue, onAction = { onIssueAction(issue) })
                            }
                        }
                    }
                }
                val hidden = issues.size - healthIssuesShown(issues.size)
                if (hidden > 0) {
                    item("issues-$severity-more") {
                        Text(
                            text = tr(
                                "还有 %d 项未列出：先修复上面这些（或导出备份后核对），再重新体检。",
                                "%d more issues are not listed — fix the ones above (or export a backup and check), then run the check again.",
                                hidden,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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
