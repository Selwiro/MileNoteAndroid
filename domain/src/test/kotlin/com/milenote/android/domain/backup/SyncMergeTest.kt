package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanBase
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Tombstone
import com.milenote.android.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录级三向合并（`SyncMerge`）：并集收敛、后写胜出、删除传播、防复活、无基线降级。
 *
 * 场景与 T019 的验收一一对应：两台设备交替改不同记录 / 改同一条 / 一方删除。
 */
class SyncMergeTest {

    private fun record(id: String, amount: Double = 100.0, updatedAt: Long? = null) = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        kind = "expense",
        updatedAt = updatedAt,
    )

    private fun reminder(id: String, title: String = "换机油", updatedAt: Long? = null) = Reminder(
        id = id,
        title = title,
        intervalKm = 10_000.0,
        done = false,
        updatedAt = updatedAt,
    )

    private fun ledger(
        records: List<Record> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        tombstones: List<Tombstone>? = null,
        customCategories: List<String>? = null,
        hiddenCategories: List<String>? = null,
    ): Ledger = Ledger(
        version = 1,
        id = "car1",
        vehicle = Vehicle(name = "测试车", currentKm = 1000.0),
        records = records,
        reminders = reminders,
        tombstones = tombstones,
        customCategories = customCategories,
        hiddenCategories = hiddenCategories,
    )

    private fun garage(ledger: Ledger) = Garage(version = 2, activeId = ledger.id, cars = listOf(ledger))

    private fun ids(g: Garage): List<String> = g.cars.single().records.map { it.id }

    // ---------------------------------------------------------------------------------------
    // 记录：并集 / 后写胜出
    // ---------------------------------------------------------------------------------------

    @Test
    fun `disjoint edits converge to union`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L), record("r2", 200.0, 10L))))
        // A 改 r1；B 改 r2。
        val local = garage(ledger(records = listOf(record("r1", 111.0, 20L), record("r2", 200.0, 10L))))
        val remote = garage(ledger(records = listOf(record("r1", 100.0, 10L), record("r2", 222.0, 21L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(listOf("r1", "r2"), ids(merged.garage))
        assertEquals(111.0, merged.garage.cars.single().records[0].amount, 0.0)
        assertEquals(222.0, merged.garage.cars.single().records[1].amount, 0.0)
        assertEquals(1, merged.summary.updated) // 相对本机，r2 被远端更新拿走
        assertEquals(0, merged.summary.added)
        assertEquals(0, merged.summary.deleted)
        assertFalse(merged.summary.hasConflicts)
    }

    @Test
    fun `remote adds records - they are appended and counted as added`() {
        val base = garage(ledger(records = listOf(record("r1", updatedAt = 10L))))
        val local = base
        val remote = garage(ledger(records = listOf(record("r1", updatedAt = 10L), record("r2", 5.0, 30L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(listOf("r1", "r2"), ids(merged.garage))
        assertEquals(1, merged.summary.added)
    }

    @Test
    fun `same record edited on both sides - newer updatedAt wins`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 20L))))
        val remote = garage(ledger(records = listOf(record("r1", 300.0, 30L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(300.0, merged.garage.cars.single().records.single().amount, 0.0)
        assertEquals(1, merged.summary.updated)
    }

    @Test
    fun `same record edited on both sides - local newer keeps local`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 40L))))
        val remote = garage(ledger(records = listOf(record("r1", 300.0, 30L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(150.0, merged.garage.cars.single().records.single().amount, 0.0)
        assertEquals(0, merged.summary.updated)
    }

    @Test
    fun `same record with equal timestamps is reported as conflict`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 50L))))
        val remote = garage(ledger(records = listOf(record("r1", 300.0, 50L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertTrue(merged.summary.hasConflicts)
        assertEquals("r1", merged.summary.conflicts.single().id)
        // 先保留本机版本，等用户裁决。
        assertEquals(150.0, merged.garage.cars.single().records.single().amount, 0.0)
    }

    // ---------------------------------------------------------------------------------------
    // 删除传播 / 防复活
    // ---------------------------------------------------------------------------------------

    @Test
    fun `remote deletion propagates when local untouched`() {
        val base = garage(ledger(records = listOf(record("r1", updatedAt = 10L), record("r2", updatedAt = 10L))))
        val local = base
        val remote = garage(ledger(records = listOf(record("r1", updatedAt = 10L)), tombstones = listOf(Tombstone("r2", 20L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(listOf("r1"), ids(merged.garage))
        assertEquals(1, merged.summary.deleted)
        assertEquals(listOf("r2"), merged.garage.cars.single().tombstones.orEmpty().map { it.id })
    }

    @Test
    fun `local deletion propagates to remote side`() {
        val base = garage(ledger(records = listOf(record("r1", updatedAt = 10L))))
        val local = garage(ledger(records = emptyList(), tombstones = listOf(Tombstone("r1", 20L))))
        val remote = base

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertTrue(ids(merged.garage).isEmpty())
        assertEquals(listOf("r1"), merged.garage.cars.single().tombstones.orEmpty().map { it.id })
        // 本机已经删掉了，不算「本机被删」。
        assertEquals(0, merged.summary.deleted)
    }

    @Test
    fun `edit beats delete - modified record is kept`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 30L))))
        val remote = garage(ledger(records = emptyList(), tombstones = listOf(Tombstone("r1", 20L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals(listOf("r1"), ids(merged.garage))
        assertEquals(150.0, merged.garage.cars.single().records.single().amount, 0.0)
        assertEquals(1, merged.summary.deleteVsEditKept)
        // 记录比墓碑新 → 墓碑作废。
        assertNull(merged.garage.cars.single().tombstones)
    }

    @Test
    fun `tombstone newer than record prevents resurrection from stale device`() {
        // 旧设备带着过期副本回来（updatedAt 很旧），远端已经删过它。
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L)), tombstones = listOf(Tombstone("r1", 500L))))
        val local = base
        val remote = garage(ledger(records = emptyList(), tombstones = listOf(Tombstone("r1", 500L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertTrue(ids(merged.garage).isEmpty())
        assertEquals(listOf("r1"), merged.garage.cars.single().tombstones.orEmpty().map { it.id })
    }

    @Test
    fun `expired tombstones are pruned after 180 days`() {
        val now = 1_000L
        val old = now - SyncMerge.TOMBSTONE_RETENTION_MS - 1
        val base = garage(ledger(tombstones = listOf(Tombstone("r-old", old), Tombstone("r-new", now - 1_000L))))

        val merged = SyncMerge.mergeGarage(base, base, base, now = now)

        assertEquals(listOf("r-new"), merged.garage.cars.single().tombstones.orEmpty().map { it.id })
    }

    // ---------------------------------------------------------------------------------------
    // 无基线（升级后首次同步）
    // ---------------------------------------------------------------------------------------

    @Test
    fun `without baseline local wins and nothing blocks`() {
        val local = garage(ledger(records = listOf(record("r1", 150.0), record("r2", 200.0))))
        val remote = garage(ledger(records = listOf(record("r1", 300.0), record("r3", 400.0))))

        val merged = SyncMerge.mergeGarage(base = null, local, remote, now = 1_000L)

        assertFalse(merged.summary.hasConflicts)
        assertEquals(listOf("r1", "r2", "r3"), ids(merged.garage))
        assertEquals(150.0, merged.garage.cars.single().records.first().amount, 0.0)
        assertTrue(merged.summary.localWinsWithoutBase >= 1)
    }

    // ---------------------------------------------------------------------------------------
    // 提醒与账本配置
    // ---------------------------------------------------------------------------------------

    @Test
    fun `reminders merge by id with later timestamp winning`() {
        val base = garage(ledger(reminders = listOf(reminder("m1", "换机油", 10L), reminder("m2", "换轮胎", 10L))))
        val local = garage(ledger(reminders = listOf(reminder("m1", "换机油", 20L), reminder("m2", "换轮胎", 10L))))
        val remote = garage(ledger(reminders = listOf(reminder("m1", "换机油（全合成）", 30L), reminder("m2", "换轮胎", 10L))))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        assertEquals("换机油（全合成）", merged.garage.cars.single().reminders.first().title)
    }

    @Test
    fun `reminder deleted remotely disappears`() {
        val base = garage(ledger(reminders = listOf(reminder("m1", updatedAt = 10L), reminder("m2", updatedAt = 10L))))
        val remote = garage(ledger(reminders = listOf(reminder("m1", updatedAt = 10L))))

        val merged = SyncMerge.mergeGarage(base, base, remote, now = 1_000L)

        assertEquals(listOf("m1"), merged.garage.cars.single().reminders.map { it.id })
    }

    @Test
    fun `ledger config merges as union and local change wins`() {
        val base = garage(ledger(customCategories = listOf("洗车")))
        val local = garage(ledger(customCategories = listOf("洗车", "装饰"), hiddenCategories = listOf("罚单")))
        val remote = garage(ledger(customCategories = listOf("洗车", "年检"), hiddenCategories = listOf("洗车")))

        val merged = SyncMerge.mergeGarage(base, local, remote, now = 1_000L)

        val car = merged.garage.cars.single()
        assertEquals(listOf("洗车", "装饰", "年检"), car.customCategories)
        assertEquals(listOf("罚单", "洗车"), car.hiddenCategories)
    }

    @Test
    fun `service plan follows the side that changed`() {
        val base = garage(ledger())
        val plan = listOf(com.milenote.android.domain.model.PlanEntry(key = "oil", intervalKm = 5_000.0))
        val remote = garage(ledger().copy(servicePlan = plan, servicePlanBase = PlanBase()))

        val merged = SyncMerge.mergeGarage(base, base, remote, now = 1_000L)

        assertEquals(listOf("oil"), merged.garage.cars.single().servicePlan.orEmpty().map { it.key })
    }

    @Test
    fun `merge is idempotent`() {
        val base = garage(ledger(records = listOf(record("r1", 100.0, 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 20L), record("r2", 5.0, 20L))))
        val remote = garage(ledger(records = listOf(record("r1", 300.0, 30L)), tombstones = listOf(Tombstone("r9", 30L))))

        val once = SyncMerge.mergeGarage(base, local, remote, now = 1_000L).garage
        val twice = SyncMerge.mergeGarage(base, once, remote, now = 1_000L).garage

        assertEquals(BackupCodec.encode(once), BackupCodec.encode(twice))
    }

    @Test
    fun `merged garage still encodes as version 2 backup`() {
        val base = garage(ledger(records = listOf(record("r1", updatedAt = 10L))))
        val local = garage(ledger(records = listOf(record("r1", 150.0, 20L))))

        val merged = SyncMerge.mergeGarage(base, local, local.copy(cars = listOf(ledger(records = emptyList(), tombstones = listOf(Tombstone("r1", 5L))))), now = 1_000L)

        val json = BackupCodec.encode(merged.garage)
        assertTrue(json.contains("\"version\":2"))
        val roundTrip = BackupCodec.decode(json)
        assertEquals(1, roundTrip.cars.size)
    }
}
