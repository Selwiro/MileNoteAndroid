package com.milenote.android.domain.calc

import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile

/** 某分类的统计金额。 */
data class CategoryValue(val name: String, val value: Double)

/**
 * 一条能量流（燃油或电荷）的统计结果。
 *
 * 混动车（PHEV / 增程）两条流同时存在，**各自独立聚合**：
 * [rate] = 区间累计量 ÷ 区间累计里程；把两条流合并会得到"分母≈2×里程、分子 L+kWh"的错数。
 *
 * @property rate 满箱/满电法里程加权能耗（L/100km 或 kWh/100km）；无区间时为 null
 * @property estimated 没有满箱/满充区间时的累计估算；不适用或不足两条记录时为 null
 * @property amount 区间累计量（L 或 kWh）
 * @property distance 区间累计里程 km
 * @property intervals 本条流的区间（已按统计时间范围过滤）
 */
data class EnergyRate(
    val rate: Double?,
    val estimated: Double?,
    val amount: Double,
    val distance: Double,
    val intervals: List<EnergyInterval>,
)

/**
 * 统计结果（Web `stats` 返回值）。
 *
 * @property records 区间内记录
 * @property total 区间内用车支出
 * @property categories 全量分类聚合（9 类 + 自定义，无记录为 0）
 * @property distance 记录行驶里程（全部 = 当前里程 − 起始里程；区间 = 区间里程差）
 * @property perKm 平均用车成本（元/km），`distance <= 0` 时为 null
 * @property energyRate 主口径能耗（燃油/HEV/PHEV=油耗，纯电=电耗），无有效区间时为 null
 * @property estimatedEnergyRate 主口径的累计估算（纯电无满充区间 / 混动无满箱区间），否则 null
 * @property intervals 主口径的区间（燃油车含手填区间）
 * @property fuelEnergy 燃油流（满箱法 + 手填）；纯电账本为 null
 * @property electricEnergy 电荷流（满电法）；燃油/HEV 账本为 null
 */
data class StatsResult(
    val records: List<Record>,
    val total: Double,
    val categories: List<CategoryValue>,
    val distance: Double,
    val perKm: Double?,
    val energyRate: Double?,
    val estimatedEnergyRate: Double?,
    val intervals: List<EnergyInterval>,
    val fuelEnergy: EnergyRate? = null,
    val electricEnergy: EnergyRate? = null,
)

/** 统计指定时间范围（闭区间，`YYYY-MM-DD`）。 */
fun stats(ledger: Ledger, from: String = "", to: String = "9999-12-31"): StatsResult {
    val records = ledger.records.filter { (from.isEmpty() || it.date >= from) && it.date <= to }
    val total = records.sumOf { costOf(it, ledger) }

    val categories = Categories.options(ledger).map { name ->
        CategoryValue(
            name = name,
            value = records
                .filter { Categories.normalize(it.category) == name }
                .sumOf { costOf(it, ledger) },
        )
    }

    val profile = ledger.energyProfile
    fun inRange(interval: EnergyInterval): Boolean =
        (from.isEmpty() || interval.date >= from) && interval.date <= to

    // 燃油流 = 满箱法 + 历史手填油耗；电荷流 = 满电法。两条流各自只取自己单位的记录。
    val fuelIntervals = if (profile == EnergyProfile.ELECTRIC_ONLY) {
        emptyList()
    } else {
        (energyIntervals(ledger.records, EnergyKind.FUEL) + manualEnergyIntervals(ledger.records))
            .filter(::inRange)
    }
    val electricIntervals = if (profile == EnergyProfile.FUEL_ONLY) {
        emptyList()
    } else {
        energyIntervals(ledger.records, EnergyKind.ELECTRIC).filter(::inRange)
    }

    val fuelEnergy = if (profile == EnergyProfile.ELECTRIC_ONLY) {
        null
    } else {
        buildEnergyRate(
            intervals = fuelIntervals,
            // 燃油车无区间就是无区间（保持历史语义）；混动车才回退累计估算。
            estimated = if (profile == EnergyProfile.DUAL && fuelIntervals.isEmpty()) {
                estimateEnergyRate(records, EnergyKind.FUEL)
            } else {
                null
            },
        )
    }
    val electricEnergy = if (profile == EnergyProfile.FUEL_ONLY) {
        null
    } else {
        buildEnergyRate(
            intervals = electricIntervals,
            estimated = if (electricIntervals.isEmpty()) {
                estimateEnergyRate(records, EnergyKind.ELECTRIC)
            } else {
                null
            },
        )
    }
    val primary = if (profile == EnergyProfile.ELECTRIC_ONLY) electricEnergy else fuelEnergy

    val distance = if (from.isEmpty() && to == "9999-12-31") {
        val ck = currentKm(ledger)
        val odometers = ledger.records.mapNotNull { it.odometer }
        val start = ledger.vehicle.startKm ?: minOf(ck, *(odometers.toDoubleArray()))
        ck - start
    } else {
        val before = ledger.records
            .filter { it.odometer != null && it.date < from }
            .sortedByDescending { it.date }
            .firstOrNull()
        val kms = records.mapNotNull { it.odometer }
        if (kms.isEmpty()) {
            0.0
        } else {
            kms.max() - (before?.odometer ?: kms.min())
        }
    }

    return StatsResult(
        records = records,
        total = total,
        categories = categories,
        distance = distance,
        perKm = if (distance > 0) total / distance else null,
        energyRate = primary?.rate,
        estimatedEnergyRate = primary?.estimated,
        intervals = primary?.intervals.orEmpty(),
        fuelEnergy = fuelEnergy,
        electricEnergy = electricEnergy,
    )
}

/** 由区间列表构造一条能量流的统计（rate 与 estimated 分开，界面自己决定展示哪个）。 */
private fun buildEnergyRate(intervals: List<EnergyInterval>, estimated: Double?): EnergyRate {
    val amount = intervals.sumOf { it.amount }
    val distance = intervals.sumOf { it.distance }
    return EnergyRate(
        rate = if (distance != 0.0) amount / distance * 100.0 else null,
        estimated = estimated,
        amount = amount,
        distance = distance,
        intervals = intervals,
    )
}

/**
 * 累计口径估算：范围内首尾两条「有里程的本类记录」之间的累计量 ÷ 里程差 × 100。
 * 首条记录只作基准（其量不计，与满箱/满电法一致）；不足两条或里程差 ≤ 0 时返回 null。
 *
 * @param kind 燃油读 `liters`，纯电读 `kwh`
 */
fun estimateEnergyRate(records: List<Record>, kind: EnergyKind = EnergyKind.FUEL): Double? {
    val electric = kind == EnergyKind.ELECTRIC
    val amountOf: (Record) -> Double? = if (electric) { r -> r.kwh } else { r -> r.liters }
    val points = records
        .filter { Categories.isEnergy(it.category) && it.kind != "manual" && amountOf(it) != null && it.odometer != null }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })
    if (points.size < 2) return null
    val first = points.first()
    val last = points.last()
    val distance = (last.odometer ?: 0.0) - (first.odometer ?: 0.0)
    if (distance <= 0) return null
    val amount = points.drop(1).sumOf { amountOf(it) ?: 0.0 }
    if (amount <= 0) return null
    return amount / distance * 100.0
}

/** 计入成本的费用笔数（Web 指标卡「N 笔费用」）。 */
fun costedRecordCount(records: List<Record>, ledger: Ledger): Int =
    records.count { costOf(it, ledger) > 0 }
