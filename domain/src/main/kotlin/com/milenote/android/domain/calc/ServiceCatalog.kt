package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record

/** 保养台账字段别名（Web `SERVICE_FIELDS`）。 */
val SERVICE_FIELDS: Map<String, List<String>> = linkedMapOf(
    "cycle" to listOf("保养周期", "周期", "间隔", "周期说明"),
    "project" to listOf("项目", "保养项目", "维修项目", "项目名称"),
    "date" to listOf("日期", "保养日期", "时间", "日期时间"),
    "time" to listOf("时刻", "具体时间", "时间"),
    "location" to listOf("地点", "保养地点", "门店", "维修店"),
    "odometer" to listOf("公里数", "总里程", "里程", "保养里程"),
    "amount" to listOf("费用", "金额", "实付金额", "花费"),
    "intervalKm" to listOf("周期公里数", "间隔公里数", "间隔公里", "保养周期公里", "周期公里"),
    "intervalMonths" to listOf("周期月数", "间隔月数", "保养周期月", "间隔月"),
    "lastDate" to listOf("上次保养日期", "基准日期"),
    "lastKm" to listOf("上次保养公里数", "基准里程", "上次保养里程"),
    "note" to listOf("备注", "说明"),
)

/** 保养台账表头映射（Web `serviceMap`）；时刻列与日期列相同时忽略时刻。 */
fun serviceMap(headers: List<String>): Map<String, Int?> {
    val map = LinkedHashMap<String, Int?>()
    for ((key, names) in SERVICE_FIELDS) {
        map[key] = names.map { name -> headers.indexOfFirst { it.trim() == name } }.firstOrNull { it >= 0 }
    }
    if (map["time"] == map["date"]) map["time"] = null
    return map
}

/**
 * 12 项内置保养方案（Web `DEFAULT_SERVICE_PLAN`）。
 * 首保 5000 km / 3 月一次性；其余 11 项按公里或月份先到为准。
 */
val DEFAULT_SERVICE_PLAN: List<PlanEntry> = listOf(
    PlanEntry("first", "首保", 5000.0, 3, true, listOf("机油机滤")),
    PlanEntry("oil", "机油机滤", 10000.0, 12),
    PlanEntry("air", "空气滤芯", 10000.0, 12),
    PlanEntry("cabin", "空调滤芯", 8000.0, 9),
    PlanEntry("coolant", "冷却液", 40000.0, 24),
    PlanEntry("brake", "制动液", 40000.0, 24),
    PlanEntry("ash", "灰滤", 60000.0, 36),
    PlanEntry("spark", "火花塞", 40000.0, null),
    PlanEntry("tires", "轮胎", 50000.0, null),
    PlanEntry("cooling-module", "冷却模块", 50000.0, null),
    PlanEntry("transmission", "变速器油", 80000.0, 48),
    PlanEntry("belt", "皮带", 100000.0, null),
)

/** 周期文案解析结果（Web `parseCycleText`）。 */
data class CycleText(val km: Double?, val months: Double?)

private val CYCLE_KM = Regex("(\\d+(?:\\.\\d+)?)(万|千)?(?:公里|千米|km)", RegexOption.IGNORE_CASE)
private val CYCLE_MONTHS = Regex("(\\d+(?:\\.\\d+)?)(?:个)?(年|月)")

/**
 * 解析「1万公里或1年」「8千公里或9月」这类中文合并周期（Web `parseCycleText`）。
 */
fun parseCycleText(value: String?): CycleText {
    val s = value.orEmpty().replace(Regex("[,，\\s]"), "")
    val kmMatch = CYCLE_KM.find(s)
    val monthMatch = CYCLE_MONTHS.find(s)
    val km = kmMatch?.let {
        val raw = it.groupValues[1].toDoubleOrNull() ?: return@let null
        raw * when (it.groupValues[2]) {
            "万" -> 10000.0
            "千" -> 1000.0
            else -> 1.0
        }
    }
    val months = monthMatch?.let {
        val raw = it.groupValues[1].toDoubleOrNull() ?: return@let null
        raw * if (it.groupValues[2] == "年") 12.0 else 1.0
    }
    return CycleText(km, months)
}

/**
 * 项目文本拆分（Web `splitProjects`）：接受列表或「、，；换行」分隔的字符串，
 * 去空白、去空项、保序去重。
 */
fun splitProjects(value: Any?): List<String> {
    val items: List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.map { it?.toString().orEmpty() }
        else -> value.toString().split(Regex("[、,，;；\\n]+"))
    }
    return items.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** 保养项目分组（Web `SERVICE_PROJECT_GROUPS`；`保险` 为 Android 新增分组）。 */
data class ServiceProjectGroup(val title: String, val projects: List<String>)

val SERVICE_PROJECT_GROUPS: List<ServiceProjectGroup> = listOf(
    ServiceProjectGroup("保养", DEFAULT_SERVICE_PLAN.filter { it.firstOnly != true }.map { it.title.orEmpty() }),
    ServiceProjectGroup("维修", listOf("补胎", "更换电瓶", "钣金修复", "喷漆")),
    ServiceProjectGroup("汽车美容", listOf("洗车", "打蜡", "抛光", "镀晶", "内饰清洁")),
    ServiceProjectGroup("保险", listOf("交强险", "车损险", "三者险")),
)

/**
 * 拥有内置项目库的费用分类（供编辑器 / 设置页判断是否展示项目选择）。
 *
 * 与 [SERVICE_PROJECT_GROUPS] 的 title 集合保持一致，便于 UI 直接做包含判断。
 */
val PROJECT_CATEGORIES: Set<String> = setOf("保养", "维修", "汽车美容", "保险")


/**
 * 保养 / 维修 / 美容 / 保险项目候选（Web `serviceProjectOptions`，`保险` 为 Android 新增分组）。
 *
 * 不传分类时返回全部内置 + 本车历史与自定义项目；
 * 传分类时只返回该分类的内置项目、分类自定义项目与同分类历史记录项目，
 * 并排除其它分类的内置项目，实现「按车辆、按分类隔离」。
 */
fun serviceProjectOptions(ledger: Ledger, category: String? = null): List<String> {
    val cat = when (category) {
        "维修保养" -> "保养"
        else -> category
    }
    if (cat == null) {
        return splitProjects(
            SERVICE_PROJECT_GROUPS.flatMap { it.projects } +
                ledger.serviceProjects.orEmpty() +
                ledger.records.flatMap { splitProjects(it.projects) },
        )
    }
    val builtins = SERVICE_PROJECT_GROUPS.firstOrNull { it.title == cat }?.projects.orEmpty()
    val otherBuiltins = SERVICE_PROJECT_GROUPS
        .filter { it.title != cat }
        .flatMap { it.projects }
        .toSet()
    val history = ledger.records
        .filter { Categories.normalize(it.category) == cat }
        .flatMap { splitProjects(it.projects) }
    val classified = ledger.serviceProjectsByCategory.orEmpty().values.flatten()
    val legacy = if (cat == "保养") {
        ledger.serviceProjects.orEmpty().filter { p ->
            p !in otherBuiltins &&
                p !in classified &&
                ledger.records.none { r ->
                    Categories.normalize(r.category) != cat && splitProjects(r.projects).contains(p)
                }
        }
    } else {
        emptyList()
    }
    return splitProjects(
        builtins + ledger.serviceProjectsByCategory.orEmpty()[cat].orEmpty() + legacy + history,
    ).filter { it !in otherBuiltins }
}

/** 分类归一 + 记录是否是服务类（保养/维修/汽车美容/旧维修保养）。 */
internal fun isServiceRecord(record: Record): Boolean =
    record.category in setOf("维修保养", "保养", "维修", "汽车美容")
