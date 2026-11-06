package com.milenote.android.data.webdav

import com.milenote.android.platform.webdav.Etag
import com.milenote.android.platform.webdav.WebDavException
import com.milenote.android.platform.webdav.WebDavUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 同步计划器 + URL/ETag 工具单元测试（纯 JVM，不需要 Android 运行时）。
 *
 * 计划器规则见 [WebDavSyncPlanner.plan] 的 KDoc 表格。
 */
class WebDavSyncPlannerTest {

    // -----------------------------------------------------------------------------------------
    // WebDavSyncPlanner：6 种组合 + 边界
    // -----------------------------------------------------------------------------------------

    @Test
    fun `remote missing and local changed - upload`() {
        assertEquals(Plan.UPLOAD, WebDavSyncPlanner.plan(localChanged = true, remoteChanged = false, remoteExists = false))
    }

    @Test
    fun `remote missing and local unchanged - none`() {
        assertEquals(Plan.NONE, WebDavSyncPlanner.plan(localChanged = false, remoteChanged = false, remoteExists = false))
    }

    @Test
    fun `remote exists and only local changed - upload`() {
        assertEquals(Plan.UPLOAD, WebDavSyncPlanner.plan(localChanged = true, remoteChanged = false, remoteExists = true))
    }

    @Test
    fun `remote exists and only remote changed - download`() {
        assertEquals(Plan.DOWNLOAD, WebDavSyncPlanner.plan(localChanged = false, remoteChanged = true, remoteExists = true))
    }

    @Test
    fun `remote exists and both changed - conflict`() {
        assertEquals(Plan.CONFLICT, WebDavSyncPlanner.plan(localChanged = true, remoteChanged = true, remoteExists = true))
    }

    @Test
    fun `remote exists and neither changed - none`() {
        assertEquals(Plan.NONE, WebDavSyncPlanner.plan(localChanged = false, remoteChanged = false, remoteExists = true))
    }

    @Test
    fun `remote missing ignores remoteChanged flag - upload when local changed`() {
        assertEquals(Plan.UPLOAD, WebDavSyncPlanner.plan(localChanged = true, remoteChanged = true, remoteExists = false))
    }

    @Test
    fun `remote missing ignores remoteChanged flag - none when local unchanged`() {
        assertEquals(Plan.NONE, WebDavSyncPlanner.plan(localChanged = false, remoteChanged = true, remoteExists = false))
    }

    @Test
    fun `repository plan shortcut matches planner`() {
        // 计划器为唯一真源，快捷入口不应有第二套规则。
        assertEquals(
            WebDavSyncPlanner.plan(true, true, true),
            WebDavSyncPlanner.plan(localChanged = true, remoteChanged = true, remoteExists = true),
        )
    }

    // -----------------------------------------------------------------------------------------
    // WebDavUrl：地址校验
    // -----------------------------------------------------------------------------------------

    @Test
    fun `https file url is accepted and trimmed`() {
        val url = "  https://dav.example.com/dav/milenote.json  "
        assertEquals("https://dav.example.com/dav/milenote.json", WebDavUrl.validate(url, allowHttp = false))
    }

    @Test
    fun `chinese file name is allowed`() {
        val url = "https://dav.example.com/备份/里程志备份.json"
        assertEquals(url, WebDavUrl.validate(url, allowHttp = false))
    }

    @Test
    fun `http url rejected when allowHttp disabled`() {
        try {
            WebDavUrl.validate("http://192.168.1.10/dav/milenote.json", allowHttp = false)
            fail("应当抛异常")
        } catch (e: WebDavException) {
            assertEquals("请使用 HTTPS 地址，或在设置中允许 HTTP", e.message)
        }
    }

    @Test
    fun `http url accepted when allowHttp enabled`() {
        val url = "http://192.168.1.10:8080/dav/milenote.json"
        assertEquals(url, WebDavUrl.validate(url, allowHttp = true))
    }

    @Test
    fun `blank url rejected`() {
        try {
            WebDavUrl.validate("   ", allowHttp = true)
            fail("应当抛异常")
        } catch (e: WebDavException) {
            assertEquals("请填写 WebDAV 文件地址", e.message)
        }
    }

    @Test
    fun `non http scheme rejected`() {
        try {
            WebDavUrl.validate("ftp://example.com/a.json", allowHttp = true)
            fail("应当抛异常")
        } catch (e: WebDavException) {
            assertEquals("只支持 http 或 https 地址", e.message)
        }
    }

    @Test
    fun `url ending with slash rejected`() {
        try {
            WebDavUrl.validate("https://dav.example.com/dav/", allowHttp = false)
            fail("应当抛异常")
        } catch (e: WebDavException) {
            assertTrue(e.message.orEmpty().contains("不能以 / 结尾"))
        }
    }

    @Test
    fun `url without path rejected`() {
        try {
            WebDavUrl.validate("https://dav.example.com", allowHttp = false)
            fail("应当抛异常")
        } catch (e: WebDavException) {
            assertTrue(e.message.orEmpty().contains("文件名"))
        }
    }

    @Test
    fun `ancestor directories are ordered shallow to deep`() {
        val dirs = WebDavUrl.ancestorDirectories("https://host:8443/a/b/c.json")
        assertEquals(
            listOf("https://host:8443/a/", "https://host:8443/a/b/"),
            dirs,
        )
    }

    @Test
    fun `ancestor directories empty for root file`() {
        assertTrue(WebDavUrl.ancestorDirectories("https://host/file.json").isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // ETag 规范化
    // -----------------------------------------------------------------------------------------

    @Test
    fun `etag normalization strips weak prefix and quotes`() {
        assertEquals("abc123", Etag.normalize("W/\"abc123\""))
        assertEquals("abc123", Etag.normalize("\"abc123\""))
        assertEquals("abc123", Etag.normalize("abc123"))
        assertEquals("abc123", Etag.normalize("  w/ \"abc123\" "))
        assertNull(Etag.normalize(null))
        assertNull(Etag.normalize("   "))
    }

    @Test
    fun `etag to header quotes value`() {
        assertEquals("\"abc123\"", Etag.toHeader("abc123"))
        assertEquals("\"abc123\"", Etag.toHeader("\"abc123\""))
        assertNull(Etag.toHeader(null))
        assertNull(Etag.toHeader("  "))
    }

    @Test
    fun `etag weak compare ignores case and quotes`() {
        assertTrue(Etag.weakEquals("W/\"ABC\"", "abc"))
        assertTrue(Etag.weakEquals("abc", "abc"))
        assertFalse(Etag.weakEquals("abc", "abd"))
        assertFalse(Etag.weakEquals(null, "abc"))
    }

    // -----------------------------------------------------------------------------------------
    // SHA-256
    // -----------------------------------------------------------------------------------------

    @Test
    fun `sha256 hex matches known vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(ByteArray(0)),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Sha256.hex("hello".toByteArray(Charsets.UTF_8)),
        )
    }
}
