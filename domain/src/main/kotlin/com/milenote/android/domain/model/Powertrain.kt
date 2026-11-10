package com.milenote.android.domain.model

/**
 * 动力类型（`Vehicle.powertrain` 的合法取值）。
 *
 * 账本里存字符串而不是枚举，是为了让老版本 App / Web 备份读取时能安全忽略未知值；
 * 非法值一律按燃油处理（见 [normalizePowertrain]）。
 *
 * 三个值对应三种「能量输入」档位（见 [EnergyProfile]）：
 * - `fuel`：只加油（**HEV 油电混动与燃油完全同构，不单列，按 `fuel` 存**）；
 * - `phev`：加油 + 充电（插电混动、增程）；
 * - `ev`：只充电。
 */
const val POWERTRAIN_FUEL: String = "fuel"

/** 插电混动 / 增程（可外接充电，同时有油箱）。 */
const val POWERTRAIN_PHEV: String = "phev"

/** 纯电（BEV）。 */
const val POWERTRAIN_EV: String = "ev"

/** 归一动力类型：只认 `"fuel"` / `"phev"` / `"ev"`，其余（含 null）→ null（语义=燃油）。 */
fun normalizePowertrain(value: String?): String? = when (value?.trim()?.lowercase()) {
    POWERTRAIN_FUEL -> POWERTRAIN_FUEL
    POWERTRAIN_PHEV -> POWERTRAIN_PHEV
    POWERTRAIN_EV -> POWERTRAIN_EV
    else -> null
}

/**
 * 能量能力档位：计算层与界面分支的唯一开关（三档，与"车叫什么"解耦）。
 *
 * - [FUEL_ONLY]：只加油（燃油、HEV）→ 今日油价卡、加油字段、加油票 OCR、满箱法；
 * - [DUAL]：加油 + 充电（PHEV / 增程）→ 两套字段与两条能耗流；
 * - [ELECTRIC_ONLY]：只充电（纯电）→ 充电字段、满电法、不抓油价不定位。
 */
enum class EnergyProfile { FUEL_ONLY, DUAL, ELECTRIC_ONLY }

/** 当前车的能量档位（缺省/非法值 → 燃油）。 */
val Vehicle.energyProfile: EnergyProfile
    get() = when (powertrain) {
        POWERTRAIN_EV -> EnergyProfile.ELECTRIC_ONLY
        POWERTRAIN_PHEV -> EnergyProfile.DUAL
        else -> EnergyProfile.FUEL_ONLY
    }

/** 有油箱：需要「今日油价」卡、加油字段与加油票 OCR。 */
val Vehicle.hasFuel: Boolean
    get() = energyProfile != EnergyProfile.ELECTRIC_ONLY

/** 有充电口：允许充电记录与「电耗」视图。 */
val Vehicle.hasCharging: Boolean
    get() = energyProfile != EnergyProfile.FUEL_ONLY

/** 是否纯电（缺省/非法值 → 燃油）。 */
val Vehicle.isEv: Boolean
    get() = powertrain == POWERTRAIN_EV

/** 规范动力类型字符串：`"fuel"` / `"phev"` / `"ev"`（用于写回、比较与导出）。 */
val Vehicle.powertrainKey: String
    get() = when (powertrain) {
        POWERTRAIN_EV -> POWERTRAIN_EV
        POWERTRAIN_PHEV -> POWERTRAIN_PHEV
        else -> POWERTRAIN_FUEL
    }

/** 账本当前车是否纯电（计算层统一入口，避免各文件各写字符串比较）。 */
val Ledger.isEv: Boolean
    get() = vehicle.isEv

/** 账本当前车的能量档位（计算层统一入口）。 */
val Ledger.energyProfile: EnergyProfile
    get() = vehicle.energyProfile
