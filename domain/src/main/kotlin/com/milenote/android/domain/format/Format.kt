package com.milenote.android.domain.format

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReminderState
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

    /** 金额：两位小数 + 千分位，如 `10,511.00`（Web `money`）。 */
    fun money(value: Double?): String = group(scale(value, 2))

    /** 普通数字：最多一位小数 + 千分位，如 `42,000`、`6.3`（Web `fmt`）。 */
    fun fmt(value: Double?): String {
        val bd = scale(value, 1).stripTrailingZeros()
        return group(bd)
    }

    /** 日期显示：`2026-11-04` → `2026.11.04`（Web 顶栏 / 记录日期）。 */
    fun dateDots(date: String): String = date.replace('-', '.')

    /** 中文星期几，如 `星期三`。 */
    fun weekday(date: LocalDate): String = Dates.weekday(date)

    /** 顶栏日期文案：`2026.11.04 &nbsp; 星期三` 的纯文本版。 */
    fun todayLabel(date: LocalDate): String = "${dateDots(Dates.iso(date))} ${weekday(date)}"

    /**
     * 保养周期文案（Web 计划表 `cycle`）：
     * `10,000 公里 或 12 个月`；12 的整数倍显示 `N 年`；无周期显示「单次事项」。
     */
    fun cycleText(intervalKm: Double?, intervalMonths: Int?): String {
        val parts = mutableListOf<String>()
        if (intervalKm != null && intervalKm != 0.0) parts.add("${fmt(intervalKm)} 公里")
        if (intervalMonths != null && intervalMonths != 0) {
            parts.add(if (intervalMonths % 12 == 0) "${intervalMonths / 12} 年" else "$intervalMonths 个月")
        }
        return if (parts.isEmpty()) "单次事项" else parts.joinToString(" 或 ")
    }

    /**
     * 提醒到期文案（Web 提醒卡副标题）：日期剩余天数 + 里程剩余公里，
     * 两行用换行分隔。
     */
    fun reminderDeadline(reminder: Reminder, state: ReminderState): String {
        val parts = mutableListOf<String>()
        val dueDate = reminder.dueDate
        if (!dueDate.isNullOrEmpty()) {
            val days = state.days
            parts.add(
                if (days == null) {
                    dueDate
                } else if (days >= 0) {
                    "$dueDate · 还有 $days 天"
                } else {
                    "$dueDate · 已过 ${-days} 天"
                },
            )
        }
        val dueKm = reminder.dueKm
        if (dueKm != null) {
            val left = state.left
            parts.add(
                when {
                    left == null -> "${fmt(dueKm)} 公里"
                    left >= 0 -> "${fmt(dueKm)} 公里 · 还可行驶 ${fmt(left)} 公里"
                    else -> "${fmt(dueKm)} 公里 · 已超出 ${fmt(-left)} 公里"
                },
            )
        }
        return parts.joinToString("\n")
    }

    /** 里程行：`42,000 公里`。 */
    fun km(value: Double?): String = "${fmt(value)} 公里"

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
