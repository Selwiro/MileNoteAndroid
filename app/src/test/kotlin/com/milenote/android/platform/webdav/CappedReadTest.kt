package com.milenote.android.platform.webdav

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 远端账本下载的大小上限（P0-4）：超限必须在读的过程中中止，不能把超大内容整个读进内存。
 */
class CappedReadTest {

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `reads the whole body when under the limit`() {
        val data = bytes(1000)
        assertArrayEquals(data, readCappedBytes(ByteArrayInputStream(data), max = 2000))
    }

    @Test
    fun `reads the whole body when exactly at the limit`() {
        val data = bytes(512)
        assertEquals(512, readCappedBytes(ByteArrayInputStream(data), max = 512).size)
    }

    @Test
    fun `throws and stops once the limit is exceeded`() {
        val data = bytes(4096)
        val e = assertThrows(WebDavException::class.java) {
            readCappedBytes(ByteArrayInputStream(data), max = 1024)
        }
        // 提示可展示、且不包含账本内容。
        assertEquals(true, e.message?.contains("过大") == true)
    }
}
