package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.PlanBase
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.normalizePowertrain
import com.milenote.android.domain.model.powertrainKey
import java.time.LocalDate

/**
 * 解析用户保养方案（Web `resolveServicePlan`）。
 *
 * 以 [defaultServicePlan]（按动力类型：燃油/HEV 12 项 / PHEV 13 项 / 纯电 6 项）为底按 key 合并，
 * 留空字段回退默认；自定义 key 追加。
 * 另一档位专属的 key 会被丢弃（燃油↔纯电切换后计划表不会凭空多出旧项目）；
 * PHEV 的 key 集合是燃油超集，两类项目都保留（排除集为空）。
 * 标题不能空 / 重复；公里须为正数、月数 1–600 整数；自定义项目公里与月至少一项。
 */
fun resolveServicePlan(entries: List<PlanEntry>? = null, powertrain: String? = null): List<PlanEntry> {
    val input = entries.orEmpty()
    val defaults = defaultServicePlan(powertrain)
    val defaultKeys = defaults.map { it.key }.toSet()
    // 「另一档位」的默认方案：用于专属 key 丢弃与共用 key 的完成判定项目重置。
    // PHEV 同时具备燃油与纯电项目，两类默认都算「旧档位」。
    val otherPlans = when (normalizePowertrain(powertrain)) {
        POWERTRAIN_EV -> listOf(FUEL_SERVICE_PLAN)
        POWERTRAIN_PHEV -> listOf(FUEL_SERVICE_PLAN, EV_SERVICE_PLAN)
        else -> listOf(EV_SERVICE_PLAN)
    }
    val otherDefaults = otherPlans.flatten().associateBy { it.key }
    val otherTypeKeys = otherDefaults.keys - defaultKeys
    val otherProjects = buildMap<String, MutableSet<List<String>>> {
        for (plan in otherPlans.flatten()) {
            val projects = plan.projects ?: continue
            getOrPut(plan.key) { mutableSetOf() }.add(projects)
        }
    }
    val resolved = defaults.map { plan ->
        val entry = input.firstOrNull { it.key == plan.key }
        // 共用 key（如首保 / 制动液）在不同档位方案里的完成判定项目不同：
        // 若保存的 projects 仍是「另一档位的默认值」，切换后回退到当前档位的默认，
        // 避免纯电车首保仍要求「机油机滤」、PHEV 首保漏掉「三电诊断」；用户真正自定义过的保留。
        val savedProjects = entry?.projects
        val projects = if (savedProjects != null && otherProjects[plan.key]?.contains(savedProjects) == true) {
            plan.projects
        } else {
            savedProjects ?: plan.projects
        }
        PlanEntry(
            key = plan.key,
            title = plan.title,
            intervalKm = entry?.intervalKm ?: plan.intervalKm,
            intervalMonths = entry?.intervalMonths ?: plan.intervalMonths,
            firstOnly = plan.firstOnly == true,
            projects = projects,
        )
    }
    val custom = input
        .filter { entry -> defaults.none { it.key == entry.key } && entry.key !in otherTypeKeys }
        .map {
            PlanEntry(
                key = it.key,
                title = it.title,
                intervalKm = it.intervalKm,
                intervalMonths = it.intervalMonths,
                firstOnly = it.firstOnly,
                projects = it.projects,
            )
        }
    val titles = mutableSetOf<String>()
    return (resolved + custom).map { plan ->
        val fallback = defaults.firstOrNull { it.key == plan.key }
        val title = plan.title.orEmpty().trim()
        if (title.isEmpty() || !titles.add(title)) {
            throw IllegalArgumentException("项目名称不能为空或重复")
        }
        val km = plan.intervalKm ?: fallback?.intervalKm
        val months = plan.intervalMonths ?: fallback?.intervalMonths
        if ((km != null && (!km.isFinite() || km <= 0)) || (months != null && (months <= 0 || months > 600))) {
            throw IllegalArgumentException("$title：公里数须为正数，时长须为 1–600 的整月")
        }
        if (km == null && months == null) {
            throw IllegalArgumentException("$title：自定义项目至少填写公里数或时长")
        }
        plan.copy(title = title, intervalKm = km, intervalMonths = months)
    }
}

/** 兼容旧调用（二值 `ev`）。新代码请用 `resolveServicePlan(entries, powertrain)`。 */
@Deprecated(
    "改用 resolveServicePlan(entries, powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("resolveServicePlan(entries, if (ev) \"ev\" else null)"),
)
fun resolveServicePlan(entries: List<PlanEntry>? = null, ev: Boolean): List<PlanEntry> =
    resolveServicePlan(entries, if (ev) POWERTRAIN_EV else null)

/**
 * 保存并应用保养方案（Web `applyServicePlan`）。
 *
 * 写入 `servicePlan / servicePlanBase / servicePlanConfigured`，更新未完成提醒
 * （不存在则新建），方案项目并入 `serviceProjects`，最后执行 [syncServiceHistory]。
 */
fun applyServicePlan(
    ledger: Ledger,
    entries: List<PlanEntry>?,
    baseDate: String,
    baseKm: Double?,
    includeFirst: Boolean,
): Ledger {
    if (Dates.dateValue(baseDate).isEmpty() || baseKm == null || !baseKm.isFinite() || baseKm < 0) {
        throw IllegalArgumentException("请填写有效基准日期和里程")
    }
    val plan = resolveServicePlan(entries, ledger.vehicle.powertrainKey)
    var next = ledger.copy(
        servicePlan = plan,
        servicePlanBase = PlanBase(date = baseDate, km = baseKm, includeFirst = includeFirst),
        servicePlanConfigured = true,
    )
    for (p in plan) {
        if (p.firstOnly == true) {
            val alreadyDone = next.reminders.any {
                it.done && (it.presetKey == p.key || it.title == p.title.orEmpty())
            }
            if (!includeFirst || alreadyDone) continue
        }
        val firstOnly = p.firstOnly == true
        val reminder = Reminder(
            title = p.title.orEmpty(),
            presetKey = p.key,
            projects = p.projects ?: listOf(p.title.orEmpty()),
            firstOnly = firstOnly,
            intervalKm = p.intervalKm,
            intervalMonths = p.intervalMonths,
            baseDate = baseDate,
            baseKm = baseKm,
            dueDate = if (p.intervalMonths != null && p.intervalMonths > 0) {
                Dates.addMonths(baseDate, p.intervalMonths)
            } else {
                ""
            },
            dueKm = if (p.intervalKm != null && p.intervalKm > 0) baseKm + p.intervalKm else null,
            done = false,
            source = "自定义保养方案",
            note = if (firstOnly) {
                firstServiceNote(ledger.vehicle.powertrainKey)
            } else {
                "日期或公里数先到为准"
            },
        )
        val oldIndex = next.reminders.indexOfFirst {
            !it.done && (it.presetKey == p.key || it.title == p.title.orEmpty())
        }
        next = if (oldIndex >= 0) {
            val old = next.reminders[oldIndex]
            val merged = old.copy(
                title = reminder.title,
                presetKey = reminder.presetKey,
                projects = reminder.projects,
                firstOnly = reminder.firstOnly,
                intervalKm = reminder.intervalKm,
                intervalMonths = reminder.intervalMonths,
                baseDate = reminder.baseDate,
                baseKm = reminder.baseKm,
                dueDate = reminder.dueDate,
                dueKm = reminder.dueKm,
                done = false,
                source = reminder.source,
                note = reminder.note,
                historyBaseline = null,
            )
            next.copy(reminders = next.reminders.toMutableList().also { it[oldIndex] = merged })
        } else {
            next.copy(reminders = next.reminders + reminder.copy(id = Ids.newId()))
        }
    }
    val projects = splitProjects(
        next.serviceProjects.orEmpty() + plan.filter { it.firstOnly != true }.map { it.title.orEmpty() },
    )
    next = next.copy(serviceProjects = projects)
    return syncServiceHistory(next)
}

/** 计划表内部定义：方案项或自定义提醒投影出的行定义。 */
private data class PlanDefinition(
    val key: String,
    val title: String,
    val intervalKm: Double?,
    val intervalMonths: Int?,
    val firstOnly: Boolean,
    val projects: List<String>?,
    val presetKey: String?,
)

/** 保养计划表一行（Web `servicePlanRows` 行对象）。 */
data class PlanRow(
    val title: String,
    val firstOnly: Boolean,
    val intervalKm: Double?,
    val intervalMonths: Int?,
    /** pending / overdue / done / unconfigured。 */
    val status: String,
    /** 当前本轮待做提醒（若有）。 */
    val current: Reminder?,
    /** 最近完成日期；无则空串。 */
    val completedDate: String,
    /** 最近完成时的里程（提醒的完成里程或对应保养记录的里程）；无则 null。 */
    val completedKm: Double? = null,
    val hasCompleted: Boolean,
    /** 历史记录条数（本轮之前的已做记录）。 */
    val recordCount: Int,
    /** 已完成提醒数量。 */
    val doneCount: Int,
)

/**
 * 保养计划表（Web `servicePlanRows`）。
 *
 * 行集合 = 方案项目（含自定义）+ 提醒中不匹配方案定义的标题；
 * 状态区分「上次已做」与「本轮待做」，过期按日期或公里任一判断，保留远期项目。
 */
fun servicePlanRows(ledger: Ledger, date: LocalDate, km: Double): List<PlanRow> {
    val definitions = resolveServicePlan(ledger.servicePlan.orEmpty(), ledger.vehicle.powertrainKey)
        .map {
            PlanDefinition(
                key = it.key,
                title = it.title.orEmpty(),
                intervalKm = it.intervalKm,
                intervalMonths = it.intervalMonths,
                firstOnly = it.firstOnly == true,
                projects = it.projects,
                presetKey = it.key,
            )
        }
        .toMutableList()
    val reminders = ledger.reminders
    val records = ledger.records.filter { isServiceRecord(it) }
    val extraTitles = mutableSetOf<String>()
    for (r in reminders) {
        val matched = definitions.any { it.presetKey == r.presetKey || it.title == r.title }
        if (!matched && !extraTitles.contains(r.title)) {
            definitions.add(
                PlanDefinition(
                    key = r.id,
                    title = r.title,
                    intervalKm = r.intervalKm,
                    intervalMonths = r.intervalMonths,
                    firstOnly = r.firstOnly == true,
                    projects = r.projects,
                    presetKey = r.presetKey,
                ),
            )
            extraTitles.add(r.title)
        }
    }
    return definitions.map { p ->
        val related = reminders.filter {
            (p.presetKey != null && it.presetKey == p.presetKey) || it.title == p.title
        }
        val pending = related
            .filter { !it.done }
            .sortedWith(
                compareByDescending<Reminder> { reminderState(it, km, date).level == ReminderLevel.OVERDUE }
                    .thenBy { it.dueDate?.takeIf { d -> d.isNotEmpty() } ?: "9999" }
                    .thenBy { it.dueKm ?: Double.MAX_VALUE },
            )
        val done = related.filter { it.done }
        val current = pending.firstOrNull()
        val history = records.filter { r ->
            done.any { it.recordId == r.id } ||
                (p.firstOnly != true && splitProjects(r.projects).any { serviceTitleMatches(it, p.title) })
        }.sortedByDescending { it.date }
        val lastDone = done
            .filter { !it.completedDate.isNullOrEmpty() }
            .maxByOrNull { it.completedDate.orEmpty() }
        val completedDate = listOfNotNull(
            lastDone?.completedDate?.takeIf { it.isNotEmpty() },
            history.firstOrNull()?.date?.takeIf { it.isNotEmpty() },
        ).maxOrNull() ?: ""
        // 与 completedDate 取同一条来源的里程，避免日期和里程对不上。
        val completedKm = when {
            completedDate.isEmpty() -> null
            history.firstOrNull()?.date == completedDate -> history.firstOrNull()?.odometer
            else -> lastDone?.completedKm ?: history.firstOrNull()?.odometer
        }
        val status = when {
            current != null ->
                if (reminderState(current, km, date).level == ReminderLevel.OVERDUE) "overdue" else "pending"
            done.isNotEmpty() || history.isNotEmpty() -> "done"
            else -> "unconfigured"
        }
        val cycle = current ?: related.lastOrNull()
        PlanRow(
            title = p.title,
            firstOnly = p.firstOnly,
            intervalKm = cycle?.intervalKm ?: p.intervalKm,
            intervalMonths = cycle?.intervalMonths ?: p.intervalMonths,
            status = status,
            current = current,
            completedDate = completedDate,
            completedKm = completedKm,
            hasCompleted = done.isNotEmpty() || history.isNotEmpty(),
            recordCount = history.size,
            doneCount = done.size,
        )
    }
}

/** 单条保养进度（Web `serviceProgress`）。 */
data class ServiceProgress(
    /** `km` 或 `days`。 */
    val kind: String,
    /** 剩余量（公里或天数，可为负）。 */
    val left: Double?,
    /** 已消耗百分比 0–100。 */
    val percent: Double,
    val overdue: Boolean,
)

/**
 * 保养进度（Web `serviceProgress`）：公里与时间各算消耗比例取较高者；
 * 任一到期的显示已到期满格；已完成返回 null。
 */
fun serviceProgress(reminder: Reminder, km: Double, date: LocalDate): ServiceProgress? {
    if (reminder.done) return null
    val parts = mutableListOf<ServiceProgress>()
    fun add(kind: String, left: Double?, total: Double) {
        if (left != null && left.isFinite() && total > 0) {
            parts += ServiceProgress(
                kind = kind,
                left = left,
                percent = maxOf(0.0, minOf(100.0, (1 - left / total) * 100.0)),
                overdue = left <= 0,
            )
        }
    }
    val dueKm = reminder.dueKm
    val intervalKm = reminder.intervalKm
    if (dueKm != null && intervalKm != null && intervalKm > 0) {
        add("km", dueKm - km, intervalKm)
    }
    val dueDate = Dates.parse(reminder.dueDate)
    val intervalMonths = reminder.intervalMonths
    if (dueDate != null && intervalMonths != null && intervalMonths > 0) {
        // Web：baseDate 非空即用（非法日期会导致时间进度无法计算），为空才回退按周期倒推。
        val baseDate = reminder.baseDate
        val base = if (baseDate.isNullOrEmpty()) {
            Dates.addMonths(dueDate, -intervalMonths)
        } else {
            Dates.parse(baseDate)
        }
        if (base != null) {
            val start = base.toEpochDay().toDouble()
            val end = dueDate.toEpochDay().toDouble()
            add("days", Math.round(end - date.toEpochDay()).toDouble(), end - start)
        }
    }
    val state = reminderState(reminder, km, date)
    if (state.level == ReminderLevel.OVERDUE) {
        return parts.firstOrNull { it.overdue } ?: ServiceProgress(
            kind = if (state.days != null && state.days <= 0) "days" else "km",
            left = if (state.days != null && state.days <= 0) state.days.toDouble() else state.left,
            percent = 100.0,
            overdue = true,
        )
    }
    return parts.sortedByDescending { it.percent }.firstOrNull()
}
