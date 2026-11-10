package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.GarageOps
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本损坏时的数据安全路径（P0）：
 *
 * - 解析失败且原始文件**能**隔离 → 从空账本继续（可写盘，原数据在隔离副本里）；
 * - 解析失败且**无法**隔离 → 停止落盘（绝不能拿空账本覆盖唯一副本）；
 * - 用户「重新读取」成功后恢复可写；
 * - 整体覆盖（WebDAV 下载 / 还原）前留快照，可按快照回滚。
 *
 * 用内存假存储替换文件系统（真机上无法稳定构造 rename 失败）。
 * 所有内容都是合成数据，不引用真实账本。
 */
class GarageRepositorySafetyTest {

    private class FakeStore(var content: String?) : GarageStore {
        val writes = mutableListOf<String>()
        var quarantineSucceeds = true
        var snapshotOnWrite = false
        val snapshots = mutableListOf<File>()
        val quarantines = mutableListOf<File>()
        private val dir: File = Files.createTempDirectory("mn-garage-test").toFile()
        private val current = File(dir, "garage.json")

        override fun exists(): Boolean = content != null
        override fun read(): String? = content
        override fun write(json: String) {
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
        assertTrue(store.latestQuarantined()!!.readText() == brokenJson)
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

        // 保护状态下的编辑也不落盘。
        repo.update { it.copy(theme = "forest") }
        assertTrue(store.writes.isEmpty())
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
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `replace snapshots the current ledger before overwriting`() = runBlocking {
        val store = FakeStore(emptyLedgerJson).apply { snapshotOnWrite = true }
        val repo = GarageRepository(store)
        repo.load()
        val writesAfterLoad = store.writes.size

        repo.replace(GarageOps.normalizeGarage(null).copy(theme = "forest"))

        assertTrue(store.snapshots.isNotEmpty())
        assertEquals(writesAfterLoad + 1, store.writes.size)
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
}
