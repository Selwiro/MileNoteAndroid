package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * 保养里程预测（F5）：按最近的用车强度估算「还有多少天到里程」。
 *
 * 只做**估算**，不猜：数据不足（少于 2 次里程读数、跨度不足 14 天、读数倒挂）一律返回 null，
 * 界面据此隐藏预测，而不是给一个看似精确的日期。
 */

/** 默认回看窗口（天）。 */
const val PREDICTION_WINDOW_DAYS: Int = 90

/** 至少需要的读数跨度（天）：短于此的样本波动太大，不做预测。 */
const val PREDICTION_MIN_SPAN_DAYS: Long = 14

/**
 * 最近 [windowDays] 天的平均日里程（km/天）。
 *
 * 取窗口内首末两条有里程读数的记录：`(末读数 − 首读数) / 首末日期差`。
 * 读数倒挂 / 无位移 / 样本不足返回 null。
 */
fun averageDailyKm(
    ledger: Ledger,
    today: LocalDate = Dates.today(),
    windowDays: Int = PREDICTION_WINDOW_DAYS,
): Double? {
    val from = today.minusDays(windowDays.toLong())
    // 同一天可能加两次油、记两次里程：按当天**最大**读数算，避免「当天最后一笔恰好录得小」
    // 把首末位移算小（甚至算成负的）。
    val readings = ledger.records.mapNotNull { record ->
        val odometer = record.odometer ?: return@mapNotNull null
        if (!odometer.isFinite() || odometer < 0) return@mapNotNull null
        val date = Dates.parse(record.date) ?: return@mapNotNull null
        if (date.isBefore(from) || date.isAfter(today)) return@mapNotNull null
        date to odometer
    }.groupBy { it.first }
        .map { (date, sameDay) -> date to sameDay.maxOf { it.second } }
        .sortedBy { it.first }
    if (readings.size < 2) return null
    val first = readings.first()
    val last = readings.last()
    val days = ChronoUnit.DAYS.between(first.first, last.first)
    if (days < PREDICTION_MIN_SPAN_DAYS) return null
    val distance = last.second - first.second
    if (!distance.isFinite() || distance <= 0) return null
    return distance / days
}

/**
 * 按日均里程估算还有几天到 `dueKm`；已到期 / 无到期里程 / 日均无效返回 null。
 *
 * 结果向上取整（剩 1 公里也算「1 天后」），避免显示「0 天后」却仍不是已到期。
 */
fun predictDueDays(reminder: Reminder, currentKm: Double, dailyKm: Double): Long? {
    val dueKm = reminder.dueKm ?: return null
    if (!dailyKm.isFinite() || dailyKm <= 0) return null
    if (!currentKm.isFinite() || !dueKm.isFinite()) return null
    val left = dueKm - currentKm
    if (left <= 0) return null
    return ceil(left / dailyKm).toLong()
}

/** 按日均里程估算到达 `dueKm` 的日期；不可估算返回 null。 */
fun predictDueDate(
    reminder: Reminder,
    currentKm: Double,
    dailyKm: Double,
    today: LocalDate = Dates.today(),
): LocalDate? = predictDueDays(reminder, currentKm, dailyKm)?.let { today.plusDays(it) }
