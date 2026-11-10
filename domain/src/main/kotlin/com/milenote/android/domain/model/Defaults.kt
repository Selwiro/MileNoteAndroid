package com.milenote.android.domain.model

/**
 * Web `blank()` 的车辆默认值。
 *
 * @param powertrain 动力类型；`"ev"` / `"phev"` 时不写旧版燃油口径字段
 *   （`fuelCostSource`），否则旧版小熊/钱迹口径会把电费或新导入的加油记录判成 0。
 *   纯电同时不写 `fuelGrade`。
 */
fun blankVehicle(powertrain: String? = null): Vehicle {
    val key = normalizePowertrain(powertrain)
    val electricProfile = key == POWERTRAIN_EV || key == POWERTRAIN_PHEV
    return Vehicle(
        name = "我的爱车",
        plate = "",
        model = "",
        startKm = null,
        currentKm = 0.0,
        fuelCostSource = if (electricProfile) null else "bear",
        // 燃油用 null 表示（老备份/老版本兼容，JSON 不新增键）；ev / phev 显式写入。
        powertrain = key?.takeUnless { it == POWERTRAIN_FUEL },
    )
}

/** Web `blank()` 的空白账本。 */
fun blankLedger(powertrain: String? = null): Ledger = Ledger(
    version = 1,
    vehicle = blankVehicle(powertrain),
    records = emptyList(),
    reminders = emptyList(),
)
