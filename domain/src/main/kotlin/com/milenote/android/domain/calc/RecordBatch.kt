package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder

/**
 * 费用明细的批量操作：批量改分类 / 批量删除 / 删除撤销。
 *
 * 全部是纯函数（账本进、账本出），界面层只负责选哪几条；
 * 删除同时产生 [DeletedRecords] 快照，供 Snackbar「撤销」原位还原。
 */

/**
 * 一次删除的撤销快照。
 *
 * @property records 被删除的记录（按删除前顺序）。
 * @property indices 与 [records] 一一对应的原始下标（`ledger.records`），撤销时按原位插回。
 * @property reminders 被这次删除改动的提醒（删除前状态 + 原下标）；含被移除的下一轮提醒。
 */
data class DeletedRecords(
    val records: List<Record> = emptyList(),
    val indices: List<Int> = emptyList(),
    val reminders: List<ReminderAt> = emptyList(),
) {
    val ids: Set<String> get() = records.mapTo(LinkedHashSet()) { it.id }
    val isEmpty: Boolean get() = records.isEmpty()
}

/** 提醒的删除前快照与原下标。 */
data class ReminderAt(
    val index: Int,
    val reminder: Reminder,
)

/** [deleteRecords] 的结果：删除后的账本 + 可用于撤销的快照。 */
data class RecordRemoval(
    val ledger: Ledger,
    val undo: DeletedRecords,
)

/**
 * 批量删除记录。
 *
 * @param ids 要删除的记录编号（不存在 / 重复的编号自动忽略）。
 * @param restoreReminders 是否把被删记录「完成过」的保养提醒恢复为未完成
 *   （单条删除时用户可选「仅删除记录」；批量删除统一恢复）。
 */
fun removeRecords(
    ledger: Ledger,
    ids: Set<String>,
    restoreReminders: Boolean = true,
): RecordRemoval {
    if (ids.isEmpty()) return RecordRemoval(ledger, DeletedRecords())
    // 按原顺序记录下标，撤销时才能原位插回（列表按日期排序渲染，但账本顺序保持稳定）。
    val removed = ledger.records.mapIndexedNotNull { index, record ->
        if (record.id in ids) index to record else null
    }
    if (removed.isEmpty()) return RecordRemoval(ledger, DeletedRecords())
    val remainingIds = removed.mapTo(HashSet()) { it.second.id }

    val before = ledger.reminders
    val after = if (restoreReminders) {
        removed.fold(before) { reminders, (_, record) ->
            restoreServiceRemindersAfterRecordDelete(reminders, record)
        }
    } else {
        before.map { reminder ->
            if (reminder.recordId in remainingIds) reminder.copy(recordId = null) else reminder
        }
    }
    val afterById = after.associateBy { it.id }
    val touched = before.mapIndexedNotNull { index, old ->
        if (afterById[old.id] != old) ReminderAt(index, old) else null
    }

    return RecordRemoval(
        ledger = ledger.copy(
            records = ledger.records.filterNot { it.id in remainingIds },
            reminders = after,
        ),
        undo = DeletedRecords(
            records = removed.map { it.second },
            indices = removed.map { it.first },
            reminders = touched,
        ),
    )
}

/**
 * 撤销一次删除：把记录按原下标插回、把受影响的提醒恢复为删除前状态。
 *
 * 幂等：记录 / 提醒若仍在账本里（例如期间同步又拉回来了）不重复插入，只补齐快照里的字段。
 */
fun restoreRemovedRecords(ledger: Ledger, undo: DeletedRecords): Ledger {
    if (undo.isEmpty) return ledger
    val records = ledger.records.toMutableList()
    undo.records.forEachIndexed { i, record ->
        if (records.none { it.id == record.id }) {
            val at = undo.indices.getOrElse(i) { records.size }.coerceIn(0, records.size)
            records.add(at, record)
        }
    }
    val reminders = ledger.reminders.toMutableList()
    undo.reminders.forEach { snapshot ->
        val current = reminders.indexOfFirst { it.id == snapshot.reminder.id }
        if (current >= 0) {
            reminders[current] = snapshot.reminder
        } else {
            val at = snapshot.index.coerceIn(0, reminders.size)
            reminders.add(at, snapshot.reminder)
        }
    }
    return ledger.copy(records = records, reminders = reminders)
}

/**
 * 批量改分类：只改选中记录的 `category`，金额 / 日期 / 备注等字段与账本顺序不变。
 *
 * 分类按 [Categories.normalize] 归一后再写入（`维修保养` → `保养`，与全仓口径一致）；
 * 不放宽 `version:2` 结构，也不动 `projects` 等字段。
 */
fun applyCategoryToRecords(ledger: Ledger, ids: Set<String>, category: String): Ledger {
    val target = Categories.normalize(category)
    if (ids.isEmpty() || target.isEmpty()) return ledger
    return ledger.copy(
        records = ledger.records.map { record ->
            if (record.id in ids && record.category != target) record.copy(category = target) else record
        },
    )
}
