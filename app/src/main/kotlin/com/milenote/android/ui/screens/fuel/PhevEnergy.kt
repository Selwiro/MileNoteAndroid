package com.milenote.android.ui.screens.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milenote.android.domain.calc.EnergyKind
import com.milenote.android.domain.calc.EnergyRate
import com.milenote.android.domain.format.Format
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr

/*
 * 插混（EnergyProfile.DUAL）能耗页专用的两块小 UI。
 *
 * 燃油 / 纯电账本仍走 `FuelScreen.kt` 里的原有分支（2×2 概览、单流走势），本文件只被
 * 「加油 + 充电」两套字段都存在的档位调用，因此可以放心按双流布局排版：
 * - PhevEnergyMetrics：概览卡 2×3（平均油耗 / 平均电耗 / 油费 / 电费 / 每公里能源费用 / 里程）；
 * - EnergyKindToggle：走势卡上方的「油耗 / 电耗」切换（单 Y 轴，单位随切换）。
 *
 * 数值全部来自 domain `stats().fuelEnergy` / `stats().electricEnergy`（两条流各自累计），
 * 这里只负责取「区间加权值 → 累计估算」的回退与单位标注，**不做任何跨流合并计算**。
 */

/**
 * 插混概览 2×3 指标。
 *
 * @param fuel 燃油流（满箱法 + 历史手填）；纯电账本为 null，插混一般非 null
 * @param electric 电荷流（满电法）；燃油账本为 null，插混一般非 null
 * @param fuelCost 油费支出（能源记录中无 `kwh` 的部分，口径同 `costOf`）
 * @param electricCost 电费支出（能源记录中有 `kwh` 的部分，口径同 `costOf`）
 * @param perKmEnergy 每公里能源费用（元/km），里程为 0 时为 null
 * @param distance 统计范围内行驶里程 km
 */
@Composable
internal fun PhevEnergyMetrics(
    fuel: EnergyRate?,
    electric: EnergyRate?,
    fuelCost: Double,
    electricCost: Double,
    perKmEnergy: Double?,
    distance: Double,
) {
    // 区间加权优先，没有满箱 / 满充区间时回退累计估算（两者单位相同，只在卡底标注口径差异）。
    val fuelRate = fuel?.rate ?: fuel?.estimated
    val electricRate = electric?.rate ?: electric?.estimated
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = tr("平均油耗", "Fuel economy"),
                value = fuelRate?.let { Format.money(it) } ?: "—",
                unit = if (fuelRate == null) null else Units.consumption,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("平均电耗", "Electric economy"),
                value = electricRate?.let { Format.money(it) } ?: "—",
                unit = if (electricRate == null) null else Units.energyConsumption,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = tr("油费支出", "Fuel expense"),
                value = Format.money(fuelCost),
                unit = Units.money,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("电费支出", "Charging expense"),
                value = Format.money(electricCost),
                unit = Units.money,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCell(
                label = tr("每公里能源费用", "Energy cost per km"),
                value = perKmEnergy?.let { Format.money(it) } ?: "—",
                unit = if (perKmEnergy == null) null else Units.costPerDistance,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = tr("行驶里程", "Distance"),
                value = Format.fmt(distance),
                unit = Units.distanceUnit,
                modifier = Modifier.weight(1f),
            )
        }
        // 估算口径提示：只在该流「没有满箱/满充区间、改用累计估算」时出现，与数值一起读。
        if (fuel != null && fuel.rate == null && fuel.estimated != null) {
            Text(
                text = tr(
                    "暂无满箱区间，平均油耗为累计估算口径。",
                    "No full-tank interval yet; fuel economy is a cumulative estimate.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (electric != null && electric.rate == null && electric.estimated != null) {
            Text(
                text = tr(
                    "暂无满充区间，平均电耗为累计估算口径（含充电损耗）。",
                    "No full-charge interval yet; electric economy is a cumulative estimate (includes charging loss).",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「油耗 / 电耗」切换：走势卡内、图表上方的一对胶囊选项（与右上角时间筛选同为胶囊语言）。
 *
 * 只切换走势图看哪条流，不改变概览 / 明细；单位由调用方随 [selected] 传入图表。
 *
 * @param selected 当前能耗种类（默认燃油，页面重建后保持）
 * @param onSelect 切换回调
 */
@Composable
internal fun EnergyKindToggle(
    selected: EnergyKind,
    onSelect: (EnergyKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            EnergyKind.FUEL to tr("油耗", "Fuel"),
            EnergyKind.ELECTRIC to tr("电耗", "Electric"),
        ).forEach { (kind, label) ->
            val active = kind == selected
            Surface(
                onClick = { onSelect(kind) },
                shape = CircleShape,
                color = if (active) scheme.primaryContainer else Color.Transparent,
                contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}
