package com.milenote.android.data.webdav

import com.milenote.android.platform.webdav.webDavUploadCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PUT 条件请求判定（B4）：
 *
 * 远端文件被删除后，绝不能拿上次同步的 ETag 去发 `If-Match`（必然 412，且会永久重试失败）。
 * 只有本次刚 fetch 到的 ETag 才允许 `If-Match`；拿不到就按「远端应不存在」发 `If-None-Match: *`。
 */
class WebDavUploadConditionTest {

    @Test
    fun `uses the freshly fetched etag`() {
        val condition = webDavUploadCondition(force = false, expectedEtag = "\"abc\"")

        assertEquals("\"abc\"", condition.ifMatchEtag)
        assertFalse(condition.ifNoneMatchAny)
    }

    @Test
    fun `missing remote file uses if-none-match instead of a stale etag`() {
        val condition = webDavUploadCondition(force = false, expectedEtag = null)

        assertNull("不能再回退到 lastEtag", condition.ifMatchEtag)
        assertTrue("远端不存在时只允许新建", condition.ifNoneMatchAny)
    }

    @Test
    fun `blank etag is treated as missing`() {
        val condition = webDavUploadCondition(force = false, expectedEtag = "  ")

        assertNull(condition.ifMatchEtag)
        assertTrue(condition.ifNoneMatchAny)
    }

    @Test
    fun `force uploads without preconditions`() {
        val condition = webDavUploadCondition(force = true, expectedEtag = "\"abc\"")

        assertNull(condition.ifMatchEtag)
        assertFalse(condition.ifNoneMatchAny)
    }
}
