package com.milenote.android.data.webdav

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同步基线的写入耐久（审计 B27）：
 *
 * 旧写法是「删旧文件 → 改名新文件」，中间没有 fsync；正好在两步之间崩溃/断电就没基线了，
 * 下次同步静默降级为「无基线合并」。现在改成「写临时文件 → fsync → rename 直接覆盖」。
 */
class SyncBaselineStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("milenote-baseline").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun tempFiles(): List<File> = dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

    @Test
    fun `write stores the baseline and leaves no temp file behind`() {
        val store = SyncBaselineStore(dir)
        store.write("""{"version":2}""")

        assertEquals("""{"version":2}""", store.read())
        assertTrue(File(dir, "last-synced.json").isFile)
        assertTrue("不应残留 .tmp：${tempFiles().map { it.name }}", tempFiles().isEmpty())
    }

    /** 覆盖已有基线：rename 直接覆盖，不先 delete（旧文件在改名成功的整个过程中都存在）。 */
    @Test
    fun `overwrites an existing baseline in place`() {
        val store = SyncBaselineStore(dir)
        store.write("""{"version":2,"n":1}""")
        store.write("""{"version":2,"n":2}""")

        assertEquals("""{"version":2,"n":2}""", store.read())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `read returns null when no baseline has been written`() {
        assertNull(SyncBaselineStore(dir).read())
    }

    /** rename 失败（只读目录 / 文件被占用 / 跨卷）时退化为直接覆盖写，基线不丢。 */
    @Test
    fun `falls back to a direct write when the atomic rename fails`() {
        val store = SyncBaselineStore(dir, rename = { _, _ -> false })
        store.write("""{"version":2,"fallback":true}""")

        assertEquals("""{"version":2,"fallback":true}""", File(dir, "last-synced.json").readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test
    fun `clear removes the baseline`() {
        val store = SyncBaselineStore(dir)
        store.write("""{"version":2}""")
        store.clear()

        assertNull(store.read())
        assertFalse(File(dir, "last-synced.json").exists())
    }
}
