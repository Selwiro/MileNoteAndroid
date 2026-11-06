package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Reminder
import java.time.LocalDate

/** 提醒等级（Web `reminderState().level`）。 */
enum class ReminderLevel(val id: String) {
    /** 已完成。 */
    DONE("done"),

    /** 已到期（日期或里程任一到期）。 */
    OVERDUE("overdue"),

    /** 即将到期（≤ 30 天或 ≤ 500 km）。 */
    SOON("soon"),

    /** 正常。 */
    NORMAL("normal"),
}

/** 提醒状态。 */
data class ReminderState(
    /** 距到期日天数；无到期日为 null。 */
    val days: Long?,
    /** 距到期里程（还可行驶公里）；无里程到期为 null。 */
    val left: Double?,
    val level: ReminderLevel,
)

/**
 * 提醒状态（Web `reminderState`）：日期或里程任一到期即为过期；
 * 已完成优先返回 `done`。
 */
fun reminderState(reminder: Reminder, km: Double, date: LocalDate = Dates.today()): ReminderState {
    val dueDate = Dates.parse(reminder.dueDate)
    val days = dueDate?.let { Dates.daysBetween(date, it) }
    val left = reminder.dueKm?.let { it - km }
    val level = when {
        reminder.done -> ReminderLevel.DONE
        (days != null && days <= 0) || (left != null && left <= 0) -> ReminderLevel.OVERDUE
        (days != null && days <= 30) || (left != null && left <= 500) -> ReminderLevel.SOON
        else -> ReminderLevel.NORMAL
    }
    return ReminderState(days = days, left = left, level = level)
}
