package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Tombstone
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车辆级删除的同步传播（B5）：
 *
 * - 删车时在 [Garage.vehicleTombstones] 留墓碑；
 * - 另一台设备合并时，整辆车（含记录）被删掉，而不是被「按 id 取并集」带回来；
 * - 墓碑持有方自己又有了这辆车（还原 / 撤销删除）时，以车为准并清掉墓碑；
 * - 过期墓碑不再生效（与记录墓碑一致，保留 180 天）。
 */
class VehicleTombstoneSyncTest {

    private fun car(id: String, recordId: String? = null): Ledger {
        val base = blankLedger().copy(id = id, vehicle = Vehicle(name = "测试车", currentKm = 1000.0))
        return if (recordId == null) {
            base
        } else {
            base.copy(
                records = listOf(
                    Record(
                        id = recordId,
                        date = "2026-11-01",
                        category = "停车费",
                        amount = 10.0,
                        kind = "expense",
                        updatedAt = 10L,
                    ),
                ),
            )
        }
    }

    private fun garage(vararg cars: Ledger, tombstones: List<Tombstone>? = null) = Garage(
        version = 2,
        activeId = cars.first().id,
        cars = cars.toList(),
        vehicleTombstones = tombstones,
    )

    @Test
    fun `deleting a car stamps a vehicle tombstone`() {
        val before = garage(car("car1", "r1"))
        val after = garage(car("car2"))

        val stamped = SyncStamps.stampChanges(before, after, now = 1_000L)

        assertEquals(listOf("car1"), stamped.vehicleTombstones.orEmpty().map { it.id })
        assertEquals(1_000L, stamped.vehicleTombstones!!.single().at)
    }

    @Test
    fun `re-adding the car removes its vehicle tombstone`() {
        val before = garage(car("car2"), tombstones = listOf(Tombstone("car1", 1_000L)))
        val after = garage(car("car1"), car("car2"))

        val stamped = SyncStamps.stampChanges(before, after, now = 2_000L)

        assertNull(stamped.vehicleTombstones)
    }

    @Test
    fun `merge deletes a remote car when the local side has an active tombstone`() {
        val local = garage(car("car2"), tombstones = listOf(Tombstone("car1", 1_000L)))
        val remote = garage(car("car1", "r1"))

        val merged = SyncMerge.mergeGarage(base = null, local = local, remote = remote, now = 2_000L)

        assertEquals(listOf("car2"), merged.garage.cars.map { it.id })
        assertEquals(listOf("car1"), merged.garage.vehicleTombstones.orEmpty().map { it.id })
    }

    @Test
    fun `merge keeps a car the tombstone owner still has and drops the tombstone`() {
        val local = garage(car("car1", "r1"), tombstones = listOf(Tombstone("car1", 1_000L)))
        val remote = garage(car("car1", "r1"))

        val merged = SyncMerge.mergeGarage(base = null, local = local, remote = remote, now = 2_000L)

        assertEquals(listOf("car1"), merged.garage.cars.map { it.id })
        assertNull(merged.garage.vehicleTombstones)
    }

    @Test
    fun `merge keeps a car edited after the deletion`() {
        val local = garage(car("car2"), tombstones = listOf(Tombstone("car1", 1_000L)))
        // 远端这辆车在删除之后仍有记录被修改（updatedAt=2_000 > 1_000）→ 保留，不丢数据。
        val edited = car("car1", "r1").let { car ->
            car.copy(records = car.records.map { it.copy(updatedAt = 2_000L) })
        }
        val remote = garage(edited)

        val merged = SyncMerge.mergeGarage(base = null, local = local, remote = remote, now = 3_000L)

        assertTrue(merged.garage.cars.any { it.id == "car1" })
        assertNull(merged.garage.vehicleTombstones)
    }

    @Test
    fun `expired vehicle tombstone no longer deletes the car`() {
        val expiredAt = 0L
        val now = SyncMerge.TOMBSTONE_RETENTION_MS + 1
        val local = garage(car("car2"), tombstones = listOf(Tombstone("car1", expiredAt)))
        val remote = garage(car("car1"))

        val merged = SyncMerge.mergeGarage(base = null, local = local, remote = remote, now = now)

        assertTrue("过期墓碑不应再删车", merged.garage.cars.any { it.id == "car1" })
    }

    @Test
    fun `two devices converge after a car is deleted on one side`() {
        val deleted = garage(car("car2"), tombstones = listOf(Tombstone("car1", 1_000L)))
        val alive = garage(car("car1", "r1"))

        // B 先同步：远端（A）删了 car1 → B 也删掉它。
        val bMerged = SyncMerge.mergeGarage(base = garage(car("car1", "r1")), local = alive, remote = deleted, now = 2_000L)
        assertEquals(listOf("car2"), bMerged.garage.cars.map { it.id })

        // A 再同步 B 的结果：仍然没有 car1，墓碑保留。
        val aMerged = SyncMerge.mergeGarage(base = deleted, local = deleted, remote = bMerged.garage, now = 3_000L)
        assertTrue(aMerged.garage.cars.none { it.id == "car1" })
        assertEquals(listOf("car1"), aMerged.garage.vehicleTombstones.orEmpty().map { it.id })
    }
}
