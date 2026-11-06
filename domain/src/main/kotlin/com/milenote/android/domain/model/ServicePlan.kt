package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/**
 * 保养方案中的一项（Web `ledger.servicePlan[]`）。
 *
 * 内置 12 项各有固定 `key`；用户自定义项 `key` 由前端生成。
 * 字段留空时由 [com.milenote.android.domain.calc.resolveServicePlan] 回退默认值。
 */
@Serializable
data class PlanEntry(
    /** 方案 key；自定义项也必须有。 */
    val key: String = "",
    /** 项目名称；内置项以默认标题为准。 */
    val title: String? = null,
    /** 公里周期。 */
    val intervalKm: Double? = null,
    /** 月数周期，1–600 的整数。 */
    val intervalMonths: Int? = null,
    /** 是否首保一次性。 */
    val firstOnly: Boolean? = null,
    /** 完成该项目所需的记录项目；默认 `[title]`。 */
    val projects: List<String>? = null,
)

/** 保养方案基准（Web `ledger.servicePlanBase`）。 */
@Serializable
data class PlanBase(
    /** 基准日期 `YYYY-MM-DD`。 */
    val date: String? = null,
    /** 基准里程。 */
    val km: Double? = null,
    /** 是否包含首保。 */
    val includeFirst: Boolean? = null,
)
