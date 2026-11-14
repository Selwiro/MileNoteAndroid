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
    /**
     * 被隐藏的内置费用分类（存储值，如 `"罚单"`）。
     *
     * 只影响「新建记录的可选分类」与设置页展示，**不改写历史记录的分类值**；
     * 受保护分类（油费/保险/保养/维修）不可隐藏。null 表示没有隐藏项（老备份缺省）。
     */
    val hiddenCategories: List<String>? = null,
    /** 用户自定义的分类显示名：存储值 → 显示名（不改写记录）。 */
    val categoryLabels: Map<String, String>? = null,
    /** 被隐藏的保养/维修等项目：分类 → 项目原始文字。
     *  只影响候选与设置页展示，不改写 `Record.projects` 里的历史文字。 */
    val hiddenServiceProjects: Map<String, List<String>>? = null,
    /** 用户自定义的项目显示名：项目原始文字 → 显示名。 */
    val serviceProjectLabels: Map<String, String>? = null,
    /**
     * 删除墓碑：多设备同步时用来传播「这条记录已经被删掉」，避免另一台设备把旧副本同步回来。
     *
     * 删除记录时**仍从 [records] 里物理移除**（界面、统计、导出全部不受影响），只额外留一条墓碑；
     * 撤销删除 / 重新创建同 id 记录时移除墓碑。null 表示没有墓碑（老备份缺省）。
     * 墓碑超过 180 天在同步合并时修剪（见 `SyncMerge.TOMBSTONE_RETENTION_MS`）。
     */
    val tombstones: List<Tombstone>? = null,
)

/**
 * 一条删除墓碑（多设备同步用）。
 *
 * @property id 被删除记录的编号。
 * @property at 删除时刻（epoch 毫秒）；同一 id 多次删除取较大值。
 */
@Serializable
data class Tombstone(
    val id: String = "",
    val at: Long = 0L,
)
