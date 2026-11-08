package com.milenote.android.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 货币显示单位。**只影响显示符号，不做汇率换算**。 */
enum class CurrencyUnit { CNY, USD }

/** 里程显示单位。**只影响显示标签，不改写已录入的数值**。 */
enum class DistanceUnit { KM, MILE }

/** 油量显示单位。**只影响显示标签，不改写已录入的数值**。 */
enum class VolumeUnit { LITER, GALLON }

/**
 * 全局单位显示口径（设置 → 外观 → 单位）。
 *
 * 与 [I18n] 同模式：三个字段都是 Compose 快照状态，组合里读过它们的文案会随设置自动重组；
 * 由宿主（MainActivity）在设置 / 语言变化时调用 [apply] 同步。
 *
 * **重要**：单位只决定显示标签与符号，账本里的数值始终是原始录入值——不换算、不改写，
 * 备份与 Web 互通不受影响。货币默认跟随界面语言（中文元 / 英文美元），用户可显式指定。
 */
object Units {

    var currency by mutableStateOf(CurrencyUnit.CNY)
        private set
    var distance by mutableStateOf(DistanceUnit.KM)
        private set
    var volume by mutableStateOf(VolumeUnit.LITER)
        private set

    /** 由宿主在语言 / 设置变化时调用。 */
    fun apply(currency: CurrencyUnit, distance: DistanceUnit, volume: VolumeUnit) {
        this.currency = currency
        this.distance = distance
        this.volume = volume
    }

    /** 金额符号：`¥` / `$`。 */
    val currencySymbol: String
        get() = if (currency == CurrencyUnit.CNY) "¥" else "$"

    /** 金额单位标签：元 / 美元（英文 CNY / USD）。 */
    val money: String
        get() = if (currency == CurrencyUnit.CNY) tr("元", "CNY") else tr("美元", "USD")

    /** 距离单位标签：公里 / 英里（英文 km / mi）。 */
    val distanceUnit: String
        get() = if (distance == DistanceUnit.KM) tr("公里", "km") else tr("英里", "mi")

    /** 油量单位标签：升 / 加仑（英文 L / gal）。 */
    val volumeUnit: String
        get() = if (volume == VolumeUnit.LITER) tr("升", "L") else tr("加仑", "gal")

    /** 油价单位：元/升、美元/加仑 等。 */
    val pricePerVolume: String
        get() = "$money/$volumeUnit"

    /** 每距离成本单位：元/公里、美元/英里 等。 */
    val costPerDistance: String
        get() = "$money/$distanceUnit"

    /** 油耗单位：按里程/油量单位自动选择「升/百公里、加仑/百公里、升/百英里、英里/加仑」。 */
    val consumption: String
        get() = when {
            distance == DistanceUnit.MILE && volume == VolumeUnit.GALLON -> tr("英里/加仑", "MPG")
            distance == DistanceUnit.MILE -> tr("升/百英里", "L/100 mi")
            volume == VolumeUnit.GALLON -> tr("加仑/百公里", "gal/100 km")
            else -> tr("升/百公里", "L/100 km")
        }
}
