package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/**
 * 提醒（Web 备份 `ledger.reminders[]`）。
 *
 * 内置保养方案生成的提醒带 `presetKey`；自定义提醒只有 `title`。
 * `historyGenerated` / `historyBaseline` / `historyRecordId` 是
 * `syncServiceHistory` 的反算元数据，必须随备份往返保留。
 */
@Serializable
data class Reminder(
    /** 提醒唯一编号。 */
    val id: String = "",
    /** 提醒事项标题，≤ 100 字。 */
    val title: String = "",
    /** 对应保养方案的 key（first/oil/air/...）。 */
    val presetKey: String? = null,
    /** 匹配费用记录所需的项目；默认 `[title]`。 */
    val projects: List<String>? = null,
    /** 首保一次性事项。 */
    val firstOnly: Boolean? = null,
    /** 公里周期。 */
    val intervalKm: Double? = null,
    /** 月数周期，1–600 的整数。 */
    val intervalMonths: Int? = null,
    /** 下次到期日期 `YYYY-MM-DD`；只有里程的提醒为空串。 */
    val dueDate: String? = null,
    /** 下次到期里程。 */
    val dueKm: Double? = null,
    /** 本轮周期基准日期（实际完成日期）。 */
    val baseDate: String? = null,
    /** 本轮周期基准里程（实际完成里程）。 */
    val baseKm: Double? = null,
    /** 是否已完成。 */
    val done: Boolean = false,
    /** 完成日期。 */
    val completedDate: String? = null,
    /** 完成时的总里程。 */
    val completedKm: Double? = null,
    /** 关联的完成记录 id。 */
    val recordId: String? = null,
    /** 是否由历史保养记录自动生成。 */
    val historyGenerated: Boolean? = null,
    /** 被历史反算改写前的基准备份，删记录时据此回退。 */
    val historyBaseline: HistoryBaseline? = null,
    /** 当前周期对应的最近历史记录 id。 */
    val historyRecordId: String? = null,
    /** 来源说明：默认保养方案 / 自定义保养方案 / 保养手册 / 保养记录 等。 */
    val source: String? = null,
    /** 备注。 */
    val note: String? = null,
)

/** `Reminder.historyBaseline`：历史反算前的原始基准。 */
@Serializable
data class HistoryBaseline(
    val baseDate: String? = null,
    val baseKm: Double? = null,
    val dueDate: String? = null,
    val dueKm: Double? = null,
)
