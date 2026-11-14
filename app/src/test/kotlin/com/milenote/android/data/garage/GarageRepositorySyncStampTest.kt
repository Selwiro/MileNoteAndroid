package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Vehicle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓储层同步打点：`update()` 给真改过的记录写 `updatedAt`、给删除留墓碑，`replace()` 不打点。
 *
 * 这是多设备同步的「写入方」契约：打点错（每次都刷新 / 漏打）会让合并永远冲突或删了又活。
 */
class GarageRepositorySyncStampTest {

    /** 内存存储（文件系统只为快照/隔离接口保留占位）。 */
    private class MemoryStore(var content: String?) : GarageStore {
        private val dir: File = Files.createTempDirectory("mn-stamp-test").toFile()

        override fun exists(): Boolean = content != null
        override fun read(): String? = content
        override fun write(json: String) {
            content = json
        }
        override fun quarantine(): File? = null
        override fun snapshot(): File? = null
        override fun latestSnapshot(): File? = null
        override fun latestQuarantined(): File? = null
        override fun currentFile(): File = File(dir, "garage.json")
    }

    private var now = 1_000L

    private fun repository(store: MemoryStore) = GarageRepository(
        store = store,
        clock = { now },
    )

    private fun record(id: String, amount: Double = 100.0) = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        kind = "expense",
    )

    private fun garage(vararg records: Record): Garage = Garage(
        version = 2,
        activeId = "car1",
        cars = listOf(
            Ledger(version = 1, id = "car1", vehicle = Vehicle(name = "测试车", currentKm = 1_000.0), records = records.toList()),
        ),
    )

    private fun seed(store: MemoryStore, vararg records: Record) {
        store.content = BackupCodec.encode(garage(*records))
    }

    @Test
    fun `added record is stamped on save`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1"))
        val repo = repository(store)
        repo.load()

        repo.update { current ->
            val car = current.cars.single()
            current.copy(cars = listOf(car.copy(records = car.records + record("r2", 50.0))))
        }

        val saved = BackupCodec.decode(store.content!!)
        val added = saved.cars.single().records.first { it.id == "r2" }
        assertEquals(1_000L, added.updatedAt)
        // 没动的记录保持 null（不刷新时间戳）。
        assertNull(saved.cars.single().records.first { it.id == "r1" }.updatedAt)
    }

    @Test
    fun `edited record is stamped on save`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1", 100.0))
        val repo = repository(store)
        repo.load()

        now = 2_000L
        repo.update { current ->
            val car = current.cars.single()
            current.copy(
                cars = listOf(car.copy(records = car.records.map { if (it.id == "r1") it.copy(amount = 120.0) else it })),
            )
        }

        val saved = BackupCodec.decode(store.content!!)
        assertEquals(2_000L, saved.cars.single().records.single().updatedAt)
    }

    @Test
    fun `deleted record leaves a tombstone on save`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1"), record("r2"))
        val repo = repository(store)
        repo.load()

        now = 3_000L
        repo.update { current ->
            val car = current.cars.single()
            current.copy(cars = listOf(car.copy(records = car.records.filterNot { it.id == "r2" })))
        }

        val saved = BackupCodec.decode(store.content!!)
        val tombstones = saved.cars.single().tombstones.orEmpty()
        assertEquals(listOf("r2"), tombstones.map { it.id })
        assertEquals(3_000L, tombstones.single().at)
    }

    @Test
    fun `saving without content change does not restamp`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1"))
        val repo = repository(store)
        repo.load()

        now = 4_000L
        repo.update { current ->
            val car = current.cars.single()
            current.copy(cars = listOf(car.copy(vehicle = car.vehicle.copy(name = "新名字"))))
        }

        val saved = BackupCodec.decode(store.content!!)
        assertNull(saved.cars.single().records.single().updatedAt)
    }

    @Test
    fun `replace does not stamp - remote timestamps are kept as is`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1"))
        val repo = repository(store)
        repo.load()

        now = 5_000L
        val remote = garage(record("r9", 10.0)).let { g ->
            val car = g.cars.single()
            g.copy(
                cars = listOf(
                    car.copy(records = car.records.map { it.copy(updatedAt = 777L) }, tombstones = null),
                ),
            )
        }
        repo.replace(remote)

        val saved = BackupCodec.decode(store.content!!)
        assertEquals(777L, saved.cars.single().records.single().updatedAt)
    }

    @Test
    fun `undo delete removes the tombstone again`() = runBlocking {
        val store = MemoryStore(null)
        seed(store, record("r1"), record("r2"))
        val repo = repository(store)
        repo.load()

        now = 6_000L
        repo.update { current ->
            val car = current.cars.single()
            current.copy(cars = listOf(car.copy(records = car.records.filterNot { it.id == "r1" })))
        }
        assertNotNull(BackupCodec.decode(store.content!!).cars.single().tombstones)

        now = 7_000L
        repo.update { current ->
            val car = current.cars.single()
            current.copy(cars = listOf(car.copy(records = car.records + record("r1", 100.0))))
        }

        val saved = BackupCodec.decode(store.content!!)
        assertTrue(saved.cars.single().tombstones.orEmpty().none { it.id == "r1" })
        assertEquals(7_000L, saved.cars.single().records.first { it.id == "r1" }.updatedAt)
    }
}
