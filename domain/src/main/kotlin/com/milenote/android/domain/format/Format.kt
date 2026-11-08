package com.milenote.android.domain.format

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReminderState
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Reminder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 纯 Kotlin 展示格式化，与 Web `toLocaleString('zh-CN')` 输出一致。
 *
 * 不依赖系统 Locale，保证单测在任何设备上稳定。
 */
object Format {

    /** 英文星期缩写，索引与 [LocalDate.dayOfWeek] 同序（周日在前）。 */
    private val WEEKDAY_EN = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** 金额：两位小数 + 千分位，如 `10,511.00`（Web `money`）。 */
    fun money(value: Double?): String = group(scale(value, 2))

    /** 普通数字：最多一位小数 + 千分位，如 `42,000`、`6.3`（Web `fmt`）。 */
    fun fmt(value: Double?): String {
        val bd = scale(value, 1).stripTrailingZeros()
        return group(bd)
    }

    /** 日期显示：`2026-11-04` → `2026.11.04`（Web 顶栏 / 记录日期）。 */
    fun dateDots(date: String): String = date.replace('-', '.')

    /** 星期几，如中文 `星期三` / 英文 `Wed`。 */
    fun weekday(date: LocalDate): String =
        tr(Dates.weekday(date), WEEKDAY_EN[date.dayOfWeek.value % 7])

    /** 顶栏日期文案：`2026.11.04 &nbsp; 星期三` 的纯文本版。 */
    fun todayLabel(date: LocalDate): String = "${dateDots(Dates.iso(date))} ${weekday(date)}"

    /**
     * 保养周期文案（Web 计划表 `cycle`）：
     * `10,000 公里 或 12 个月`；12 的整数倍显示 `N 年`；无周期显示「单次事项」。
     */
    fun cycleText(
        intervalKm: Double?,
        intervalMonths: Int?,
        distanceUnit: String = tr("公里", "km"),
    ): String {
        val parts = mutableListOf<String>()
        if (intervalKm != null && intervalKm != 0.0) parts.add(tr("%s %s", "%s %s", fmt(intervalKm), distanceUnit))
        if (intervalMonths != null && intervalMonths != 0) {
            parts.add(
                if (intervalMonths % 12 == 0) {
                    val years = intervalMonths / 12
                    if (years == 1) tr("1 年", "1 year") else tr("%d 年", "%d years", years)
                } else if (intervalMonths == 1) {
                    tr("1 个月", "1 month")
                } else {
                    tr("%d 个月", "%d months", intervalMonths)
                },
            )
        }
        return if (parts.isEmpty()) tr("单次事项", "One-off") else parts.joinToString(tr(" 或 ", " or "))
    }

    /**
     * 提醒到期文案（Web 提醒卡副标题）：日期剩余天数 + 里程剩余公里，
     * 两行用换行分隔。
     */
    fun reminderDeadline(
        reminder: Reminder,
        state: ReminderState,
        distanceUnit: String = tr("公里", "km"),
    ): String {
        val parts = mutableListOf<String>()
        val dueDate = reminder.dueDate
        if (!dueDate.isNullOrEmpty()) {
            val days = state.days
            parts.add(
                if (days == null) {
                    dueDate
                } else if (days >= 0) {
                    tr("%s · 还有 %d 天", "%s · %d days left", dueDate, days)
                } else {
                    tr("%s · 已过 %d 天", "%s · %d days overdue", dueDate, -days)
                },
            )
        }
        val dueKm = reminder.dueKm
        if (dueKm != null) {
            val left = state.left
            parts.add(
                when {
                    left == null -> tr("%s %s", "%s %s", fmt(dueKm), distanceUnit)
                    left >= 0 -> tr(
                        "%s %s · 还可行驶 %s %s",
                        "%s %s · %s %s left",
                        fmt(dueKm),
                        distanceUnit,
                        fmt(left),
                        distanceUnit,
                    )
                    else -> tr(
                        "%s %s · 已超出 %s %s",
                        "%s %s · over by %s %s",
                        fmt(dueKm),
                        distanceUnit,
                        fmt(-left),
                        distanceUnit,
                    )
                },
            )
        }
        return parts.joinToString("\n")
    }

    /** 里程行：`42,000 公里`（单位由调用方传入，默认公里/km）。 */
    fun km(value: Double?, distanceUnit: String = tr("公里", "km")): String =
        tr("%s %s", "%s %s", fmt(value), distanceUnit)

    private fun scale(value: Double?, digits: Int): BigDecimal {
        val v = value ?: 0.0
        if (!v.isFinite()) return BigDecimal.ZERO
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP)
    }

    private fun group(value: BigDecimal): String {
        val plain = value.toPlainString()
        val negative = plain.startsWith("-")
        val unsigned = if (negative) plain.substring(1) else plain
        val dot = unsigned.indexOf('.')
        val intPart = if (dot >= 0) unsigned.substring(0, dot) else unsigned
        val fraction = if (dot >= 0) unsigned.substring(dot) else ""
        val grouped = StringBuilder()
        for ((i, ch) in intPart.withIndex()) {
            if (i > 0 && (intPart.length - i) % 3 == 0) grouped.append(',')
            grouped.append(ch)
        }
        return (if (negative) "-" else "") + grouped + fraction
    }
}
