package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.GarageOps
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 账本损坏 / 读写失败时的数据安全路径：
 *
 * - 解析失败且原始文件**能**隔离 → 从空账本继续（可写盘，原数据在隔离副本里）；
 * - 解析失败且**无法**隔离 → 停止落盘（绝不能拿空账本覆盖唯一副本）；
 * - 文件存在但为 0 字节 → 按损坏处理，不能当成「首次启动」静默写空；
 * - 读取抛 IO 异常 → 不崩溃、进入保护状态；
 * - 写盘失败 → 不崩溃、不假成功，内存保留修改并记录 writeError；
 * - 整体覆盖 / 删除前留快照，可按快照回滚；演示模式拒绝破坏性操作。
 *
 * 用内存假存储替换文件系统（真机上无法稳定构造 rename / ENOSPC）。
 * 所有内容都是合成数据，不引用真实账本。
 */
class GarageRepositorySafetyTest {

    private class FakeStore(var content: String?) : GarageStore {
        val writes = mutableListOf<String>()
        var quarantineSucceeds = true
        var snapshotOnWrite = false
        var failWrites = false
        var throwOnRead = false
        val snapshots = mutableListOf<File>()
        val quarantines = mutableListOf<File>()
        private val dir: File = Files.createTempDirectory("mn-garage-test").toFile()
        private val current = File(dir, "garage.json")

        override fun exists(): Boolean = content != null
        override fun read(): String? {
            if (throwOnRead) throw IOException("permission denied")
            return content
        }

        override fun write(json: String) {
            if (failWrites) throw IOException("No space left on device")
            writes += json
            content = json
            current.writeText(json)
        }

        override fun quarantine(): File? {
            if (!quarantineSucceeds) return null
            val broken = File(dir, "garage-broken-${System.nanoTime()}.json")
            content?.let { broken.writeText(it) }
            content = null
            quarantines += broken
            return broken
        }

        override fun snapshot(): File? {
            if (!snapshotOnWrite) return null
            val snap = File(dir, "garage-snapshot-${System.nanoTime()}.json")
            content?.let { snap.writeText(it) } ?: return null
            snapshots += snap
            return snap
        }

        override fun latestSnapshot(): File? = snapshots.lastOrNull()
        override fun latestQuarantined(): File? = quarantines.lastOrNull()
        override fun currentFile(): File = current
    }

    private val emptyLedgerJson: String = BackupCodec.encode(GarageOps.normalizeGarage(null))
    private val brokenJson = """{"version":2,"cars":[{"id":"""

    @Test
    fun `load with broken ledger quarantines and writes fresh ledger`() = runBlocking {
        val store = FakeStore(brokenJson)
        val repo = GarageRepository(store)

        repo.load()

        assertFalse(repo.persistenceBlocked.value)
        assertNotNull(repo.loadError.value)
        assertEquals(1, store.writes.size)
        assertNotNull(store.latestQuarantined())
        assertEquals(brokenJson, store.latestQuarantined()!!.readText())
    }

    @Test
    fun `load blocks persistence when quarantine fails`() = runBlocking {
        val store = FakeStore(brokenJson).apply { quarantineSucceeds = false }
        val repo = GarageRepository(store)

        repo.load()

        assertTrue(repo.persistenceBlocked.value)
        assertNotNull(repo.loadError.value)
        // 没有落盘：坏文件仍是唯一副本，不能被空账本覆盖。
        assertTrue("写入不应发生：${store.writes}", store.writes.isEmpty())
        assertEquals(brokenJson, store.content)

        // 保护状态下的编辑也不落盘，但仍保留在内存（不丢用户刚输入的内容）。
        repo.update { it.copy(theme = "forest") }
        assertTrue(store.writes.isEmpty())
        assertEquals("forest", repo.garage.value!!.theme)
    }

    @Test
    fun `load treats zero-byte file as corrupted instead of first launch`() = runBlocking {
        val store = FakeStore("")
        val repo = GarageRepository(store)

        repo.load()

        assertFalse(repo.persistenceBlocked.value)
        assertNotNull("0 字节文件必须报读取失败，而不是静默当首次启动", repo.loadError.value)
        assertNotNull(store.latestQuarantined())
        assertEquals("", store.latestQuarantined()!!.readText())
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `load blocks persistence when a zero-byte file cannot be quarantined`() = runBlocking {
        val store = FakeStore("").apply { quarantineSucceeds = false }
        val repo = GarageRepository(store)

        repo.load()

        assertTrue(repo.persistenceBlocked.value)
        assertNotNull(repo.loadError.value)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `load survives a read failure and enters protection mode`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { throwOnRead = true }
        val repo = GarageRepository(store)

        val garage = repo.load()

        assertEquals(1, garage.cars.size)
        assertTrue(repo.persistenceBlocked.value)
        assertNotNull(repo.loadError.value)
        assertTrue("读取失败时不应隔离或写盘：${store.writes}", store.writes.isEmpty())
        assertEquals(emptyLedgerJson, store.content)
    }

    @Test
    fun `normal load does not rewrite the ledger`() = runBlocking {
        val store = FakeStore(emptyLedgerJson)
        val repo = GarageRepository(store)

        repo.load()

        assertNull(repo.loadError.value)
        assertFalse(repo.persistenceBlocked.value)
        assertTrue("正常读取不应写盘：${store.writes}", store.writes.isEmpty())
    }

    @Test
    fun `reload recovers after the broken file is repaired`() = runBlocking {
        val store = FakeStore(brokenJson).apply { quarantineSucceeds = false }
        val repo = GarageRepository(store)
        repo.load()
        assertTrue(repo.persistenceBlocked.value)

        // 用户在外部修好（或重新放入）可用账本后点「重新读取」。
        store.content = emptyLedgerJson
        repo.reload()

        assertFalse(repo.persistenceBlocked.value)
        assertNull(repo.loadError.value)
        // 正常重读也不重写；下一次保存才落盘。
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `update write failure keeps the change in memory and reports it`() = runBlocking {
        val store = FakeStore(emptyLedgerJson)
        val repo = GarageRepository(store)
        repo.load()

        store.failWrites = true
        val updated = repo.update { it.copy(theme = "forest") }

        assertEquals("forest", updated!!.theme)
        assertEquals("forest", repo.garage.value!!.theme)
        assertNotNull("写盘失败必须给出可显示的 saveError", repo.writeError.value)
        assertTrue(store.writes.isEmpty())

        // 恢复可写后，下一次保存会把累积的修改落盘并清掉错误。
        store.failWrites = false
        repo.update { it.copy(theme = "purple") }
        assertNull(repo.writeError.value)
        assertEquals(1, store.writes.size)
        assertEquals("purple", BackupCodec.decode(store.content!!).theme)
    }

    @Test
    fun `replace throws instead of pretending to save when the write fails`() = runBlocking {
        val store = FakeStore(emptyLedgerJson)
        val repo = GarageRepository(store)
        repo.load()
        val before = repo.garage.value!!

        store.failWrites = true
        try {
            repo.replace(before.copy(theme = "forest"))
            fail("写入失败时 replace 必须抛 GarageWriteException")
        } catch (_: GarageWriteException) {
            // expected
        }

        assertEquals(before.theme, repo.garage.value!!.theme)
        assertNotNull(repo.writeError.value)
    }

    @Test
    fun `snapshot for undo copies the current ledger without writing`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { snapshotOnWrite = true }
        val repo = GarageRepository(store)
        repo.load()

        assertTrue(repo.snapshotForUndo())
        assertNotNull(store.latestSnapshot())
        assertTrue("只复制快照，不应写账本：${store.writes}", store.writes.isEmpty())
    }

    @Test
    fun `replace snapshots the current ledger before overwriting`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { snapshotOnWrite = true }
        val repo = GarageRepository(store)
        repo.load()

        repo.replace(GarageOps.normalizeGarage(null).copy(theme = "forest"))

        assertTrue(store.snapshots.isNotEmpty())
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `rollback restores the snapshot taken before the last overwrite`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { snapshotOnWrite = true }
        val repo = GarageRepository(store)
        repo.load()
        val originalTheme = repo.garage.value!!.theme

        repo.replace(GarageOps.normalizeGarage(null).copy(theme = "forest"))
        assertEquals("forest", repo.garage.value!!.theme)

        assertTrue(repo.rollbackToLatestSnapshot())
        assertEquals(originalTheme, repo.garage.value!!.theme)
    }

    @Test
    fun `rollback returns false without a snapshot`() = runBlocking {
        val store = FakeStore(emptyLedgerJson)
        val repo = GarageRepository(store)
        repo.load()

        assertFalse(repo.rollbackToLatestSnapshot())
    }

    @Test
    fun `delete data snapshots before writing and stamps a vehicle tombstone`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { snapshotOnWrite = true }
        val repo = GarageRepository(store)
        repo.load()
        val oldCarId = repo.garage.value!!.cars.single().id

        val next = repo.deleteData("all")

        assertTrue("删除前必须留快照", store.snapshots.isNotEmpty())
        assertEquals(1, store.writes.size)
        assertTrue(next.cars.isNotEmpty())
        assertTrue(next.cars.none { it.id == oldCarId })
        assertEquals(listOf(oldCarId), next.vehicleTombstones.orEmpty().map { it.id })
    }

    @Test
    fun `demo mode rejects restore export and delete`() = runBlocking {
        val store = FakeStore(emptyLedgerJson)
        val repo = GarageRepository(store)
        repo.load()
        repo.enterDemo()
        val writesBefore = store.writes.size

        for (action in listOf<suspend () -> Unit>(
            { repo.exportBackup() },
            { repo.restoreBackup(emptyLedgerJson) },
            { repo.deleteData("all") },
        )) {
            try {
                action()
                fail("演示模式必须拒绝该操作")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
        assertFalse(repo.rollbackToLatestSnapshot())
        assertEquals(writesBefore, store.writes.size)
    }
}
