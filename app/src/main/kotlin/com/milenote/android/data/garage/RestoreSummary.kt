package com.milenote.android.data.garage

import com.milenote.android.i18n.I18n
import com.milenote.android.domain.backup.BackupPayload
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Garage

/**
 * 恢复备份后的数量摘要（纯函数，[GarageRepository.restoreBackup] 与单测共用）。
 *
 * 口径：
 * - v2 多车备份：车辆数取 payload 里的车辆数，记录/提醒取 payload 各车求和
 *   （恢复是合并，结果可能比 payload 多，但提示只报本次备份带来的数量）；
 * - v1 旧版单车备份：记录/提醒取 payload 账本计数，车辆数取合并后车库的车辆数。
 */
internal fun summarizeRestore(payload: BackupPayload, merged: Garage): RestoreSummary = when (payload) {
    is BackupPayload.LegacyLedger -> RestoreSummary(
        cars = merged.cars.size,
        records = payload.ledger.records.size,
        reminders = payload.ledger.reminders.size,
    )
    is BackupPayload.MulticarGarage -> RestoreSummary(
        cars = payload.garage.cars.size,
        records = payload.garage.cars.sumOf { it.records.size },
        reminders = payload.garage.cars.sumOf { it.reminders.size },
    )
}

/**
 * 恢复结果摘要文案（UX3）。
 *
 * 恢复后会自动把当前车切到第一辆有内容的车（[GarageOps.activeCarWithRecords]），
 * 摘要要把这件事说出来：否则用户看到「已恢复」但不知道当前车已经换了。
 * 纯函数，单测直接覆盖中英 / 有切换 / 无切换。
 */
internal fun restoreSummaryMessage(summary: RestoreSummary): String {
    // 1.0 UX 走查：英文以前一律复数，「1 vehicles / 1 reminders」读起来就不对。
    val base = tr(
        "已恢复备份：%s · %s · %s",
        "Backup restored: %s · %s · %s",
        vehiclesText(summary.cars),
        recordsText(summary.records),
        remindersText(summary.reminders),
    )
    val repaired = if (summary.repairedDuplicates > 0) {
        tr(
            " · 修正 %d 条重复编号",
            " · fixed %d duplicate IDs",
            summary.repairedDuplicates,
        )
    } else {
        ""
    }
    val withRepaired = base + repaired
    val switched = summary.switchedTo ?: return withRepaired
    return tr(
        "%s（已切换到「%s」）",
        "%s (switched to \"%s\")",
        withRepaired, switched,
    )
}

/** 车辆数量：英文按单复数，中文量词不变。 */
private fun vehiclesText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 vehicle" else "$count vehicles"
    } else {
        "$count 辆车"
    }

/** 记录数量。 */
private fun recordsText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 record" else "$count records"
    } else {
        "$count 条记录"
    }

/** 提醒数量。 */
private fun remindersText(count: Int): String =
    if (I18n.english) {
        if (count == 1) "1 reminder" else "$count reminders"
    } else {
        "$count 条提醒"
    }
