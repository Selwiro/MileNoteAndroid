package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/**
 * 一辆车的账本（Web 备份 `garage.cars[]`，version 1）。
 *
 * 可选字段对应 Web 上可能不存在的属性；`explicitNulls = false` 时省略，
 * Web 读取缺省属性的语义相同。
 */
@Serializable
data class Ledger(
    /** 账本版本，固定 1。 */
    val version: Int = 1,
    /** 车辆唯一编号（v2 车库内非空且不重复）。 */
    val id: String = "",
    val vehicle: Vehicle = Vehicle(),
    val records: List<Record> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    /** 自定义费用分类。 */
    val customCategories: List<String>? = null,
    /** 用户保存过的保养方案。 */
    val servicePlan: List<PlanEntry>? = null,
    val servicePlanBase: PlanBase? = null,
    /** 用户是否启用过保养方案。 */
    val servicePlanConfigured: Boolean? = null,
    /** 旧版全局保养项目池。 */
    val serviceProjects: List<String>? = null,
    /** 按费用分类隔离的保养项目池。 */
    val serviceProjectsByCategory: Map<String, List<String>>? = null,
)
