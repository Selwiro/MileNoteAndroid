package com.milenote.android.data.garage

import com.milenote.android.domain.model.Garage

/**
 * 一次账本保存的结果。
 *
 * 以前 [GarageRepository.update] 只返回 `Garage?`：一个「内存里改了、磁盘没写下去」的结果
 * 和真的保存成功完全一样，调用方只能靠事后读 [GarageRepository.writeError] 猜（有竞态，
 * 也可能早被别的写入清掉）。凡是「失败就必须留住用户输入 / 不能报成功」的入口
 * （记账、批量改分类、导入、保养、车辆）都改用 [GarageRepository.save] 判定。
 */
sealed interface SaveResult {

    /** 已写入本机（演示模式、本机写入暂停属于预期内行为，也算 Ok，由界面另行提示）。 */
    data class Ok(val garage: Garage) : SaveResult

    /**
     * 没有写入本机。
     *
     * @property message 可直接展示的原因（校验不通过 / 未写入本机）。
     * @property garage 失败时内存里的账本：**null 表示内存也没变**（账本未加载或校验不通过）；
     *   非 null 表示修改已进内存但没落盘（写盘失败），用户重试成功后会一并写入。
     */
    data class Failed(val message: String, val garage: Garage? = null) : SaveResult

    /** 成功时是写入磁盘的账本；写盘失败时是内存里的账本（可能为 null）。 */
    val garageOrNull: Garage?
        get() = when (this) {
            is Ok -> garage
            is Failed -> garage
        }
}
