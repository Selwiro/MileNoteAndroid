package com.milenote.android.domain.calc

import java.time.LocalDate

/**
 * 车辆陪伴天数（Web `companionDays`）。
 *
 * 起止日期都必须是严格合法的 `YYYY-MM-DD`，且起始日算第 1 天；
 * 未来 / 非法输入返回 null。
 */
object Companion {
    fun days(start: String?, end: LocalDate = Dates.today()): Int? {
        val endText = Dates.iso(end)
        return days(start, endText)
    }

    fun days(start: String?, end: String?): Int? {
        if (!Dates.isValid(start) || !Dates.isValid(end)) return null
        if (start!! > end!!) return null
        val diff = Dates.daysBetween(start, end) ?: return null
        return Math.round(diff + 1.0).toInt()
    }
}
