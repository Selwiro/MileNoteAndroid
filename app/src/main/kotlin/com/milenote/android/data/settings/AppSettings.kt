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
)
