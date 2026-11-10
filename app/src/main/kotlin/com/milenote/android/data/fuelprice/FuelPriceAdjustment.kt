package com.milenote.android.data.fuelprice

import java.time.LocalDate

/**
 * 把油价站点的调价文案换算成展示文案。
 *
 * 站点原文是「下次油价 11 月 12 日 24 时调整」：11 月 12 日 24:00（即 13 日 0 时）生效。
 * 展示沿用原文当天「11 月 12 日 24 时」，**不能顺延到 13 日**——13 日 24 时是 14 日 0 时，晚了一天。
 *
 * 站点只发布“下一次”调价，正常在 0–14 天内；放宽到 31 天兼容长假窗口。
 * 页面还没更新、生效时刻已过时返回 null，由界面显示「时间待公布」，避免把上一轮当成下一轮。
 *
 * @param month 站点文案里的月份
 * @param day 站点文案里的日
 * @param today 当前日期（上海时区），测试可注入
 */
internal fun nextAdjustmentLabel(month: Int, day: Int, today: LocalDate): String? {
    if (month !in 1..12 || day !in 1..31) return null
    val latestAhead = today.plusDays(31)
    val target = ((today.year)..(today.year + 1))
        .mapNotNull { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull() }
        // 24 时属于当天：次日 0 时才生效。
        .firstOrNull { it.plusDays(1).isAfter(today) && !it.isAfter(latestAhead) }
        ?: return null
    // 统一返回中文站点口径（「12 日 24 时」）：油价卡只在中文界面展示，英文界面整卡隐藏，
    // 所以这里不做语言切换——否则抓取发生在语言偏好加载完成前时，缓存里会留下英文格式。
    return "${target.monthValue} 月 ${target.dayOfMonth} 日 24 时"
}
