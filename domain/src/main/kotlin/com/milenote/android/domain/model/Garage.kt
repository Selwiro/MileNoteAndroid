package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/**
 * 车库（Web 备份顶层，version 2）。
 *
 * ```json
 * { "version": 2, "activeId": "car-uuid", "theme": "blue", "cars": [ Ledger ] }
 * ```
 */
@Serializable
data class Garage(
    /** 备份结构版本，固定 2。 */
    val version: Int = 2,
    /** 当前车辆 id；无效时回退第一辆。 */
    val activeId: String = "",
    /** 界面皮肤 id：blue / forest / purple / orange / graphite。 */
    val theme: String = "blue",
    /** 至少一辆车。 */
    val cars: List<Ledger> = emptyList(),
) {
    /** 当前车辆；`activeId` 无效时回退第一辆，空车库返回 null。 */
    val activeCar: Ledger?
        get() = cars.firstOrNull { it.id == activeId } ?: cars.firstOrNull()
}
