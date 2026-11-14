package com.milenote.android.domain.backup

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.splitProjects
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Tombstone
import com.milenote.android.domain.model.normalizePowertrain

/**
 * 一条自动裁决不了的冲突：同一条记录 / 提醒在两边都被改过，且 `updatedAt` 完全相同。
 *
 * 出现概率极低（毫秒级撞车）；合并时先保留本机版本并把它报出来，由用户在设置页裁决。
 */
data class MergeConflict(
    val carId: String,
    val kind: Kind,
    val id: String,
    /** 面向用户的短标签（记录=「日期 分类」，提醒=标题）。 */
    val label: String,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
) {
    enum class Kind { RECORD, REMINDER }
}

/**
 * 一次三向合并的结果摘要（用于界面文案「已自动合并：新增 2、更新 1、删除 1」）。
 *
 * 计数口径是「相对本机本地账本的变化」：`added` = 远端有、本机没有；`updated` = 两边都有但
 * 合并后内容与本机不同（远端胜出）；`deleted` = 本机有、合并后没有（远端删除已传播到本机）。
 */
data class MergeSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    /** 删除 vs 修改冲突里被保留的修改条数（不丢数据的自动裁决）。 */
    val deleteVsEditKept: Int = 0,
    /** 无基线时以本机为准的条数（升级后首次同步等）。 */
    val localWinsWithoutBase: Int = 0,
    val remindersChanged: Int = 0,
    val conflicts: List<MergeConflict> = emptyList(),
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()

    /** 相对本机发生的记录变化条数。 */
    val changes: Int get() = added + updated + deleted

    val isEmpty: Boolean get() = changes == 0 && remindersChanged == 0
}

/**
 * 记录级三向合并（base = 上次同步时的样子；local = 本机；remote = 远端）。
 *
 * 设计要点（见 `work/docs/design/webdav-multi-device-sync-20261114.md` §4.4）：
 * - 记录 / 提醒按 `id` 匹配，**两边只改不同记录时自然收敛为并集**；
 * - 同一条两边都改 → `updatedAt` 大者胜（后写胜出），完全相同才报 [MergeConflict]；
 * - 删除传播靠 [Ledger.tombstones]：一边删除、另一边没改 → 删除生效；另一边改过 → 保留修改（不丢数据）；
 * - 墓碑比记录新 → 记录丢弃（旧设备无法把删掉的记录同步回来）；记录比墓碑新 → 视为刻意重建，墓碑移除；
 * - 账本级配置（分类、项目库、方案）按「本机改过就本机优先、否则取远端」+ 并集合并；
 * - 车辆级删除**不传播**（本轮不涉及；按 id 取并集，最坏情况是删掉的车被另一台设备带回来）。
 *
 * 纯函数：不修改入参，不读写文件，不依赖 Android；时间戳由调用方传入便于单测。
 */
object SyncMerge {

    /** 删除墓碑保留 180 天，超出在合并时修剪（足够覆盖长期离线的设备）。 */
    const val TOMBSTONE_RETENTION_MS: Long = 180L * 24 * 60 * 60 * 1000

    /** 合并结果：车库 + 摘要。 */
    data class Result(
        val garage: Garage,
        val summary: MergeSummary,
    )

    /**
     * 三向合并车库。
     *
     * @param base 上次同步时的车库；null = 没有可信基线（升级后首次同步 / 状态被清），走降级策略：
     *   同一条两边都改时**本机优先**，且不报冲突（否则升级后第一次同步就会卡住）。
     * @param now 当前时刻（epoch 毫秒），用于修剪墓碑。
     */
    fun mergeGarage(base: Garage?, local: Garage, remote: Garage, now: Long): Result {
        val stats = Stats(hasBase = base != null)
        val baseCars = base?.cars.orEmpty().associateBy { it.id }
        val localCars = local.cars.associateBy { it.id }
        val remoteCars = remote.cars.associateBy { it.id }
        // 顺序：本机车辆顺序优先，远端多出来的车按远端顺序追加。
        val order = LinkedHashSet<String>()
        local.cars.forEach { order += it.id }
        remote.cars.forEach { order += it.id }
        baseCars.keys.forEach { order += it }

        val cars = mutableListOf<Ledger>()
        for (carId in order) {
            val localCar = localCars[carId]
            val remoteCar = remoteCars[carId]
            when {
                localCar != null && remoteCar != null ->
                    cars += mergeCar(baseCars[carId], localCar, remoteCar, now, stats)
                localCar != null -> cars += localCar
                remoteCar != null -> cars += remoteCar
            }
        }
        // 兜底：空车库不合法（normalizeGarage 要求至少一辆车），退回本机账本。
        if (cars.isEmpty()) return Result(local, MergeSummary())
        val garage = local.copy(
            cars = cars,
            activeId = local.activeId.takeIf { id -> cars.any { it.id == id } } ?: cars.first().id,
        )
        return Result(garage, stats.toSummary())
    }

    // -----------------------------------------------------------------------------------------
    // 车辆级
    // -----------------------------------------------------------------------------------------

    private fun mergeCar(base: Ledger?, local: Ledger, remote: Ledger, now: Long, stats: Stats): Ledger {
        val (records, tombstones) = mergeRecords(base, local, remote, now, stats)
        val reminders = mergeReminders(base, local, remote, stats)
        return mergeConfig(base, local, remote).copy(
            id = local.id,
            records = records,
            reminders = reminders,
            tombstones = tombstones,
        )
    }

    // -----------------------------------------------------------------------------------------
    // 记录与墓碑
    // -----------------------------------------------------------------------------------------

    private fun mergeRecords(
        base: Ledger?,
        local: Ledger,
        remote: Ledger,
        now: Long,
        stats: Stats,
    ): Pair<List<Record>, List<Tombstone>?> {
        val baseRecords = base?.records.orEmpty().associateBy { it.id }
        val localRecords = local.records.associateBy { it.id }
        val remoteRecords = remote.records.associateBy { it.id }

        val tombstones = LinkedHashMap<String, Long>()
        fun addTombstone(id: String, at: Long) {
            if (id.isEmpty()) return
            val previous = tombstones[id]
            tombstones[id] = if (previous == null) at else maxOf(previous, at)
        }
        base?.tombstones.orEmpty().forEach { addTombstone(it.id, it.at) }
        local.tombstones.orEmpty().forEach { addTombstone(it.id, it.at) }
        remote.tombstones.orEmpty().forEach { addTombstone(it.id, it.at) }
        // 修剪过期墓碑（保留 180 天）。
        tombstones.filterValues { now - it > TOMBSTONE_RETENTION_MS }.keys.toList().forEach { tombstones.remove(it) }

        val ids = LinkedHashSet<String>()
        local.records.forEach { ids += it.id }
        remote.records.forEach { ids += it.id }
        baseRecords.keys.forEach { ids += it }
        tombstones.keys.forEach { ids += it }

        val merged = mutableListOf<Record>()
        for (id in ids) {
            val localRecord = localRecords[id]
            val remoteRecord = remoteRecords[id]
            val baseRecord = baseRecords[id]
            val deletedAt = tombstones[id]
            var winner: Record? = when {
                localRecord != null && remoteRecord != null ->
                    pickRecord(baseRecord, localRecord, remoteRecord, local.id, stats)
                localRecord != null -> when {
                    // 远端没有这条：远端从未有过 → 保留；远端删了而本机没改 → 删除。
                    baseRecord == null -> localRecord
                    SyncStamps.sameRecordContent(localRecord, baseRecord) -> null
                    else -> {
                        // 删除 vs 修改：保留修改，不丢数据。
                        stats.deleteVsEditKept++
                        localRecord
                    }
                }
                remoteRecord != null -> when {
                    baseRecord == null -> remoteRecord
                    SyncStamps.sameRecordContent(remoteRecord, baseRecord) -> null
                    else -> {
                        stats.deleteVsEditKept++
                        remoteRecord
                    }
                }
                else -> null
            }
            if (winner != null && deletedAt != null) {
                if (deletedAt > (winner.updatedAt ?: 0L)) {
                    // 删除发生在这条记录的最后一次修改之后 → 删除仍然有效（旧设备不能复活它）。
                    winner = null
                } else {
                    // 记录比墓碑新：刻意重建 / 撤销删除，墓碑作废。
                    tombstones.remove(id)
                }
            }
            if (winner != null) {
                merged += winner
                val previous = localRecords[id]
                when {
                    previous == null -> stats.added++
                    !SyncStamps.sameRecordContent(previous, winner) -> stats.updated++
                }
            } else if (localRecords[id] != null) {
                stats.deleted++
            }
        }

        val result = if (tombstones.isEmpty()) {
            null
        } else {
            tombstones.entries
                .map { Tombstone(id = it.key, at = it.value) }
                .sortedWith(compareBy<Tombstone> { it.at }.thenBy { it.id })
        }
        return merged to result
    }

    /** 两边都有同一条记录：判断谁改过，都改过则按 `updatedAt` 后写胜出。 */
    private fun pickRecord(
        base: Record?,
        local: Record,
        remote: Record,
        carId: String,
        stats: Stats,
    ): Record = when {
        SyncStamps.sameRecordContent(local, remote) -> local
        base != null && SyncStamps.sameRecordContent(local, base) -> remote
        base != null && SyncStamps.sameRecordContent(remote, base) -> local
        else -> {
            val localAt = local.updatedAt ?: 0L
            val remoteAt = remote.updatedAt ?: 0L
            when {
                // 无基线：本机优先，不阻塞同步（升级后第一次同步 / 同步状态被清）。
                !stats.hasBase -> {
                    stats.localWinsWithoutBase++
                    local
                }
                localAt > remoteAt -> local
                remoteAt > localAt -> remote
                else -> {
                    stats.conflicts += MergeConflict(
                        carId = carId,
                        kind = MergeConflict.Kind.RECORD,
                        id = local.id,
                        label = "${local.date} ${Categories.normalize(local.category)}".trim(),
                        localUpdatedAt = localAt,
                        remoteUpdatedAt = remoteAt,
                    )
                    local
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 提醒（没有墓碑：删除 vs 修改一律保留修改）
    // -----------------------------------------------------------------------------------------

    private fun mergeReminders(base: Ledger?, local: Ledger, remote: Ledger, stats: Stats): List<Reminder> {
        val baseReminders = base?.reminders.orEmpty().associateBy { it.id }
        val localReminders = local.reminders.associateBy { it.id }
        val remoteReminders = remote.reminders.associateBy { it.id }
        val ids = LinkedHashSet<String>()
        local.reminders.forEach { ids += it.id }
        remote.reminders.forEach { ids += it.id }
        baseReminders.keys.forEach { ids += it }

        val merged = mutableListOf<Reminder>()
        for (id in ids) {
            val localReminder = localReminders[id]
            val remoteReminder = remoteReminders[id]
            val baseReminder = baseReminders[id]
            val winner: Reminder? = when {
                localReminder != null && remoteReminder != null -> pickReminder(
                    baseReminder, localReminder, remoteReminder, local.id, stats,
                )
                localReminder != null -> when {
                    baseReminder == null -> localReminder
                    SyncStamps.sameReminderContent(localReminder, baseReminder) -> null
                    else -> localReminder
                }
                remoteReminder != null -> when {
                    baseReminder == null -> remoteReminder
                    SyncStamps.sameReminderContent(remoteReminder, baseReminder) -> null
                    else -> remoteReminder
                }
                else -> null
            }
            if (winner != null) {
                merged += winner
                val previous = localReminders[id]
                if (previous == null || !SyncStamps.sameReminderContent(previous, winner)) {
                    stats.remindersChanged++
                }
            }
        }
        return merged
    }

    private fun pickReminder(
        base: Reminder?,
        local: Reminder,
        remote: Reminder,
        carId: String,
        stats: Stats,
    ): Reminder = when {
        SyncStamps.sameReminderContent(local, remote) -> local
        base != null && SyncStamps.sameReminderContent(local, base) -> remote
        base != null && SyncStamps.sameReminderContent(remote, base) -> local
        else -> {
            val localAt = local.updatedAt ?: 0L
            val remoteAt = remote.updatedAt ?: 0L
            when {
                !stats.hasBase -> {
                    stats.localWinsWithoutBase++
                    local
                }
                localAt > remoteAt -> local
                remoteAt > localAt -> remote
                else -> {
                    stats.conflicts += MergeConflict(
                        carId = carId,
                        kind = MergeConflict.Kind.REMINDER,
                        id = local.id,
                        label = local.title,
                        localUpdatedAt = localAt,
                        remoteUpdatedAt = remoteAt,
                    )
                    local
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 账本级配置
    // -----------------------------------------------------------------------------------------

    private fun mergeConfig(base: Ledger?, local: Ledger, remote: Ledger): Ledger {
        val hasBase = base != null
        val servicePlan = pick(local.servicePlan, remote.servicePlan, base?.servicePlan, hasBase)
        val servicePlanBase = pick(local.servicePlanBase, remote.servicePlanBase, base?.servicePlanBase, hasBase)
        val vehicle = pick(local.vehicle, remote.vehicle, base?.vehicle, hasBase)
            .let { it.copy(powertrain = normalizePowertrain(it.powertrain)) }
        val byCategory = LinkedHashMap<String, List<String>>()
        for ((category, projects) in local.serviceProjectsByCategory.orEmpty()) {
            byCategory[category] = splitProjects(projects)
        }
        for ((category, projects) in remote.serviceProjectsByCategory.orEmpty()) {
            byCategory[category] = splitProjects(byCategory[category].orEmpty() + projects)
        }
        val hiddenProjects = LinkedHashMap<String, List<String>>()
        for ((category, projects) in local.hiddenServiceProjects.orEmpty()) {
            hiddenProjects[category] = projects.distinct()
        }
        for ((category, projects) in remote.hiddenServiceProjects.orEmpty()) {
            hiddenProjects[category] = (hiddenProjects[category].orEmpty() + projects).distinct()
        }
        return local.copy(
            vehicle = vehicle,
            customCategories = orNullIfEmpty(
                (local.customCategories.orEmpty() + remote.customCategories.orEmpty()).distinct(),
                local.customCategories,
                remote.customCategories,
            ),
            servicePlan = servicePlan,
            servicePlanBase = servicePlanBase,
            servicePlanConfigured = when {
                local.servicePlanConfigured == true || remote.servicePlanConfigured == true -> true
                local.servicePlanConfigured == false && remote.servicePlanConfigured == false -> false
                else -> null
            },
            serviceProjects = orNullIfEmpty(
                splitProjects(local.serviceProjects.orEmpty() + remote.serviceProjects.orEmpty()),
                local.serviceProjects,
                remote.serviceProjects,
            ),
            serviceProjectsByCategory = orNullIfEmpty(byCategory, local.serviceProjectsByCategory, remote.serviceProjectsByCategory),
            hiddenCategories = orNullIfEmpty(
                (local.hiddenCategories.orEmpty() + remote.hiddenCategories.orEmpty())
                    .map { Categories.normalize(it) }
                    .filter { it.isNotEmpty() }
                    .distinct(),
                local.hiddenCategories,
                remote.hiddenCategories,
            ),
            categoryLabels = orNullIfEmpty(
                remote.categoryLabels.orEmpty() + local.categoryLabels.orEmpty(),
                local.categoryLabels,
                remote.categoryLabels,
            ),
            hiddenServiceProjects = orNullIfEmpty(hiddenProjects, local.hiddenServiceProjects, remote.hiddenServiceProjects),
            serviceProjectLabels = orNullIfEmpty(
                remote.serviceProjectLabels.orEmpty() + local.serviceProjectLabels.orEmpty(),
                local.serviceProjectLabels,
                remote.serviceProjectLabels,
            ),
        )
    }

    /** 合并结果为空且两边本来都没有这个字段时保留 null（不让备份多出空数组）。 */
    private fun <T> orNullIfEmpty(merged: T, local: T?, remote: T?): T? =
        if (isEmpty(merged) && local == null && remote == null) null else merged

    private fun isEmpty(value: Any?): Boolean = when (value) {
        null -> true
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    /**
     * 账本级单项配置：本机改过就本机优先，本机没改就用远端；无基线时本机优先。
     *
     * 这些字段没有逐项时间戳，用「相对基线是否变化」判断，避免每次同步都互相覆盖。
     */
    private fun <T> pick(local: T, remote: T, base: T?, hasBase: Boolean): T = when {
        !hasBase -> local
        local == base -> remote
        else -> local
    }

    // -----------------------------------------------------------------------------------------
    // 统计
    // -----------------------------------------------------------------------------------------

    private class Stats(val hasBase: Boolean) {
        var added = 0
        var updated = 0
        var deleted = 0
        var deleteVsEditKept = 0
        var localWinsWithoutBase = 0
        var remindersChanged = 0
        val conflicts = mutableListOf<MergeConflict>()

        fun toSummary(): MergeSummary = MergeSummary(
            added = added,
            updated = updated,
            deleted = deleted,
            deleteVsEditKept = deleteVsEditKept,
            localWinsWithoutBase = localWinsWithoutBase,
            remindersChanged = remindersChanged,
            conflicts = conflicts.toList(),
        )
    }
}
