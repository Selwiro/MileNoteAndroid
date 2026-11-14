package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Tombstone

/**
 * 本地修改打点：给真正改过内容的记录 / 提醒写 `updatedAt`，给被删除的记录留墓碑。
 *
 * 多设备同步（WebDAV）靠这两个信息做「后写胜出」与删除传播。打点必须**只在内容真变化时**发生：
 * 每次保存都刷新时间戳会让两台设备永远互相「改过同一条」，合并永远选不出胜者。
 *
 * 调用约定：
 * - 只由 [com.milenote.android.domain.backup.SyncStamps] 的调用方（`GarageRepository.update`）在
 *   **写入本地账本前**执行；
 * - 整体覆盖（WebDAV 下载 / 还原备份 / 回滚）**不要**打点，否则远端带来的时间戳会被本机覆盖。
 */
object SyncStamps {

    /**
     * 用 [before]（写入前）与 [after]（本次修改后）的差异补全时间戳与墓碑。
     *
     * - 新增或内容变化的记录 / 提醒 → `updatedAt = now`；
     * - 内容没变的记录 / 提醒 → 原样保留（含原有 `updatedAt`）；
     * - `before` 有、`after` 没有的记录 → 追加墓碑（同 id 取较大时刻）；
     * - `after` 里存在且墓碑更旧的记录 → 移除墓碑（重新创建 / 撤销删除视为复活）；
     * - 其他墓碑原样保留。
     *
     * 纯函数：不修改入参，返回新对象。
     *
     * @param now 打点时刻（epoch 毫秒）；由调用方传入便于单测。
     */
    fun stampChanges(before: Garage, after: Garage, now: Long): Garage {
        val beforeCars = before.cars.associateBy { it.id }
        return after.copy(cars = after.cars.map { car -> stampCar(beforeCars[car.id], car, now) })
    }

    private fun stampCar(before: Ledger?, after: Ledger, now: Long): Ledger {
        val records = stampRecords(before?.records.orEmpty(), after.records, now)
        val reminders = stampReminders(before?.reminders.orEmpty(), after.reminders, now)
        val tombstones = tombstones(before?.records.orEmpty(), after.records, after.tombstones, now)
        return after.copy(records = records, reminders = reminders, tombstones = tombstones)
    }

    private fun stampRecords(before: List<Record>, after: List<Record>, now: Long): List<Record> {
        if (before.isEmpty() && after.isEmpty()) return after
        val previous = before.associateBy { it.id }
        return after.map { record ->
            val old = previous[record.id]
            when {
                old == null -> record.copy(updatedAt = now)
                !sameRecordContent(old, record) -> record.copy(updatedAt = now)
                else -> record
            }
        }
    }

    private fun stampReminders(before: List<Reminder>, after: List<Reminder>, now: Long): List<Reminder> {
        if (before.isEmpty() && after.isEmpty()) return after
        val previous = before.associateBy { it.id }
        return after.map { reminder ->
            val old = previous[reminder.id]
            when {
                old == null -> reminder.copy(updatedAt = now)
                !sameReminderContent(old, reminder) -> reminder.copy(updatedAt = now)
                else -> reminder
            }
        }
    }

    /**
     * 墓碑集合：保留旧墓碑 + 本次新删的 + 去掉已复活的。
     *
     * 返回 null 表示没有墓碑（编码时省略该字段，备份更干净）。
     */
    private fun tombstones(
        before: List<Record>,
        after: List<Record>,
        existing: List<Tombstone>?,
        now: Long,
    ): List<Tombstone>? {
        val afterIds = after.mapTo(HashSet()) { it.id }
        val merged = LinkedHashMap<String, Long>()
        existing.orEmpty().forEach { tombstone ->
            if (tombstone.id.isNotEmpty()) merged[tombstone.id] = maxOf(merged[tombstone.id] ?: 0L, tombstone.at)
        }
        // 本次删除：before 有、after 没有 → 新墓碑（已有墓碑取较新时刻）。
        for (record in before) {
            if (record.id.isEmpty() || record.id in afterIds) continue
            merged[record.id] = maxOf(merged[record.id] ?: now, now)
        }
        // 记录又活着（新建同 id / 撤销删除）→ 墓碑不再需要。
        afterIds.forEach { merged.remove(it) }
        if (merged.isEmpty()) return null
        return merged.entries
            .map { Tombstone(id = it.key, at = it.value) }
            .sortedWith(compareBy<Tombstone> { it.at }.thenBy { it.id })
    }

    /** 记录内容是否相同（忽略 `updatedAt` 本身，否则每次打点都会被判成「又改了」）。 */
    fun sameRecordContent(a: Record, b: Record): Boolean =
        a.copy(updatedAt = null) == b.copy(updatedAt = null)

    /** 提醒内容是否相同（忽略 `updatedAt` 本身）。 */
    fun sameReminderContent(a: Reminder, b: Reminder): Boolean =
        a.copy(updatedAt = null) == b.copy(updatedAt = null)

    /** 两辆车的内容是否相同（忽略每条记录 / 提醒的 `updatedAt`）。 */
    fun sameLedgerContent(a: Ledger, b: Ledger): Boolean =
        a.copy(records = a.records.map { it.copy(updatedAt = null) }, reminders = a.reminders.map { it.copy(updatedAt = null) }) ==
            b.copy(records = b.records.map { it.copy(updatedAt = null) }, reminders = b.reminders.map { it.copy(updatedAt = null) })

    /** 整个车库的内容是否相同（忽略时间戳与墓碑排序）。 */
    fun sameGarageContent(a: Garage, b: Garage): Boolean =
        a.cars.size == b.cars.size &&
            a.cars.zip(b.cars).all { (x, y) -> x.id == y.id && sameLedgerContent(x, y) }
}
