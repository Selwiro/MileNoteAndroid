package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupPayload
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
