package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record

/**
 * 能量种类：一条记录只能是其中一种（`liters` 或 `kwh`）。
 *
 * PHEV / 增程同一账本里两类记录并存，计算时必须按种类分流，
 * **不能把"升"和"度"混进同一个分子**（量纲不同、且两条流覆盖同一段里程）。
 */
enum class EnergyKind { FUEL, ELECTRIC }

/**
 * 一个能耗区间（燃油=满箱法，纯电=满电法；手填记录单独成区间）。
 *
 * [rate] 单位随 [EnergyKind]：燃油为 L/100km，纯电为 kWh/100km；
 * [amount] 为区间累计能源量（燃油 L / 纯电 kWh）。
 */
data class EnergyInterval(
    /** 区间结束（本次加满/充满）日期。 */
    val date: String,
    /** 区间行驶里程 km。 */
    val distance: Double,
    /** 区间累计能源量：燃油 L，纯电 kWh。 */
    val amount: Double,
    /** 区间能耗（L/100km 或 kWh/100km）。 */
    val rate: Double,
    /** `满箱法` / `满电法` / `手动`。 */
    val source: String,
)

/**
 * 满箱法 / 满电法能耗区间。
 *
 * 规则（与燃油版完全一致，只按动力类型取能量字段）：
 * 第一次加满/充满只建立基准（起点油量/电量不计）；中途未加满的能源量累计到下次加满；
 * `missed`（此前漏记）会重新建立基准；里程差 ≤ 0 的区间不产出。
 * 历史手填能耗（`kind == "manual"`）不参与本函数。
 *
 * @param kind 能量种类：读 `Record.liters`（燃油）或 `Record.kwh`（纯电）。
 *   只统计本种类的记录：混动车切换/混排时，另一种单位的记录不会串进来重算。
 */
fun energyIntervals(records: List<Record>, kind: EnergyKind = EnergyKind.FUEL): List<EnergyInterval> {
    val electric = kind == EnergyKind.ELECTRIC
    val amountOf: (Record) -> Double? = if (electric) { r -> r.kwh } else { r -> r.liters }
    val sorted = records
        .filter { Categories.isEnergy(it.category) && it.kind != "manual" && amountOf(it) != null }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })

    val sourceLabel = if (electric) "满电法" else "满箱法"
    var anchor: Record? = null
    var amount = 0.0
    val result = mutableListOf<EnergyInterval>()
    for (r in sorted) {
        if (r.missed) {
            val odometer = r.odometer
            val rAmount = amountOf(r)
            anchor = if (r.full && odometer != null && rAmount != null && rAmount != 0.0) r else null
            amount = 0.0
            continue
        }
        val odometer = r.odometer
        val currentAmount = amountOf(r)
        if (odometer == null || currentAmount == null || currentAmount == 0.0) {
            anchor = null
            amount = 0.0
            continue
        }
        val currentAnchor = anchor
        if (currentAnchor != null) {
            amount += currentAmount
            if (r.full) {
                val distance = odometer - (currentAnchor.odometer ?: odometer)
                if (distance > 0) {
                    result += EnergyInterval(
                        date = r.date,
                        distance = distance,
                        amount = amount,
                        rate = amount / distance * 100.0,
                        source = sourceLabel,
                    )
                }
                anchor = r
                amount = 0.0
            }
        } else if (r.full) {
            anchor = r
        }
    }
    return result
}

/** 旧调用点兼容：布尔版 `energyIntervals`（true=纯电）。 */
@Deprecated("Use energyIntervals(records, kind)", ReplaceWith("energyIntervals(records, if (ev) EnergyKind.ELECTRIC else EnergyKind.FUEL)"))
fun energyIntervals(records: List<Record>, ev: Boolean): List<EnergyInterval> =
    energyIntervals(records, if (ev) EnergyKind.ELECTRIC else EnergyKind.FUEL)

/** 手填能耗转成的加权区间（`kind == "manual"`；单位随车：历史 Web 数据按 L/100km 处理）。 */
fun manualEnergyIntervals(records: List<Record>): List<EnergyInterval> = records
    .filter { it.kind == "manual" && (it.distance ?: 0.0) > 0 && (it.rate ?: 0.0) > 0 }
    .map {
        val distance = it.distance ?: 0.0
        val rate = it.rate ?: 0.0
        EnergyInterval(
            date = it.date,
            distance = distance,
            amount = distance * rate / 100.0,
            rate = rate,
            source = "手动",
        )
    }

/**
 * 每条「区间结束」加满/充满记录对应的百公里能耗（满箱法/满电法），以及手填能耗记录。
 *
 * - 与 [energyIntervals] 使用同一套累计规则：只有加满/充满并产生正里程差的记录才会得到能耗；
 *   未加满 / 首次建立基准 / 里程差 ≤ 0 的记录不出现在结果中。
 * - 手填记录（`kind == "manual"` 且 rate > 0）直接返回其 rate。
 *
 * @return record.id → L/100km 或 kWh/100km
 */
fun energyRateByRecord(records: List<Record>, kind: EnergyKind = EnergyKind.FUEL): Map<String, Double> {
    val electric = kind == EnergyKind.ELECTRIC
    val result = LinkedHashMap<String, Double>()
    records.filter { it.kind == "manual" }
        .forEach { record ->
            val rate = record.rate ?: return@forEach
            if (rate > 0 && (record.distance ?: 0.0) > 0) result[record.id] = rate
        }
    val amountOf: (Record) -> Double? = if (electric) { r -> r.kwh } else { r -> r.liters }
    val sorted = records
        .filter { Categories.isEnergy(it.category) && it.kind != "manual" && amountOf(it) != null }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })
    var anchor: Record? = null
    var amount = 0.0
    for (r in sorted) {
        if (r.missed) {
            val odometer = r.odometer
            val rAmount = amountOf(r)
            anchor = if (r.full && odometer != null && rAmount != null && rAmount != 0.0) r else null
            amount = 0.0
            continue
        }
        val odometer = r.odometer
        val currentAmount = amountOf(r)
        if (odometer == null || currentAmount == null || currentAmount == 0.0) {
            anchor = null
            amount = 0.0
            continue
        }
        val currentAnchor = anchor
        if (currentAnchor != null) {
            amount += currentAmount
            if (r.full) {
                val distance = odometer - (currentAnchor.odometer ?: odometer)
                if (distance > 0) result[r.id] = amount / distance * 100.0
                anchor = r
                amount = 0.0
            }
        } else if (r.full) {
            anchor = r
        }
    }
    return result
}

/** 旧调用点兼容：布尔版 `energyRateByRecord`（true=纯电）。 */
@Deprecated("Use energyRateByRecord(records, kind)", ReplaceWith("energyRateByRecord(records, if (ev) EnergyKind.ELECTRIC else EnergyKind.FUEL)"))
fun energyRateByRecord(records: List<Record>, ev: Boolean): Map<String, Double> =
    energyRateByRecord(records, if (ev) EnergyKind.ELECTRIC else EnergyKind.FUEL)
