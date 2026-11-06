package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record

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
 * @property fuelRate 里程加权综合油耗，无有效区间时为 null
 * @property intervals 区间内有效油耗区间（满箱法 + 手填）
 */
data class StatsResult(
    val records: List<Record>,
    val total: Double,
    val categories: List<CategoryValue>,
    val distance: Double,
    val perKm: Double?,
    val fuelRate: Double?,
    val intervals: List<FuelInterval>,
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

    val allIntervals = fuelIntervals(ledger.records) + manualFuelIntervals(ledger.records)
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

    val distanceFuel = intervals.sumOf { it.distance }
    val fuelLiters = intervals.sumOf { it.liters }

    return StatsResult(
        records = records,
        total = total,
        categories = categories,
        distance = distance,
        perKm = if (distance > 0) total / distance else null,
        fuelRate = if (distanceFuel != 0.0) fuelLiters / distanceFuel * 100.0 else null,
        intervals = intervals,
    )
}

/** 计入成本的费用笔数（Web 指标卡「N 笔费用」）。 */
fun costedRecordCount(records: List<Record>, ledger: Ledger): Int =
    records.count { costOf(it, ledger) > 0 }
