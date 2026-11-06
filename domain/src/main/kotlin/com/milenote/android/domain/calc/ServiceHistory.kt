package com.milenote.android.domain.calc

import com.milenote.android.domain.import.inferServiceNote
import com.milenote.android.domain.model.HistoryBaseline
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder

/**
 * 按最近一次实际保养记录重算所有未完成提醒（Web `syncServiceHistory`）。
 *
 * 这是 Web 端每次 render / save 都会执行的全局副作用：
 * - 标题集合 = 方案项目（非首保）+ 带周期的未完成提醒标题；
 * - 有历史记录：写入 `baseDate/baseKm`，按周期反算 `dueDate/dueKm`，记录 `historyRecordId`；
 *   首次改写前保存 `historyBaseline`，幂等；无待办且方案存在时新建 `historyGenerated` 提醒；
 * - 无历史记录：删除自动生成的待办；手工提醒若有 `historyBaseline` 则回退。
 */
fun syncServiceHistory(ledger: Ledger): Ledger {
    val records = ledger.records.filter { isServiceRecord(it) && Dates.isValid(it.date) }
    val plans = resolveServicePlan(ledger.servicePlan.orEmpty()).filter { it.firstOnly != true }
    val titles = LinkedHashSet<String>()
    plans.forEach { titles.add(it.title.orEmpty()) }
    ledger.reminders
        .filter {
            it.firstOnly != true &&
                ((it.intervalKm ?: 0.0) != 0.0 || (it.intervalMonths ?: 0) != 0)
        }
        .forEach { titles.add(it.title) }

    var reminders = ledger.reminders.toMutableList()
    for (title in titles) {
        val history = records
            .filter { splitProjects(it.projects).contains(title) }
            .sortedWith(
                compareByDescending<Record> { it.date }
                    .thenByDescending { it.odometer ?: -1.0 },
            )
        val latest = history.firstOrNull()
        val pending = reminders.filter { !it.done && it.firstOnly != true && it.title == title }

        if (latest == null) {
            reminders = reminders
                .filterNot { it.title == title && !it.done && it.historyGenerated == true }
                .toMutableList()
            for (reminder in pending) {
                val baseline = reminder.historyBaseline ?: continue
                val index = reminders.indexOfFirst { it.id == reminder.id }
                if (index >= 0) reminders[index] = restoreBaseline(reminders[index], baseline)
            }
            continue
        }

        val plan = plans.firstOrNull { it.title == title }
        val working = pending.toMutableList()
        if (working.isEmpty() && plan != null) {
            working.add(historyGeneratedReminder(title, plan))
        }
        for (reminder in working) {
            val intervalKm = reminder.intervalKm ?: 0.0
            val intervalMonths = reminder.intervalMonths ?: 0
            if (intervalKm == 0.0 && intervalMonths == 0) continue

            var current = reminder
            if (current.historyGenerated != true && current.historyBaseline == null) {
                current = current.copy(
                    historyBaseline = HistoryBaseline(
                        baseDate = current.baseDate ?: "",
                        baseKm = current.baseKm,
                        dueDate = current.dueDate ?: "",
                        dueKm = current.dueKm,
                    ),
                )
            }
            val latestOdometer = latest.odometer
            current = current.copy(
                baseDate = latest.date,
                baseKm = latestOdometer,
                dueDate = if (intervalMonths > 0) Dates.addMonths(latest.date, intervalMonths) else "",
                dueKm = if (intervalKm > 0 && latestOdometer != null) latestOdometer + intervalKm else null,
                historyRecordId = latest.id,
            )
            val index = reminders.indexOfFirst { it.id == current.id }
            if (index >= 0) reminders[index] = current else reminders.add(current)
        }
    }
    return ledger.copy(reminders = reminders.toList())
}

/** 钱迹保养导入自动完成待办的结果。 */
data class CompleteImportedResult(
    val reminders: List<Reminder>,
    /** 自动完成的待办数量。 */
    val completed: Int,
)

/**
 * 仅用新导入的钱迹保养记录完成匹配的待办（Web `completeImportedServices`）。
 *
 * 保守匹配：提醒所需项目必须全部包含于记录项目；首保额外要求备注含「首保」；
 * 不早于基准日、同一记录不重复完成、完成日期须晚于上次完成。
 */
fun completeImportedServices(ledger: Ledger, recordIds: List<String>): CompleteImportedResult {
    var reminders = ledger.reminders.toMutableList()
    var completed = 0
    val records = ledger.records
        .filter {
            it.id in recordIds &&
                it.source == "钱迹" &&
                it.category in setOf("保养", "维修保养") &&
                Dates.isValid(it.date)
        }
        .sortedWith(compareBy<Record> { it.date }.thenBy { it.odometer ?: 0.0 })

    for (record in records) {
        val projects = splitProjects(record.projects)
        if (projects.isEmpty()) continue
        val ids = reminders.filter { !it.done }
            .sortedByDescending { it.firstOnly == true }
            .map { it.id }
        for (id in ids) {
            val reminder = reminders.firstOrNull { it.id == id } ?: continue
            if (reminder.done) continue

            val required = if (reminder.firstOnly == true) {
                listOf("机油机滤")
            } else {
                val projectsText = reminder.projects
                val raw: Any = if (!projectsText.isNullOrEmpty()) {
                    projectsText
                } else {
                    val inferred = inferServiceNote(Record(category = "保养", note = reminder.title)).projects
                    if (!inferred.isNullOrEmpty()) inferred else reminder.title
                }
                splitProjects(raw)
            }
            if (required.isEmpty() || !required.all { projects.contains(it) }) continue
            if (reminder.firstOnly == true && !record.note.contains("首保")) continue
            if (reminders.any { done ->
                    done.done && done.recordId == record.id &&
                        (done.title == reminder.title ||
                            (done.presetKey != null && done.presetKey == reminder.presetKey))
                }
            ) {
                continue
            }

            val dueDate = reminder.dueDate
            val intervalMonths = reminder.intervalMonths
            val baseline = when {
                !reminder.baseDate.isNullOrEmpty() -> reminder.baseDate.orEmpty()
                Dates.isValid(dueDate) && intervalMonths != null -> Dates.addMonths(dueDate!!, -intervalMonths)
                else -> ""
            }
            if (baseline.isNotEmpty() && record.date < baseline) continue

            val latestDone = reminders
                .filter { done ->
                    done.done && (done.title == reminder.title ||
                        (done.presetKey != null && done.presetKey == reminder.presetKey))
                }
                .mapNotNull { it.completedDate }
                .sorted()
                .lastOrNull()
            if (latestDone != null && record.date <= latestDone) continue

            // 首保完成已重设常规机油周期，不再用同一记录重复完成新的机油待办。
            if (reminder.title == "机油机滤" &&
                reminders.any { it.firstOnly == true && it.done && it.recordId == record.id }
            ) {
                continue
            }

            reminders = completeService(reminders, reminder, record).toMutableList()
            completed++
        }
    }
    return CompleteImportedResult(reminders.toList(), completed)
}
