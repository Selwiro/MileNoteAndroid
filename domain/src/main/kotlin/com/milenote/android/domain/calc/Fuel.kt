package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record

/** 一个满箱法（或手填）油耗区间。 */
data class FuelInterval(
    /** 区间结束（本次加满）日期。 */
    val date: String,
    /** 区间行驶里程 km。 */
    val distance: Double,
    /** 区间累计加油量 L。 */
    val liters: Double,
    /** 区间油耗 L/100km。 */
    val rate: Double,
    /** `满箱法` 或 `手动`。 */
    val source: String,
)

/**
 * 满箱法油耗（Web `fuelIntervals`）。
 *
 * 规则：第一次加满只建立基准（起点油量不计）；中途未加满的油量累计到下次加满；
 * `missed`（此前漏记）会重新建立基准；里程差 ≤ 0 的区间不产出。
 * 历史手填油耗（`kind == "manual"`）不参与本函数。
 */
fun fuelIntervals(records: List<Record>): List<FuelInterval> {
    val sorted = records
        .filter {
            it.category == "油费" && it.kind != "manual" && (it.liters != null || it.odometer != null)
        }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })

    var anchor: Record? = null
    var liters = 0.0
    val result = mutableListOf<FuelInterval>()
    for (r in sorted) {
        if (r.missed) {
            val odometer = r.odometer
            val rLiters = r.liters
            anchor = if (r.full && odometer != null && rLiters != null && rLiters != 0.0) r else null
            liters = 0.0
            continue
        }
        val odometer = r.odometer
        val currentLiters = r.liters
        if (odometer == null || currentLiters == null || currentLiters == 0.0) {
            anchor = null
            liters = 0.0
            continue
        }
        val currentAnchor = anchor
        if (currentAnchor != null) {
            liters += currentLiters
            if (r.full) {
                val distance = odometer - (currentAnchor.odometer ?: odometer)
                if (distance > 0) {
                    result += FuelInterval(
                        date = r.date,
                        distance = distance,
                        liters = liters,
                        rate = liters / distance * 100.0,
                        source = "满箱法",
                    )
                }
                anchor = r
                liters = 0.0
            }
        } else if (r.full) {
            anchor = r
        }
    }
    return result
}

/** 手填油耗转成的加权区间（Web `stats` 中 `kind === 'manual'` 的部分）。 */
fun manualFuelIntervals(records: List<Record>): List<FuelInterval> = records
    .filter { it.kind == "manual" && (it.distance ?: 0.0) > 0 && (it.rate ?: 0.0) > 0 }
    .map {
        val distance = it.distance ?: 0.0
        val rate = it.rate ?: 0.0
        FuelInterval(
            date = it.date,
            distance = distance,
            liters = distance * rate / 100.0,
            rate = rate,
            source = "手动",
        )
    }

/**
 * 每条「区间结束」加油记录对应的百公里油耗（满箱法），以及手填油耗记录。
 *
 * - 与 [fuelIntervals] 使用同一套满箱法累计规则：只有加满并产生正里程差的记录才会得到油耗；
 *   未加满 / 首次建立基准 / 里程差 ≤ 0 的记录不出现在结果中。
 * - 手填记录（`kind == "manual"` 且 rate > 0）直接返回其 rate。
 *
 * @return record.id → L/100km
 */
fun fuelRateByRecord(records: List<Record>): Map<String, Double> {
    val result = LinkedHashMap<String, Double>()
    records.filter { it.kind == "manual" }
        .forEach { record ->
            val rate = record.rate ?: return@forEach
            if (rate > 0 && (record.distance ?: 0.0) > 0) result[record.id] = rate
        }
    val sorted = records
        .filter {
            it.category == "油费" && it.kind != "manual" && (it.liters != null || it.odometer != null)
        }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })
    var anchor: Record? = null
    var liters = 0.0
    for (r in sorted) {
        if (r.missed) {
            val odometer = r.odometer
            val rLiters = r.liters
            anchor = if (r.full && odometer != null && rLiters != null && rLiters != 0.0) r else null
            liters = 0.0
            continue
        }
        val odometer = r.odometer
        val currentLiters = r.liters
        if (odometer == null || currentLiters == null || currentLiters == 0.0) {
            anchor = null
            liters = 0.0
            continue
        }
        val currentAnchor = anchor
        if (currentAnchor != null) {
            liters += currentLiters
            if (r.full) {
                val distance = odometer - (currentAnchor.odometer ?: odometer)
                if (distance > 0) result[r.id] = liters / distance * 100.0
                anchor = r
                liters = 0.0
            }
        } else if (r.full) {
            anchor = r
        }
    }
    return result
}
