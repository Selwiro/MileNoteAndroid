package com.milenote.android.domain.model

/** Web `blank()` 的车辆默认值。 */
fun blankVehicle(): Vehicle = Vehicle(
    name = "我的爱车",
    plate = "",
    model = "",
    startKm = null,
    currentKm = 0.0,
    fuelCostSource = "bear",
)

/** Web `blank()` 的空白账本。 */
fun blankLedger(): Ledger = Ledger(
    version = 1,
    vehicle = blankVehicle(),
    records = emptyList(),
    reminders = emptyList(),
)
