package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两台设备交替改同一个账本的端到端收敛测试（纯 JVM，不需要模拟器 / Android）。
 *
 * 用一个内存「WebDAV 文件」+ 每台设备的（本地账本、上次同步基线、上次同步 hash / ETag）复现
 * [SyncDecision] 的判定与 [SyncMerge] 的合并，验证 T019 的验收场景：
 * 交替改不同记录 → 并集；改同一条 → 后写胜出；删除 → 传播且不复活；全程不出现需人工处理的冲突。
 */
class SyncConvergenceTest {

    /** 内存 WebDAV 文件：内容 + 每次写入递增的 ETag。 */
    private class FakeRemote {
        var garage: Garage? = null
        var etag: Int = 0

        fun put(next: Garage) {
            garage = next
            etag += 1
        }
    }

    /** 一台设备：本地账本 + 上次同步基线 + 上次同步的本地 hash / 远端 ETag。 */
    private class Device(name: String, var local: Garage) {
        val name = name
        var base: Garage? = null
        var lastLocalHash: String? = null
        var lastEtag: Int? = null
    }

    private var clock = 1_000L

    /** 和 app 层 `plainLedgerHash` 同口径（SHA-256 十六进制）；测试里只需要「同内容同 hash」。 */
    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun now(): Long {
        clock += 1_000L
        return clock
    }

    private fun sync(device: Device, remote: FakeRemote): SyncPlan {
        val localJson = BackupCodec.encode(device.local)
        val localHash = sha256(localJson)
        val localChanged = device.lastLocalHash == null || device.lastLocalHash != localHash
        val remoteChanged = device.lastEtag == null || device.lastEtag != remote.etag
        val plan = SyncDecision.decide(
            local = device.local,
            remote = remote.garage,
            base = device.base,
            localChanged = localChanged,
            remoteChanged = remoteChanged,
            now = now(),
        )
        when (plan) {
            SyncPlan.UpToDate -> if (remote.garage != null) device.base = remote.garage
            is SyncPlan.Upload -> {
                remote.put(plan.garage)
                settle(device, remote)
            }
            is SyncPlan.Download -> {
                device.local = plan.garage
                settle(device, remote)
            }
            is SyncPlan.UploadMerged -> {
                device.local = plan.garage
                remote.put(plan.garage)
                settle(device, remote)
            }
            is SyncPlan.Conflict -> throw AssertionError("${device.name} 出现需要人工处理的冲突：${plan.summary.conflicts}")
        }
        return plan
    }

    private fun settle(device: Device, remote: FakeRemote) {
        device.base = device.local
        device.lastLocalHash = sha256(BackupCodec.encode(device.local))
        device.lastEtag = remote.etag
    }

    /** 模拟一次本地修改：内容变化后按仓储口径打点。 */
    private fun edit(device: Device, transform: (Garage) -> Garage) {
        device.local = SyncStamps.stampChanges(device.local, transform(device.local), now())
    }

    private fun record(id: String, amount: Double) = Record(
        id = id,
        date = "2026-11-01",
        category = "油费",
        amount = amount,
        liters = 30.0,
        odometer = 1_000.0,
        full = true,
        kind = "expense",
    )

    private fun seedGarage(): Garage {
        val ledger = Ledger(
            version = 1,
            id = "car1",
            vehicle = Vehicle(name = "测试车", currentKm = 1_000.0),
            records = listOf(record("r1", 100.0), record("r2", 200.0), record("r3", 300.0)),
        )
        return Garage(version = 2, activeId = "car1", cars = listOf(ledger))
    }

    private fun amounts(garage: Garage): Map<String, Double> =
        garage.cars.single().records.associate { it.id to it.amount }

    private fun editAmount(id: String, amount: Double): (Garage) -> Garage = { garage ->
        garage.copy(
            cars = garage.cars.map { car ->
                car.copy(records = car.records.map { if (it.id == id) it.copy(amount = amount) else it })
            },
        )
    }

    private fun deleteRecord(id: String): (Garage) -> Garage = { garage ->
        garage.copy(cars = garage.cars.map { car -> car.copy(records = car.records.filterNot { it.id == id }) })
    }

    @Test
    fun `two devices editing different records converge to the union`() {
        val remote = FakeRemote()
        val a = Device("A", seedGarage())
        val b = Device("B", seedGarage())

        // 初始：A 上传，B 下载。
        sync(a, remote)
        sync(b, remote)
        assertEquals(remote.garage, a.local)
        assertEquals(remote.garage, b.local)

        // A 改 r1；B 改 r2；A 先同步，B 后同步（此时 B 会看到两边都改）。
        edit(a, editAmount("r1", 111.0))
        edit(b, editAmount("r2", 222.0))
        sync(a, remote)
        val bPlan = sync(b, remote)
        assertTrue("B 应该自动合并而不是停手", bPlan is SyncPlan.UploadMerged)

        assertEquals(111.0, amounts(remote.garage!!)["r1"]!!, 0.0)
        assertEquals(222.0, amounts(remote.garage!!)["r2"]!!, 0.0)

        // A 再同步一次拿到 B 的改动。
        val aPlan = sync(a, remote)
        assertTrue(aPlan is SyncPlan.Download)
        assertEquals(BackupCodec.encode(b.local), BackupCodec.encode(a.local))
    }

    @Test
    fun `deletion propagates and does not come back`() {
        val remote = FakeRemote()
        val a = Device("A", seedGarage())
        val b = Device("B", seedGarage())
        sync(a, remote)
        sync(b, remote)

        // A 删除 r3 并同步。
        edit(a, deleteRecord("r3"))
        sync(a, remote)
        assertFalse(amounts(remote.garage!!).containsKey("r3"))

        // B 本地没改过 → 直接下载，r3 一并消失。
        sync(b, remote)
        assertFalse(amounts(b.local).containsKey("r3"))

        // 模拟「旧设备」：同步状态还停在删除之前（etag 更旧），本地仍带着 r3，并且它又改了一条记录
        // 想上传 —— 墓碑必须压住 r3，不能把它复活到远端。
        val stale = Device("Old", seedGarage())
        stale.base = seedGarage()
        stale.lastLocalHash = sha256(BackupCodec.encode(seedGarage()))
        stale.lastEtag = 1
        edit(stale, editAmount("r1", 123.0))
        val stalePlan = sync(stale, remote)
        assertTrue(stalePlan is SyncPlan.UploadMerged)
        assertFalse(amounts(stale.local).containsKey("r3"))
        assertFalse(amounts(remote.garage!!).containsKey("r3"))
    }

    @Test
    fun `same record edited on both devices keeps the later write`() {
        val remote = FakeRemote()
        val a = Device("A", seedGarage())
        val b = Device("B", seedGarage())
        sync(a, remote)
        sync(b, remote)

        edit(a, editAmount("r1", 150.0)) // 较早
        sync(a, remote)
        edit(b, editAmount("r1", 999.0)) // 较晚（时钟推进）
        sync(b, remote)
        assertEquals(999.0, amounts(remote.garage!!)["r1"]!!, 0.0)

        sync(a, remote)
        assertEquals(999.0, amounts(a.local)["r1"]!!, 0.0)
        assertEquals(BackupCodec.encode(b.local), BackupCodec.encode(a.local))
    }

    @Test
    fun `three rounds of alternating edits stay converged`() {
        val remote = FakeRemote()
        val a = Device("A", seedGarage())
        val b = Device("B", seedGarage())
        sync(a, remote)
        sync(b, remote)

        repeat(3) { round ->
            edit(a, editAmount("r1", 100.0 + round * 10))
            edit(b, editAmount("r2", 200.0 + round * 10))
            sync(a, remote)
            sync(b, remote)
        }
        sync(a, remote)

        assertEquals(BackupCodec.encode(a.local), BackupCodec.encode(b.local))
        assertEquals(BackupCodec.encode(remote.garage!!), BackupCodec.encode(a.local))
        assertEquals(3, a.local.cars.single().records.size)
    }

    @Test
    fun `add on both devices converges to the union`() {
        val remote = FakeRemote()
        val a = Device("A", seedGarage())
        val b = Device("B", seedGarage())
        sync(a, remote)
        sync(b, remote)

        val add: (Record) -> (Garage) -> Garage = { record ->
            { garage -> garage.copy(cars = garage.cars.map { it.copy(records = it.records + record) }) }
        }
        edit(a, add(record("r4", 40.0)))
        edit(b, add(record("r5", 50.0)))
        sync(a, remote)
        sync(b, remote)
        sync(a, remote)

        assertEquals(setOf("r1", "r2", "r3", "r4", "r5"), amounts(a.local).keys)
        assertEquals(BackupCodec.encode(a.local), BackupCodec.encode(b.local))
    }
}
