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
    /**
     * 被删除车辆的墓碑（车辆 id + 删除时刻，epoch 毫秒）。
     *
     * 记录级删除靠 [Ledger.tombstones] 传播；整辆车被删时记录随车消失，没有地方挂墓碑，
     * 所以需要车辆级墓碑，否则另一台设备会在下次合并时把整辆车（含记录）带回来。
     * 可选字段：老备份 / 老版本没有该字段时为 null，`version:2` 不变。
     */
    val vehicleTombstones: List<Tombstone>? = null,
) {
    /** 当前车辆；`activeId` 无效时回退第一辆，空车库返回 null。 */
    val activeCar: Ledger?
        get() = cars.firstOrNull { it.id == activeId } ?: cars.firstOrNull()
}
