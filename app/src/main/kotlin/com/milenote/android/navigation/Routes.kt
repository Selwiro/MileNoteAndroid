package com.milenote.android.navigation

/** 顶层导航路由。 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val RECORDS = "records"
    const val FUEL = "fuel"
    const val MAINTENANCE = "maintenance"
    const val VEHICLE = "vehicle"
    const val SETTINGS = "settings"
    const val IMPORTER = "importer"

    /** 底栏 4 项。 */
    val bottomTabs = listOf(DASHBOARD, RECORDS, FUEL, MAINTENANCE)
}
