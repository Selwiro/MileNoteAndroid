package com.milenote.android.navigation

/**
 * 顶层导航路由。
 *
 * 底栏 4 个 pager tab = 总览 / 油耗(能耗) / 保养 / 我的，中间「＋」是记一笔动作（不是页面）。
 * 三个「明细 / 记录」是推入页，入口在各 tab 顶栏右上角图标；设置子页也是推入页（从「我的」菜单进）。
 */
object Routes {
    // —— 底栏 4 个 pager tab（顺序即底栏顺序；take(2) 在「＋」左、drop(2) 在右） ——
    const val DASHBOARD = "dashboard"
    const val FUEL = "fuel"
    const val MAINTENANCE = "maintenance"
    const val MINE = "mine"

    // —— 推入页：三个「明细 / 记录」入口（各自 tab 顶栏右上角图标进入） ——
    /** 费用明细（原「记录 / 费用统计」页的明细页签）。 */
    const val RECORDS = "records"

    /** 油耗 / 能耗明细。 */
    const val FUEL_DETAIL = "fuel-detail"

    /** 维修保养记录。 */
    const val SERVICE_HISTORY = "service-history"

    /** 用车报告（从「我的 → 数据与同步」推入；页内再分月报 / 年报 / 累计）。 */
    const val REPORT = "report"

    // —— 设置子页（从「我的」菜单推入；page 为 SettingsPage 的小写 key） ——
    const val SETTINGS_PAGE = "settings-page"
    const val SETTINGS_PAGE_PATTERN = "settings-page?page={page}"

    /** 生成设置子页路由。 */
    fun settingsPageRoute(page: String): String = "$SETTINGS_PAGE?page=$page"

    // —— 导入向导（外部导入）；source 为可选来源 id（bear / qianji） ——
    const val IMPORTER = "importer"
    const val IMPORTER_PATTERN = "importer?source={source}"

    /** 生成导入向导路由：source 为空时走无参路由。 */
    fun importerRoute(source: String?): String =
        if (source.isNullOrBlank()) IMPORTER else "$IMPORTER?source=$source"

    /** 底栏 4 项（pager 页序）。 */
    val bottomTabs = listOf(DASHBOARD, FUEL, MAINTENANCE, MINE)
}
