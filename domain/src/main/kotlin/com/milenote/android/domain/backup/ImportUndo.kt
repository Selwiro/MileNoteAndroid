package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder

/**
 * 一次导入的**逆操作**参数（1.0 审查 #5）。
 *
 * 以前「撤销本次导入」是把整库回滚到导入前的快照：导入后自动同步拉下的远端记录会被一起抹掉，
 * 导入期间别处写进来的修改也回退。这里改成只反做这次导入真正改动过的东西：
 *
 * - [touched]：本次导入写入后的记录内容（新增 / 覆盖 / 合并都算）；
 * - [previous]：这些记录在导入前的内容（新增的记录没有这一项 → 撤销时删掉）；
 * - [remindersAfter] / [remindersBefore]：本次导入自动完成的提醒在前后各是什么样。
 *
 * 撤销时逐条比对：当前值仍等于 [touched] 才回退；用户导入后又改过的记录**跳过并计数**，
 * 不会把新修改一起撤掉。
 */
data class ImportUndo(
    /** 导入写入的目标车辆 id。 */
    val carId: String,
    /** id → 导入后的记录。 */
    val touched: Map<String, Record> = emptyMap(),
    /** id → 导入前的记录（只包含被覆盖 / 被合并的旧记录）。 */
    val previous: Map<String, Record> = emptyMap(),
    /** id → 导入后的提醒（被自动标完成的那几条）。 */
    val remindersAfter: Map<String, Reminder> = emptyMap(),
    /** id → 导入前的提醒。 */
    val remindersBefore: Map<String, Reminder> = emptyMap(),
) {
    val isEmpty: Boolean get() = touched.isEmpty() && remindersAfter.isEmpty()
}

/** 撤销导入的结果。 */
data class ImportUndoResult(
    val garage: Garage,
    /** 真正回退的条数（记录 + 提醒）。 */
    val undone: Int,
    /** 因为「导入后又被改过」而保留原样的条数。 */
    val skipped: Int,
)

/**
 * 只反做一次导入（见 [ImportUndo]）。
 *
 * 纯函数：不改入参；目标车辆不存在（例如已被删除）时原样返回，全部计入 [ImportUndoResult.skipped]。
 */
fun undoImport(garage: Garage, undo: ImportUndo): ImportUndoResult {
    val car = garage.cars.firstOrNull { it.id == undo.carId }
        ?: return ImportUndoResult(garage, undone = 0, skipped = undo.touched.size + undo.remindersAfter.size)

    var undone = 0
    var skipped = 0

    val records = buildList {
        for (record in car.records) {
            val after = undo.touched[record.id]
            if (after == null) {
                add(record)
                continue
            }
            if (record != after) {
                // 导入后用户又改过它：保留现在的值，别把新修改一起撤掉。
                skipped++
                add(record)
                continue
            }
            undone++
            undo.previous[record.id]?.let { add(it) }
        }
    }

    val reminders = car.reminders.map { reminder ->
        val after = undo.remindersAfter[reminder.id]
        val before = undo.remindersBefore[reminder.id]
        if (after == null || before == null) {
            reminder
        } else if (reminder != after) {
            skipped++
            reminder
        } else {
            undone++
            before
        }
    }

    val updated: Ledger = car.copy(records = records, reminders = reminders)
    return ImportUndoResult(
        garage = garage.copy(cars = garage.cars.map { if (it.id == updated.id) updated else it }),
        undone = undone,
        skipped = skipped,
    )
}

/**
 * 对比导入前后的同一辆车，算出 [ImportUndo]。
 *
 * 用「内容对比」而不是「记下写入了哪些 id」：合并（判重命中只补升数 / 备注）、覆盖、
 * 自动完成提醒都会改到别的记录，逐条对比才能全部照顾到。
 */
fun buildImportUndo(carId: String, before: Ledger?, after: Ledger?): ImportUndo {
    if (after == null) return ImportUndo(carId)
    val beforeRecords = before?.records.orEmpty().associateBy { it.id }
    val touched = LinkedHashMap<String, Record>()
    val previous = LinkedHashMap<String, Record>()
    for (record in after.records) {
        val old = beforeRecords[record.id]
        if (old != record) {
            touched[record.id] = record
            if (old != null) previous[record.id] = old
        }
    }
    val beforeReminders = before?.reminders.orEmpty().associateBy { it.id }
    val remindersAfter = LinkedHashMap<String, Reminder>()
    val remindersBefore = LinkedHashMap<String, Reminder>()
    for (reminder in after.reminders) {
        val old = beforeReminders[reminder.id] ?: continue
        if (old != reminder) {
            remindersAfter[reminder.id] = reminder
            remindersBefore[reminder.id] = old
        }
    }
    return ImportUndo(
        carId = carId,
        touched = touched,
        previous = previous,
        remindersAfter = remindersAfter,
        remindersBefore = remindersBefore,
    )
}
