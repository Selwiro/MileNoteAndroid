package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.isEv

/** 某分类的统计金额。 */
data class CategoryValue(val name: String, val value: Double)

/**
 * 统计结果（Web `stats` 返回值）。
 *
 * @property records 区间内记录
 * @property total 区间内用车支出
 * @property categories 全量分类聚合（9 类 + 自定义，无记录为 0）
 * @property distance 记录行驶里程（全部 = 当前里程 − 起始里程；区间 = 区间里程差）
 * @property perKm 平均用车成本（元/km），`distance <= 0` 时为 null
 * @property energyRate 里程加权综合能耗（满箱法/满电法 + 手填），无有效区间时为 null；
 *   燃油单位 L/100km，纯电单位 kWh/100km
 * @property estimatedEnergyRate 纯电且无满电区间时的累计估算能耗（kWh/100km），否则 null
 * @property intervals 区间内有效能耗区间（满箱法 + 手填）
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

    val ev = ledger.isEv
    // 手填能耗（kind == manual）是燃油历史字段；纯电只统计 kWh 满电区间。
    val allIntervals = if (ev) {
        energyIntervals(ledger.records, ev = true)
    } else {
        energyIntervals(ledger.records) + manualEnergyIntervals(ledger.records)
    }
    val intervals = allIntervals.filter { (from.isEmpty() || it.date >= from) && it.date <= to }

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

    val distanceEnergy = intervals.sumOf { it.distance }
    val intervalAmount = intervals.sumOf { it.amount }

    return StatsResult(
        records = records,
        total = total,
        categories = categories,
        distance = distance,
        perKm = if (distance > 0) total / distance else null,
        energyRate = if (distanceEnergy != 0.0) intervalAmount / distanceEnergy * 100.0 else null,
        estimatedEnergyRate = if (ev && intervals.isEmpty()) estimateEnergyRate(records) else null,
        intervals = intervals,
    )
}

/**
 * 纯电累计口径估算：范围内首尾两条「有里程的充电记录」之间的累计充电量 ÷ 里程差 × 100。
 * 首条记录只作基准（其充电量不计，与满电法一致）；不足两条或里程差 ≤ 0 时返回 null。
 */
fun estimateEnergyRate(records: List<Record>): Double? {
    val points = records
        .filter { Categories.isEnergy(it.category) && it.kind != "manual" && it.kwh != null && it.odometer != null }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })
    if (points.size < 2) return null
    val first = points.first()
    val last = points.last()
    val distance = (last.odometer ?: 0.0) - (first.odometer ?: 0.0)
    if (distance <= 0) return null
    val amount = points.drop(1).sumOf { it.kwh ?: 0.0 }
    if (amount <= 0) return null
    return amount / distance * 100.0
}

/** 计入成本的费用笔数（Web 指标卡「N 笔费用」）。 */
fun costedRecordCount(records: List<Record>, ledger: Ledger): Int =
    records.count { costOf(it, ledger) > 0 }
