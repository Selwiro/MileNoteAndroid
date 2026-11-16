package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Tombstone
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复备份 vs 车辆删除墓碑（审计回归）：
 *
 * A 删了车 X 并同步，B 也从同步里删掉 X（本地留下车辆墓碑）；之后 B 用旧备份把 X 恢复回来。
 * 恢复是显式修改，不能被墓碑再删一次；标记要能跨设备传播并让墓碑作废。
 */
class RestoredCarSyncTest {

    private fun car(id: String, recordId: String? = null) = blankLedger().copy(
        id = id,
        vehicle = Vehicle(name = "测试车", currentKm = 1000.0),
        records = recordId?.let {
            listOf(Record(id = it, date = "2026-11-01", category = "停车费", amount = 10.0, updatedAt = 10L))
        }.orEmpty(),
    )

    private fun garage(vararg cars: Ledger, tombstones: List<Tombstone>? = null) = Garage(
        version = 2,
        activeId = cars.first().id,
        cars = cars.toList(),
        vehicleTombstones = tombstones,
    )

    @Test
    fun `mark restored cars stamps only newly added cars and clears their tombstone`() {
        val previous = garage(car("blank"), car("other"), tombstones = listOf(Tombstone("car1", 1_000L), Tombstone("gone", 2_000L)))
        // mergeGarage 从当前车库出发，所以墓碑会留在合并结果里（这里如实模拟）。
        val merged = garage(
            car("blank"), car("other"), car("car1", "r1"),
            tombstones = previous.vehicleTombstones,
        )

        val marked = GarageOps.markRestoredCars(merged, previous, at = 5_000L)

        assertEquals(5_000L, marked.cars.first { it.id == "car1" }.restoredAt)
        assertNull("已有车不能被标记", marked.cars.first { it.id == "blank" }.restoredAt)
        assertNull(marked.cars.first { it.id == "other" }.restoredAt)
        assertEquals(
            "只清被恢复车辆的墓碑，其他墓碑保留",
            listOf("gone"),
            marked.vehicleTombstones.orEmpty().map { it.id },
        )
    }

    @Test
    fun `restored car survives a vehicle tombstone in both directions`() {
        val restored = garage(car("blank"), car("car1", "r1").copy(restoredAt = 5_000L))
        val deleted = garage(car("blank"), tombstones = listOf(Tombstone("car1", 1_000L)))

        // B（有车 + restoredAt）合并 A（墓碑）：车保留、墓碑被清掉。
        val onRestoredSide = SyncMerge.mergeGarage(base = null, local = restored, remote = deleted, now = 6_000L)
        assertEquals(setOf("blank", "car1"), onRestoredSide.garage.cars.map { it.id }.toSet())
        assertTrue(onRestoredSide.garage.vehicleTombstones.orEmpty().none { it.id == "car1" })

        // A（墓碑）合并 B（车 + restoredAt）：同样保留，并把自己的墓碑清掉（否则会来回删）。
        val onDeletedSide = SyncMerge.mergeGarage(base = null, local = deleted, remote = restored, now = 6_000L)
        assertEquals(setOf("blank", "car1"), onDeletedSide.garage.cars.map { it.id }.toSet())
        assertTrue(onDeletedSide.garage.vehicleTombstones.orEmpty().none { it.id == "car1" })
    }

    @Test
    fun `restored marker propagates through a later three way merge`() {
        val left = garage(car("blank"), car("car1", "r1").copy(restoredAt = 5_000L))
        val right = garage(car("blank"), car("car1", "r1"))

        val merged = SyncMerge.mergeGarage(base = null, local = right, remote = left, now = 6_000L)

        assertEquals(5_000L, merged.garage.cars.first { it.id == "car1" }.restoredAt)
    }

    @Test
    fun `restored marker is serialized with the backup and survives restore normalization`() {
        val garage = garage(car("blank"), car("car1", "r1").copy(restoredAt = 5_000L))

        val decoded = BackupCodec.decode(BackupCodec.encode(garage))

        assertEquals(5_000L, decoded.cars.first { it.id == "car1" }.restoredAt)
        assertNotNull(decoded.activeCar)
    }
}
