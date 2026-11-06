package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import java.time.LocalDate

/** 月度用量（Web `monthlyUsage` 行）：费用求和 + 按读数分摊的里程。 */
data class MonthUsage(
    /** `YYYY-MM`。 */
    val key: String,
    /** 展示文案，如 `11 月`。 */
    val label: String,
    /** 该月费用合计。 */
    val cost: Double,
    /** 该月推算行驶里程；无读数覆盖时为 null（不伪造）。 */
    val distance: Double?,
)

private class MutableMonth(
    val key: String,
    val label: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
) {
    var cost: Double = 0.0
    var distance: Double? = null
}

/**
 * 月度用车趋势（Web `monthlyUsage`，Web 端未接 UI）。
 *
 * 以有日期的总里程读数为节点，相邻读数之间的里程按天分摊、跨月按重叠比例拆开；
 * 读数倒退时跳过；提车日期 + 起始里程只在「无当日读数且所有读数都晚于提车日」时补基准。
 */
fun monthlyUsage(
    ledger: Ledger,
    date: LocalDate = Dates.today(),
    count: Int = 6,
): List<MonthUsage> {
    val dateText = Dates.iso(date)
    val firstOfMonth = date.withDayOfMonth(1)
    val months = (count - 1 downTo 0).map { offset ->
        val month = firstOfMonth.minusMonths(offset.toLong())
        MutableMonth(
            key = "%04d-%02d".format(month.year, month.monthValue),
            label = "${month.monthValue} 月",
            startEpochDay = month.toEpochDay(),
            endEpochDay = month.plusMonths(1).toEpochDay(),
        )
    }

    val readings = mutableMapOf<String, Double>()
    for (r in ledger.records) {
        if (!Dates.isValid(r.date) || r.date > dateText) continue
        months.firstOrNull { r.date.startsWith(it.key) }?.let { it.cost += costOf(r, ledger) }
        val odometer = r.odometer
        if (odometer != null && odometer.isFinite() && odometer >= 0) {
            readings[r.date] = maxOf(readings[r.date] ?: 0.0, odometer)
        }
    }

    val baseline = ledger.vehicle.companionDate
    val startKm = ledger.vehicle.startKm
    if (Dates.isValid(baseline) && baseline!! <= dateText && startKm != null &&
        !readings.containsKey(baseline) && readings.keys.all { it > baseline }
    ) {
        readings[baseline] = startKm
    }

    var prevDay: Long? = null
    var prevKm = 0.0
    for ((day, km) in readings.toSortedMap()) {
        val time = Dates.parse(day)?.toEpochDay() ?: continue
        if (prevDay != null && km < prevKm) continue
        val previous = prevDay
        if (previous != null && time > previous) {
            for (m in months) {
                val overlap = minOf(time, m.endEpochDay) - maxOf(previous, m.startEpochDay)
                if (overlap > 0) {
                    m.distance = (m.distance ?: 0.0) + (km - prevKm) * overlap.toDouble() / (time - previous)
                }
            }
        }
        prevDay = time
        prevKm = km
    }

    return months.map { MonthUsage(it.key, it.label, it.cost, it.distance) }
}
