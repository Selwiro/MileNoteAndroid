package com.milenote.android.domain.calc

import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.normalizePowertrain
import com.milenote.android.domain.model.powertrainKey

/**
 * 燃油车（含 HEV 油电混动）内置保养方案 12 项（Web `DEFAULT_SERVICE_PLAN`）。
 * 首保 5000 km / 3 月一次性；其余 11 项按公里或月份先到为准。
 */
val FUEL_SERVICE_PLAN: List<PlanEntry> = listOf(
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

/**
 * 纯电（BEV）内置保养方案 6 项。
 *
 * 首保 5000 km / 3 月一次性（完成判定项目=三电诊断）；三电诊断 10000 km / 12 月；
 * 空调滤芯 8000 km / 9 月；刹车油 40000 km / 24 月；冷却液 60000 km / 48 月；轮胎 50000 km。
 * 机油机滤、空气滤芯、灰滤、火花塞、冷却模块、变速器油、皮带为燃油专属，纯电方案不含。
 *
 * `brake` 与燃油方案同 key 但标题不同（燃油「制动液」/纯电「刹车油」）：
 * 标题由默认方案决定，已保存的周期覆盖不受影响。
 */
val EV_SERVICE_PLAN: List<PlanEntry> = listOf(
    PlanEntry("first", "首保", 5000.0, 3, true, listOf("三电诊断")),
    PlanEntry("ev-diag", "三电诊断", 10000.0, 12),
    PlanEntry("cabin", "空调滤芯", 8000.0, 9),
    PlanEntry("brake", "刹车油", 40000.0, 24),
    PlanEntry("coolant", "冷却液", 60000.0, 48),
    PlanEntry("tires", "轮胎", 50000.0, null),
)

/**
 * 插电混动 / 增程（PHEV）内置保养方案 **13 项** = 燃油 12 项 + 三电诊断。
 *
 * 首保完成判定项目 = 机油机滤 + 三电诊断（两类都要做，仅一次）；
 * 三电诊断（`ev-diag`）10000 km / 12 月，紧跟机油机滤之后，其余 11 项与燃油完全一致。
 * key 集合是燃油方案的超集，燃油车切到 PHEV 时已保存的周期覆盖全部保留。
 */
val PHEV_SERVICE_PLAN: List<PlanEntry> = buildList {
    for (plan in FUEL_SERVICE_PLAN) {
        add(
            if (plan.key == "first") {
                plan.copy(projects = listOf("机油机滤", "三电诊断"))
            } else {
                plan
            },
        )
        // 三电诊断紧跟机油机滤，让「发动机保养 + 三电检查」在计划表里相邻。
        if (plan.key == "oil") add(PlanEntry("ev-diag", "三电诊断", 10000.0, 12))
    }
}

/**
 * 按动力类型取内置保养方案：
 * - `fuel`（含 HEV、缺省/非法值）→ 燃油 12 项；
 * - `phev`（插混 / 增程）→ 13 项（燃油 12 + 三电诊断）；
 * - `ev`（纯电）→ 6 项。
 */
fun defaultServicePlan(powertrain: String? = null): List<PlanEntry> = when (normalizePowertrain(powertrain)) {
    POWERTRAIN_EV -> EV_SERVICE_PLAN
    POWERTRAIN_PHEV -> PHEV_SERVICE_PLAN
    else -> FUEL_SERVICE_PLAN
}

/** 兼容旧调用（二值 `ev`）。新代码请用 `defaultServicePlan(powertrain)`。 */
@Deprecated(
    "改用 defaultServicePlan(powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("defaultServicePlan(if (ev) \"ev\" else null)"),
)
fun defaultServicePlan(ev: Boolean): List<PlanEntry> =
    if (ev) EV_SERVICE_PLAN else FUEL_SERVICE_PLAN

/** 兼容旧调用：语义=燃油方案。新代码请用 [defaultServicePlan]。 */
val DEFAULT_SERVICE_PLAN: List<PlanEntry> get() = FUEL_SERVICE_PLAN

/**
 * 首保一次性项目说明（写进提醒 note 的数据文案，历史版本为纯中文常量）。
 *
 * 燃油 / HEV=机油机滤；PHEV=机油机滤 + 三电诊断；纯电=三电诊断。
 */
internal fun firstServiceNote(powertrain: String?): String = when (normalizePowertrain(powertrain)) {
    POWERTRAIN_EV -> "首保包含三电诊断，仅一次"
    POWERTRAIN_PHEV -> "首保包含机油机滤与三电诊断，仅一次"
    else -> "首保包含机油机滤，仅一次"
}

private val FUEL_PLAN_TITLES: Set<String> = FUEL_SERVICE_PLAN.mapNotNull { it.title }.toSet()
private val EV_PLAN_TITLES: Set<String> = EV_SERVICE_PLAN.mapNotNull { it.title }.toSet()

/** 只属于燃油方案的项目名（纯电项目候选里应排除）。 */
val FUEL_ONLY_SERVICE_TITLES: Set<String> = FUEL_PLAN_TITLES - EV_PLAN_TITLES

/** 只属于纯电方案的项目名（燃油项目候选里应排除）。 */
val EV_ONLY_SERVICE_TITLES: Set<String> = EV_PLAN_TITLES - FUEL_PLAN_TITLES

/**
 * 跨动力类型的排除集：只排除「另一种档位专属」的项目。
 *
 * PHEV 两类都有 → 空集（机油机滤与三电诊断都要出现）；
 * 燃油 → 排除纯电专属；纯电 → 排除燃油专属。
 */
fun crossTypeServiceTitles(powertrain: String?): Set<String> = when (normalizePowertrain(powertrain)) {
    POWERTRAIN_EV -> FUEL_ONLY_SERVICE_TITLES
    POWERTRAIN_PHEV -> emptySet()
    else -> EV_ONLY_SERVICE_TITLES
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

/**
 * 同一保养项的别名（燃油方案叫「制动液」、纯电方案叫「刹车油」）。
 *
 * PHEV 切换过动力类型后，历史记录里的名字可能是任一档位方案的写法，
 * 计划表 / 历史反算按别名并集算同一项。
 */
private val SERVICE_TITLE_ALIASES: Map<String, Set<String>> = mapOf(
    "制动液" to setOf("制动液", "刹车油"),
    "刹车油" to setOf("制动液", "刹车油"),
)

/** 记录里的项目名 [project] 是否算方案项 [title]（含「制动液 / 刹车油」别名）。 */
fun serviceTitleMatches(project: String, title: String): Boolean =
    project == title || SERVICE_TITLE_ALIASES[title]?.contains(project) == true

/** 保养项目分组（Web `SERVICE_PROJECT_GROUPS`；`保险` 为 Android 新增分组）。 */
data class ServiceProjectGroup(val title: String, val projects: List<String>)

/**
 * 按动力类型取项目库分组：只有「保养」组随方案切换（燃油 11 项 / PHEV 12 项 / 纯电 5 项，
 * 均不含首保），维修 / 汽车美容 / 保险三组与动力类型无关。
 */
fun serviceProjectGroups(powertrain: String? = null): List<ServiceProjectGroup> = listOf(
    ServiceProjectGroup("保养", defaultServicePlan(powertrain).filter { it.firstOnly != true }.map { it.title.orEmpty() }),
    ServiceProjectGroup("维修", listOf("补胎", "更换电瓶", "钣金修复", "喷漆")),
    ServiceProjectGroup("汽车美容", listOf("洗车", "打蜡", "抛光", "镀晶", "内饰清洁")),
    ServiceProjectGroup("保险", listOf("交强险", "车损险", "三者险")),
)

/** 兼容旧调用（二值 `ev`）。新代码请用 `serviceProjectGroups(powertrain)`。 */
@Deprecated(
    "改用 serviceProjectGroups(powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("serviceProjectGroups(if (ev) \"ev\" else null)"),
)
fun serviceProjectGroups(ev: Boolean): List<ServiceProjectGroup> =
    serviceProjectGroups(if (ev) POWERTRAIN_EV else null)

/** 兼容旧调用：语义=燃油车分组。新代码请用 [serviceProjectGroups]。 */
val SERVICE_PROJECT_GROUPS: List<ServiceProjectGroup> get() = serviceProjectGroups()

/**
 * 拥有内置项目库的费用分类（供编辑器 / 设置页判断是否展示项目选择）。
 *
 * 与 [serviceProjectGroups] 的 title 集合保持一致，便于 UI 直接做包含判断。
 */
val PROJECT_CATEGORIES: Set<String> = setOf("保养", "维修", "汽车美容", "保险")

/**
 * 保养 / 维修 / 美容 / 保险项目候选（Web `serviceProjectOptions`，`保险` 为 Android 新增分组）。
 *
 * 不传分类时返回全部内置 + 本车历史与自定义项目；
 * 传分类时只返回该分类的内置项目、分类自定义项目与同分类历史记录项目，
 * 并排除其它分类的内置项目、以及另一种动力类型专属的保养项目（纯电不出现机油机滤等、
 * 燃油不出现三电诊断、PHEV 两类都保留），实现「按车辆、按分类隔离」。
 */
fun serviceProjectOptions(ledger: Ledger, category: String? = null): List<String> {
    val powertrain = ledger.vehicle.powertrainKey
    val groups = serviceProjectGroups(powertrain)
    val crossType = crossTypeServiceTitles(powertrain)
    val cat = when (category) {
        "维修保养" -> "保养"
        else -> category
    }
    if (cat == null) {
        return splitProjects(
            groups.flatMap { it.projects } +
                ledger.serviceProjects.orEmpty() +
                ledger.records.flatMap { splitProjects(it.projects) },
        ).filter { it !in crossType && it !in CategoryConfig.allHiddenProjects(ledger) }
    }
    val builtins = groups.firstOrNull { it.title == cat }?.projects.orEmpty()
    val otherBuiltins = groups
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
    ).filter {
        it !in otherBuiltins && it !in crossType && it !in CategoryConfig.hiddenProjects(ledger, cat)
    }
}

/**
 * 当前车的能量档位（界面分支唯一开关）。
 *
 * 放在 domain 是因为计划表 / 提醒文案也要按档位分支，避免各层各写一遍字符串比较。
 */
val Ledger.servicePlanTier: EnergyProfile get() = vehicle.energyProfile

/** 分类归一 + 记录是否是服务类（保养/维修/汽车美容/旧维修保养）。 */
internal fun isServiceRecord(record: Record): Boolean =
    record.category in setOf("维修保养", "保养", "维修", "汽车美容")
