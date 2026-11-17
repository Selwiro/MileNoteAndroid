package com.milenote.android.ui.screens.settings

import com.milenote.android.data.webdav.WebDavFailureCategory
import com.milenote.android.data.webdav.WebDavProblemKind
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「我的 → 数据概览」状态行的同步口径（[mineSyncStatus] / [mineStatusLine]）。
 *
 * 口径：未配置 = 未开启；已配置且上次同步正常 = 已开启；失败 / 冲突 / 缺密码 = 对应短标签 + 警示色。
 * 这些标签每次渲染按当前语言取，不沿用异常发生时的语言。
 */
class MineSyncStatusTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `not configured is sync off and not a warning`() {
        val status = mineSyncStatus(configured = false, problemKind = null)
        assertEquals("同步未开启", status.text)
        assertFalse(status.warn)
    }

    @Test
    fun `unconfigured stays off even if a stale problem is around`() {
        val status = mineSyncStatus(configured = false, problemKind = WebDavProblemKind.ERROR)
        assertEquals("同步未开启", status.text)
        assertFalse(status.warn)
    }

    @Test
    fun `configured with a successful sync reports success without a warning`() {
        val status = mineSyncStatus(configured = true, problemKind = null, hasSynced = true)
        assertEquals("最近同步成功", status.text)
        assertFalse(status.warn)
    }

    @Test
    fun `configured but never synced does not claim sync is on`() {
        // 1.0 UX 走查：配好了却一次都没同步过，以前也显示「同步已开启」。
        val status = mineSyncStatus(configured = true, problemKind = null, hasSynced = false)
        assertEquals("已连接 · 尚未同步", status.text)
        assertFalse(status.warn)
    }

    @Test
    fun `a failed connection test is reported as saved but not connected`() {
        val status = mineSyncStatus(configured = true, problemKind = null, connectionVerified = false)
        assertEquals("已保存 · 未连接", status.text)
        assertTrue(status.warn)
    }

    @Test
    fun `sync error turns the line into a warning`() {
        val status = mineSyncStatus(configured = true, problemKind = WebDavProblemKind.ERROR)
        assertEquals("同步失败", status.text)
        assertTrue(status.warn)
    }

    @Test
    fun `sync conflict turns the line into a warning`() {
        val status = mineSyncStatus(configured = true, problemKind = WebDavProblemKind.CONFLICT)
        assertEquals("同步冲突", status.text)
        assertTrue(status.warn)
    }

    @Test
    fun `encrypted remote without password turns the line into a warning`() {
        val status = mineSyncStatus(configured = true, problemKind = WebDavProblemKind.NEEDS_PASSWORD)
        assertEquals("同步需密码", status.text)
        assertTrue(status.warn)
    }

    @Test
    fun `labels follow the current language instead of the language at failure time`() {
        I18n.applyEnglish(true)
        assertEquals("Sync failed", mineSyncStatus(true, WebDavProblemKind.ERROR).text)
        assertEquals("Sync conflict", mineSyncStatus(true, WebDavProblemKind.CONFLICT).text)
        assertEquals("Sync needs password", mineSyncStatus(true, WebDavProblemKind.NEEDS_PASSWORD).text)
        assertEquals("Last sync succeeded", mineSyncStatus(true, null, hasSynced = true).text)
        assertEquals("Sync off", mineSyncStatus(false, null).text)
    }

    @Test
    fun `status line joins backup and sync text with a middle dot`() {
        val line = mineStatusLine(
            backupText = "上次备份 今天",
            backupWarn = false,
            sync = MineSyncStatus(text = "同步失败", warn = true),
        )
        assertEquals("上次备份 今天 · 同步失败", line.text)
        assertTrue(line.warn)
    }

    @Test
    fun `status line warns when only the backup is overdue`() {
        val line = mineStatusLine(
            backupText = "已 31 天未备份",
            backupWarn = true,
            sync = MineSyncStatus(text = "同步已开启", warn = false),
        )
        assertEquals("已 31 天未备份 · 同步已开启", line.text)
        assertTrue(line.warn)
    }

    @Test
    fun `status line stays normal when both backup and sync are fine`() {
        val line = mineStatusLine(
            backupText = "上次备份 昨天",
            backupWarn = false,
            sync = MineSyncStatus(text = "同步已开启", warn = false),
        )
        assertEquals("上次备份 昨天 · 同步已开启", line.text)
        assertFalse(line.warn)
    }

    // ---- UX6：失败短标签按归一类别给方向 ----

    @Test
    fun `sync failure label tells which category to fix`() {
        assertEquals(
            "同步失败·认证",
            mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.AUTH).text,
        )
        assertEquals(
            "同步失败·权限",
            mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.PERMISSION).text,
        )
        assertEquals(
            "同步失败·文件不存在",
            mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.NOT_FOUND).text,
        )
        assertEquals(
            "同步失败·网络",
            mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.NETWORK).text,
        )
        assertEquals(
            "同步失败·加密密码",
            mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.ENCRYPTION).text,
        )
        // 未分类（旧数据 / 其它错误）保持原来的短标签
        assertEquals("同步失败", mineSyncStatus(true, WebDavProblemKind.ERROR, null).text)
        assertEquals("同步失败", mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.OTHER).text)
    }

    @Test
    fun `sync failure labels are translated for english ui`() {
        I18n.applyEnglish(true)
        val status = mineSyncStatus(true, WebDavProblemKind.ERROR, WebDavFailureCategory.AUTH)
        assertEquals("Sync failed: auth", status.text)
        assertTrue(status.warn)
        assertFalse(status.text.any { it.code in 0x4E00..0x9FFF })
    }
}
