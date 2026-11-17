package com.milenote.android.data.garage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本文件写入：临时文件 + fsync + 原子替换（1.0 审查 #1）。
 *
 * 这里只覆盖真文件系统的可见结果；「fsync / rename 失败」用 JVM 复现不了，
 * 对应保护逻辑由 [GarageRepositorySafetyTest] 的假存储用例守（写失败必须报 Failed）。
 */
class GarageFileStoreTest {

    private val dir: File = Files.createTempDirectory("mn-store-test").toFile()
    private val store = GarageFileStore(dir)

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `write then read round trips`() {
        store.write("""{"version":2}""")

        assertEquals("""{"version":2}""", store.read())
        assertTrue(store.exists())
    }

    @Test
    fun `write leaves no temp file behind`() {
        store.write("""{"version":2}""")
        store.write("""{"version":2,"x":1}""")

        val leftovers = dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }
        assertTrue("不应残留临时文件：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `shorter rewrite does not keep the previous tail`() {
        store.write("""{"version":2,"theme":"forest","note":"a-long-value"}""")
        store.write("""{"version":2}""")

        assertEquals("""{"version":2}""", store.read())
    }

    @Test
    fun `read returns null when the ledger does not exist yet`() {
        assertNull(store.read())
        assertFalse(store.exists())
    }
}
