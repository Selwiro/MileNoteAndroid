package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReminderLevel
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.reminderState
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.Today
import com.milenote.android.ui.components.FilterPill
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.theme.MileNoteDimens
import java.time.LocalDate

/** 车库总览的排序维度（只影响展示，不写账本）。 */
private enum class GarageSort {
    TOTAL,
    PER_KM,
    DISTANCE,
    RECENT;

    val label: String
        get() = when (this) {
            TOTAL -> tr("累计支出", "Total spend")
            PER_KM -> tr("元/公里", "Cost per km")
            DISTANCE -> tr("累计里程", "Distance")
            RECENT -> tr("最近记录", "Last record")
        }
}

/** 一辆车的对比指标（全部来自 stats()，不新写口径）。 */
private data class CarSummary(
    val ledger: Ledger,
    val recordCount: Int,
    val total: Double,
    val distance: Double,
    val perKm: Double?,
    val monthTotal: Double,
    val energy: String,
    val dueCount: Int,
    val lastDate: String?,
)

/**
 * 车库总览（F7）：只读并列对比，**不做跨车合计**、不做图表、不在这里编辑车辆。
 *
 * 点一行 = 切换到该车并回总览（由调用方处理导航）。
 */
@Composable
internal fun GarageOverviewPage(
    cars: List<Ledger>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = Today.current
    var sort by rememberSaveable { mutableStateOf(GarageSort.TOTAL) }
    var sortMenu by remember { mutableStateOf(false) }
    val summaries = remember(cars, today) { cars.map { car -> car.summary(today) } }
    val sorted = remember(summaries, sort) {
        when (sort) {
            GarageSort.TOTAL -> summaries.sortedByDescending { it.total }
            GarageSort.PER_KM -> summaries.sortedBy { it.perKm ?: Double.MAX_VALUE }
            GarageSort.DISTANCE -> summaries.sortedByDescending { it.distance }
            GarageSort.RECENT -> summaries.sortedByDescending { it.lastDate ?: "" }
        }
    }

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
        item("header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = tr("共 %d 辆车", "%d vehicles", cars.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                FilterPill(
                    label = tr("排序：%s", "Sort: %s", sort.label),
                    expanded = sortMenu,
                    onToggle = { sortMenu = true },
                    onDismiss = { sortMenu = false },
                ) {
                    GarageSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sort = option
                                sortMenu = false
                            },
                        )
                    }
                }
            }
            Text(
                text = tr(
                    "只并列、不合并：不同动力类型的能耗与元/公里不做合计。",
                    "Listed side by side, never merged: energy and cost per km are not summed across vehicles.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (cars.isEmpty()) {
            item("empty") {
                SectionCard {
                    Text(
                        text = tr("还没有车辆", "No vehicles yet"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            items(sorted.size) { index ->
                val summary = sorted[index]
                SectionCard(
                    modifier = Modifier.clickable { onSelect(summary.ledger.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = DataLabels.vehicleName(summary.ledger.vehicle.name),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = tr(
                                    "%s · %d 条记录",
                                    "%s · %d records",
                                    powertrainLabel(summary.ledger.vehicle.energyProfile),
                                    summary.recordCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (summary.ledger.id == activeId) {
                            Text(
                                text = tr("当前", "Current"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCell(
                                label = tr("累计支出", "Total spend"),
                                value = "${Units.currencySymbol}${Format.money(summary.total)}",
                                modifier = Modifier.weight(1f),
                            )
                            MetricCell(
                                label = tr("累计里程", "Distance"),
                                value = Format.km(summary.distance, Units.distanceUnit),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCell(
                                label = tr("元/公里", "Cost per km"),
                                value = summary.perKm?.let { "${Format.money(it)} ${Units.costPerDistance}" }
                                    ?: "—",
                                modifier = Modifier.weight(1f),
                            )
                            MetricCell(
                                label = tr("本月支出", "This month"),
                                value = "${Units.currencySymbol}${Format.money(summary.monthTotal)}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MetricCell(
                                label = tr("能耗", "Energy"),
                                value = summary.energy,
                                modifier = Modifier.weight(1f),
                            )
                            MetricCell(
                                label = tr("待办", "Due"),
                                value = if (summary.dueCount > 0) {
                                    tr("%d 项", "%d", summary.dueCount)
                                } else {
                                    "—"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = summary.lastDate?.let {
                                tr("最近记录 %s", "Last record %s", Format.dateDots(it))
                            } ?: tr("暂无记录", "No records yet"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private fun powertrainLabel(profile: EnergyProfile): String = when (profile) {
    EnergyProfile.FUEL_ONLY -> tr("燃油 / 混动", "Petrol / hybrid")
    EnergyProfile.DUAL -> tr("插混 / 增程", "Plug-in hybrid")
    EnergyProfile.ELECTRIC_ONLY -> tr("纯电", "Electric")
}

/** 单车指标：全部复用 `stats()` / `reminderState()`，口径与单车页一致。 */
private fun Ledger.summary(today: LocalDate): CarSummary {
    val all = stats(this)
    val monthStart = Dates.iso(today.withDayOfMonth(1))
    val month = stats(this, monthStart, Dates.iso(today))
    val km = currentKm(this)
    val fuelRate = all.fuelEnergy?.rate ?: all.fuelEnergy?.estimated
    val electricRate = all.electricEnergy?.rate ?: all.electricEnergy?.estimated
    val fuelText = fuelRate?.let { "${Format.fmt(it)} ${Units.consumption}" }
    val electricText = electricRate?.let { "${Format.fmt(it)} ${Units.energyConsumption}" }
    val energy = when (vehicle.energyProfile) {
        EnergyProfile.FUEL_ONLY -> fuelText ?: "—"
        EnergyProfile.ELECTRIC_ONLY -> electricText ?: "—"
        EnergyProfile.DUAL -> listOfNotNull(fuelText, electricText).joinToString("\n").ifEmpty { "—" }
    }
    val due = reminders.count { reminder ->
        reminder.done != true &&
            reminderState(reminder, km, today).level.let {
                it == ReminderLevel.OVERDUE || it == ReminderLevel.SOON
            }
    }
    return CarSummary(
        ledger = this,
        recordCount = records.size,
        total = all.total,
        distance = all.distance,
        perKm = all.perKm,
        monthTotal = month.total,
        energy = energy,
        dueCount = due,
        lastDate = records.maxOfOrNull { it.date }?.takeIf { Dates.isValid(it) },
    )
}
