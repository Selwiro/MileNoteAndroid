package com.milenote.android.domain.calc

import com.milenote.android.domain.model.HistoryBaseline
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import java.time.LocalDate

/**
 * 按默认方案生成提醒（Web `defaultServiceReminders`）。
 *
 * @param baseDate 基准日期（默认用提车日期）
 * @param baseKm 基准里程（默认用记账起始里程）
 * @param includeFirst 是否包含一次性首保
 * @param powertrain 动力类型：`fuel`（含 HEV，缺省）12 项 / `phev` 13 项 / `ev` 6 项
 */
fun defaultServiceReminders(
    baseDate: String,
    baseKm: Double?,
    includeFirst: Boolean = true,
    powertrain: String? = null,
): List<Reminder> {
    if (Dates.dateValue(baseDate).isEmpty() || baseKm == null || !baseKm.isFinite() || baseKm < 0) {
        throw IllegalArgumentException("请填写有效基准日期和里程")
    }
    return defaultServicePlan(powertrain)
        .filter { includeFirst || it.firstOnly != true }
        .map { plan ->
            val months = plan.intervalMonths
            val km = plan.intervalKm
            Reminder(
                id = Ids.newId(),
                title = plan.title.orEmpty(),
                presetKey = plan.key,
                projects = plan.projects ?: listOf(plan.title.orEmpty()),
                firstOnly = plan.firstOnly == true,
                intervalKm = km,
                intervalMonths = months,
                dueKm = if (km != null) baseKm + km else null,
                dueDate = if (months != null && months > 0) Dates.addMonths(baseDate, months) else "",
                done = false,
                source = "默认保养方案",
                note = if (plan.firstOnly == true) {
                    firstServiceNote(powertrain) + "；公里或时间先到为准"
                } else {
                    "用户设置的默认周期；公里或时间先到为准"
                },
            )
        }
}

/** 兼容旧调用（二值 `ev`）。新代码请用 `defaultServiceReminders(..., powertrain)`。 */
@Deprecated(
    "改用 defaultServiceReminders(..., powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("defaultServiceReminders(baseDate, baseKm, includeFirst, if (ev) \"ev\" else null)"),
)
fun defaultServiceReminders(
    baseDate: String,
    baseKm: Double?,
    includeFirst: Boolean = true,
    ev: Boolean,
): List<Reminder> = defaultServiceReminders(baseDate, baseKm, includeFirst, if (ev) POWERTRAIN_EV else null)

/**
 * 完成一次保养后的下一轮提醒（Web `nextService`）。
 *
 * 首保一次性返回 null；无任何周期也返回 null。
 */
fun nextService(reminder: Reminder, date: String, km: Double?): Reminder? {
    if (reminder.firstOnly == true) return null
    val months = reminder.intervalMonths
    val intervalKm = reminder.intervalKm
    val next = reminder.copy(
        id = Ids.newId(),
        done = false,
        baseDate = date,
        baseKm = km,
        dueDate = if (months != null && months > 0) Dates.addMonths(date, months) else "",
        dueKm = if (intervalKm != null && intervalKm > 0 && km != null) km + intervalKm else null,
        completedDate = null,
        completedKm = null,
        recordId = null,
        historyRecordId = null,
        historyBaseline = null,
    )
    return if (next.dueDate.orEmpty().isNotEmpty() || next.dueKm != null) next else null
}

/**
 * 完成保养（Web `completeService`）。
 *
 * 标记提醒已完成并生成下一轮；若完成的是首保，把首保完成判定项目对应的
 * 未完成循环项用本次日期/里程重设（燃油=机油机滤，纯电=三电诊断；保留原 id）。
 */
fun completeService(
    reminders: List<Reminder>,
    reminder: Reminder,
    record: Record,
): List<Reminder> {
    val projects = splitProjects(record.projects)
    val completed = reminders.map { r ->
        if (r.id == reminder.id) {
            r.copy(
                done = true,
                completedDate = record.date,
                completedKm = record.odometer,
                recordId = record.id,
            )
        } else {
            r
        }
    }.toMutableList()
    nextService(reminder, record.date, record.odometer)?.let { completed.add(it) }
    if (reminder.firstOnly == true) {
        // 首保完成判定项目（燃油=机油机滤 / 纯电=三电诊断）：完成首保后重设对应循环项。
        val firstTargets = splitProjects(reminder.projects) + projects
        val index = completed.indexOfFirst { candidate ->
            candidate.done != true && candidate.id != reminder.id &&
                (candidate.title in firstTargets || splitProjects(candidate.projects).any { it in firstTargets })
        }
        if (index >= 0) {
            val target = completed[index]
            val rescheduled = nextService(target, record.date, record.odometer)
            if (rescheduled != null) completed[index] = rescheduled.copy(id = target.id)
        }
    }
    return completed
}

/** 待办分组（Web `groupReminders` 返回的 near/later/undated/done/past）。 */
data class ReminderGroups(
    /** 今天起一年内、有日期、未完成。 */
    val near: List<Reminder>,
    /** 一年以后。 */
    val later: List<Reminder>,
    /** 无有效日期（只有里程或日期为空）。 */
    val undated: List<Reminder>,
    /** 已完成。 */
    val done: List<Reminder>,
    /** 已过期（日期早于今天）。 */
    val past: List<Reminder>,
)

/**
 * 提醒分组（Web `groupReminders`）：`done` 优先；无有效日期进 undated；
 * 早于今天进 past；今天至一年后（含边界）进 near；更远进 later。
 */
fun groupReminders(reminders: List<Reminder>, date: LocalDate): ReminderGroups {
    val cutoff = Dates.iso(Dates.addMonths(date, 12))
    val dateText = Dates.iso(date)
    val groups = mutableMapOf(
        "near" to mutableListOf<Reminder>(),
        "later" to mutableListOf<Reminder>(),
        "undated" to mutableListOf<Reminder>(),
        "done" to mutableListOf<Reminder>(),
        "past" to mutableListOf<Reminder>(),
    )
    for (r in reminders) {
        val due = r.dueDate?.takeIf { Dates.isValid(it) }
        val key = when {
            r.done -> "done"
            due == null -> "undated"
            due < dateText -> "past"
            due <= cutoff -> "near"
            else -> "later"
        }
        groups.getValue(key).add(r)
    }
    for (rows in groups.values) {
        rows.sortWith(compareBy { it.dueDate?.takeIf { d -> d.isNotEmpty() } ?: "9999" })
    }
    return ReminderGroups(
        near = groups.getValue("near"),
        later = groups.getValue("later"),
        undated = groups.getValue("undated"),
        done = groups.getValue("done"),
        past = groups.getValue("past"),
    )
}

/** 从历史记录生成提醒时使用的默认 note（Web 固定文案）。 */
internal const val HISTORY_GENERATED_NOTE = "按最近一次实际保养日期和里程计算，先到为准"

/** 用于历史反算的「方案项目存在但还没有待办」时新建提醒。 */
internal fun historyGeneratedReminder(title: String, plan: com.milenote.android.domain.model.PlanEntry): Reminder =
    Reminder(
        id = Ids.newId(),
        title = title,
        presetKey = plan.key,
        projects = listOf(title),
        intervalKm = plan.intervalKm,
        intervalMonths = plan.intervalMonths,
        done = false,
        historyGenerated = true,
        source = "保养记录",
        note = HISTORY_GENERATED_NOTE,
    )

/** 把历史基线写回提醒并清除反算元数据。 */
internal fun restoreBaseline(reminder: Reminder, baseline: HistoryBaseline): Reminder = reminder.copy(
    baseDate = baseline.baseDate,
    baseKm = baseline.baseKm,
    dueDate = baseline.dueDate,
    dueKm = baseline.dueKm,
    historyBaseline = null,
    historyRecordId = null,
)

/**
 * 删除一条保养记录后恢复它曾完成的提醒（并移除完成时自动生成的下一轮）。
 *
 * - 找到 `recordId == record.id` 且已完成的提醒，恢复为未完成；
 * - 移除同标题、未完成且明显由本次完成生成的下一轮（baseDate/baseKm 与记录一致，
 *   或 historyRecordId 指向该记录）；
 * - 恢复后的 dueDate/dueKm 保留完成前的值，随后由 syncServiceHistory 按剩余历史重算。
 */
fun restoreServiceRemindersAfterRecordDelete(
    reminders: List<Reminder>,
    record: Record,
): List<Reminder> {
    val completed = reminders.filter { it.done == true && it.recordId == record.id }
    if (completed.isEmpty()) return reminders
    val titles = completed.map { it.title }.toSet()
    val generatedNextIds = reminders.asSequence()
        .filter { it.done != true && it.title in titles }
        .filter { reminder ->
            reminder.historyRecordId == record.id ||
                (reminder.baseDate == record.date &&
                    (record.odometer == null || reminder.baseKm == record.odometer))
        }
        .map { it.id }
        .toSet()
    return reminders
        .filterNot { it.id in generatedNextIds }
        .map { reminder ->
            if (reminder.id in completed.map { it.id }) {
                reminder.copy(
                    done = false,
                    completedDate = null,
                    completedKm = null,
                    recordId = null,
                )
            } else {
                reminder
            }
        }
}
