package com.milenote.android.navigation

/** 顶层导航路由。 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val RECORDS = "records"
    const val FUEL = "fuel"
    const val MAINTENANCE = "maintenance"
    const val VEHICLE = "vehicle"
    const val SETTINGS = "settings"

    /** 导入向导（外部导入）；source 为可选来源 id（bear / qianji）。 */
    const val IMPORTER = "importer"
    const val IMPORTER_PATTERN = "importer?source={source}"

    /** 生成导入向导路由：source 为空时走无参路由。 */
    fun importerRoute(source: String?): String =
        if (source.isNullOrBlank()) IMPORTER else "$IMPORTER?source=$source"

    /** 底栏 4 项。 */
    val bottomTabs = listOf(DASHBOARD, RECORDS, FUEL, MAINTENANCE)
}
