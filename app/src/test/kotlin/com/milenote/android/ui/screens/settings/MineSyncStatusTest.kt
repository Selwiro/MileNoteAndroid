package com.milenote.android.ui.screens.settings

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
    fun `configured without problem is sync on and not a warning`() {
        val status = mineSyncStatus(configured = true, problemKind = null)
        assertEquals("同步已开启", status.text)
        assertFalse(status.warn)
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
        assertEquals("Sync on", mineSyncStatus(true, null).text)
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
}
