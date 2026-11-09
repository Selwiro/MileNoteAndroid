package com.milenote.android.data.settings

/** 深浅色偏好。 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * 全局设置（DataStore 持久化；不进账本备份）。
 * 皮肤跟随 Web 口径存在账本 `garage.theme` 里，不在这里。
 */
data class AppSettings(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** WebDAV 自动同步：应用打开期间同步（前台，退出即结束）。 */
    val webDavAutoSync: Boolean = false,
    /** 界面语言：跟随系统 / 中文 / English。 */
    val language: com.milenote.android.i18n.AppLanguage = com.milenote.android.i18n.AppLanguage.SYSTEM,
    /** 货币显示单位；`null` = 跟随界面语言（中文元 / 英文美元）。只影响显示，不做汇率换算。 */
    val currency: com.milenote.android.i18n.CurrencyUnit? = null,
    /** 里程显示单位，默认公里。只影响显示，不改写数值。 */
    val distanceUnit: com.milenote.android.i18n.DistanceUnit = com.milenote.android.i18n.DistanceUnit.KM,
    /** 油量显示单位，默认升。只影响显示，不改写数值。 */
    val volumeUnit: com.milenote.android.i18n.VolumeUnit = com.milenote.android.i18n.VolumeUnit.LITER,
    /**
     * 最近一次 JSON 完整备份成功写出的时刻（epoch millis）；`null` = 尚未导出备份。
     * 只存在本机设置里，**不进账本备份**、不参与 WebDAV 同步。
     */
    val lastBackupAt: Long? = null,
)
