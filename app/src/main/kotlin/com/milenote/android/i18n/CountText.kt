package com.milenote.android.i18n

/**
 * 数量短语：英文按 1 / 多分单复数，中文量词不变（1.0 UX 走查）。
 *
 * 以前英文界面到处是「1 vehicles / 1 reminders」，读起来就不对。中文量词本身没有单复数，
 * 所以中文分支直接写全（`$count 辆`），英文分支分 one / many。
 */
fun vehiclesText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 vehicle" else "$count vehicles"
    } else {
        "$count 辆车"
    }

/** 记录数量：`1 record` / `2 records` / `3 条记录`。 */
fun recordsText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 record" else "$count records"
    } else {
        "$count 条记录"
    }

/** 提醒数量：`1 reminder` / `2 reminders` / `3 条提醒`。 */
fun remindersText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 reminder" else "$count reminders"
    } else {
        "$count 条提醒"
    }

/** 只出数量的「N 辆」短文案（英文 `N vehicle(s)`）。 */
fun vehicleCountText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 vehicle" else "$count vehicles"
    } else {
        "$count 辆"
    }
