package com.milenote.android.domain.model

/**
 * Web `blank()` 的车辆默认值。
 *
 * @param powertrain 动力类型；`"ev"` 时清空燃油口径字段（`fuelGrade` / `fuelCostSource`），
 *   否则旧版 `costOf` 的小熊/钱迹口径会把纯电车电费判成 0。
 */
fun blankVehicle(powertrain: String? = null): Vehicle {
    val ev = normalizePowertrain(powertrain) == POWERTRAIN_EV
    return Vehicle(
        name = "我的爱车",
        plate = "",
        model = "",
        startKm = null,
        currentKm = 0.0,
        fuelCostSource = if (ev) null else "bear",
        powertrain = if (ev) POWERTRAIN_EV else null,
    )
}

/** Web `blank()` 的空白账本。 */
fun blankLedger(powertrain: String? = null): Ledger = Ledger(
    version = 1,
    vehicle = blankVehicle(powertrain),
    records = emptyList(),
    reminders = emptyList(),
)
