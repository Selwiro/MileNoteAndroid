package com.milenote.android.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「远端格式」探测（试用反馈：开了加密但显示未知）。
 *
 * 只看开头 256 字节：信封的 format 字段在最前面，明文备份以 `{` 开头。
 */
class RemoteFormatProbeTest {

    @Test
    fun `encrypted envelope prefix is detected`() {
        val prefix = """{"format":"drive-ledger-encrypted","version":1,"salt":"QUJD","iv":"REVG","data":"R0hJ"""

        assertEquals(true, remoteFormatFromPrefix(prefix))
    }

    @Test
    fun `plain backup prefix is detected`() {
        val prefix = """{"version":2,"activeId":"car-1","theme":"blue","cars":[{"id":"car-1""""

        assertEquals(false, remoteFormatFromPrefix(prefix))
    }

    @Test
    fun `leading whitespace and BOM do not confuse detection`() {
        assertEquals(false, remoteFormatFromPrefix("\uFEFF  {\"version\":2"))
        assertEquals(true, remoteFormatFromPrefix("\n\t{\"format\":\"drive-ledger-encrypted\""))
    }

    @Test
    fun `unknown content returns null`() {
        assertNull(remoteFormatFromPrefix(null))
        assertNull(remoteFormatFromPrefix(""))
        assertNull(remoteFormatFromPrefix("   "))
        assertNull(remoteFormatFromPrefix("<html>not a backup</html>"))
    }
}
