package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.isEv

/**
 * 能源费成本口径（Web `costOf`，顺序敏感）。
 *
 * 1. 非能源费：金额原样计入；
 * 2. 能源费且 `fuelCounted` 为布尔：按该值计 / 不计（新导入统一走这条）；
 * 3. 纯电记录（有 `kwh`）或纯电账本：**不走**旧版小熊/钱迹口径，直接按金额计入
 *    （旧口径用 `liters > 0` 判小熊来源，纯电记录没有升数会被误判为 0）；
 * 4. 其余燃油记录回退旧口径 `vehicle.fuelCostSource`：非小熊 / 钱迹来源直接计金额，
 *    小熊口径只认小熊来源（或带升数），钱迹口径只认钱迹来源。
 */
fun costOf(record: Record, ledger: Ledger): Double {
    if (!Categories.isEnergy(record.category)) return record.amount
    val counted = record.fuelCounted
    if (counted != null) return if (counted) record.amount else 0.0
    if (record.kwh != null || ledger.isEv) return record.amount
    val basis = ledger.vehicle.fuelCostSource ?: "combined"
    if (record.source != "小熊油耗" && record.source != "钱迹") return record.amount
    val fromQianji = record.source == "钱迹" ||
        record.importedKeys.orEmpty().any { it.startsWith("钱迹:") }
    val fromBear = record.source == "小熊油耗" || (record.liters ?: 0.0) > 0
    if (basis == "qianji" && !fromQianji) return 0.0
    if (basis == "bear" && !fromBear) return 0.0
    return record.amount
}

/** 当前总里程（Web `currentKm`）：车辆当前值、起始值、所有记录里程取最大。 */
fun currentKm(ledger: Ledger): Double {
    var max = maxOf(ledger.vehicle.currentKm, ledger.vehicle.startKm ?: 0.0)
    for (r in ledger.records) {
        val odometer = r.odometer ?: continue
        if (odometer.isFinite() && odometer > max) max = odometer
    }
    return max
}
