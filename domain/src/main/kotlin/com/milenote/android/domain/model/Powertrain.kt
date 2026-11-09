package com.milenote.android.domain.model

/**
 * 动力类型（`Vehicle.powertrain` 的合法取值）。
 *
 * 账本里存字符串而不是枚举，是为了让老版本 App / Web 备份读取时能安全忽略未知值；
 * 非法值一律按燃油处理（见 [normalizePowertrain]）。
 */
const val POWERTRAIN_FUEL: String = "fuel"

/** 纯电（BEV）。 */
const val POWERTRAIN_EV: String = "ev"

/** 归一动力类型：只认 `"fuel"` / `"ev"`，其余（含 null）→ null（语义=燃油）。 */
fun normalizePowertrain(value: String?): String? = when (value?.trim()?.lowercase()) {
    POWERTRAIN_FUEL -> POWERTRAIN_FUEL
    POWERTRAIN_EV -> POWERTRAIN_EV
    else -> null
}

/** 是否纯电（缺省/非法值 → 燃油）。 */
val Vehicle.isEv: Boolean
    get() = powertrain == POWERTRAIN_EV

/** 规范动力类型字符串：`"fuel"` / `"ev"`（用于写回、比较与导出）。 */
val Vehicle.powertrainKey: String
    get() = if (isEv) POWERTRAIN_EV else POWERTRAIN_FUEL

/** 账本当前车是否纯电（计算层统一入口，避免各文件各写字符串比较）。 */
val Ledger.isEv: Boolean
    get() = vehicle.isEv
