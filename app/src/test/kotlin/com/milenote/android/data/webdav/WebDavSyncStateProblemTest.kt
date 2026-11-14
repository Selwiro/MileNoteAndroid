package com.milenote.android.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WebDavSyncState.problem] 的解析口径：把持久化的异常字段还原成 [WebDavProblem]。
 *
 * 类别名无法识别（旧版本 / 数据被改）但仍有说明时按 ERROR 兜底——宁可多提醒一次失败，也不静默藏掉。
 */
class WebDavSyncStateProblemTest {

    @Test
    fun `no problem fields means healthy`() {
        assertNull(WebDavSyncState().problem)
    }

    @Test
    fun `problem restores kind message and timestamp`() {
        val at = 1_762_000_000_000L
        val problem = WebDavSyncState(
            lastProblemKind = WebDavProblemKind.CONFLICT.name,
            lastProblemMessage = "本地与远端都有修改，已暂停自动同步，请手动处理冲突",
            lastProblemAt = at,
        ).problem

        assertEquals(WebDavProblemKind.CONFLICT, problem?.kind)
        assertEquals("本地与远端都有修改，已暂停自动同步，请手动处理冲突", problem?.message)
        assertEquals(at, problem?.at)
    }

    @Test
    fun `needs password is restored as its own kind`() {
        val problem = WebDavSyncState(
            lastProblemKind = WebDavProblemKind.NEEDS_PASSWORD.name,
            lastProblemMessage = "远端数据已加密，请到 设置 → WebDAV 同步 输入加密密码",
            lastProblemAt = 42L,
        ).problem

        assertEquals(WebDavProblemKind.NEEDS_PASSWORD, problem?.kind)
    }

    @Test
    fun `unknown kind falls back to error`() {
        val problem = WebDavSyncState(
            lastProblemKind = "SOMETHING_NEW",
            lastProblemMessage = "同步失败：网络错误",
            lastProblemAt = 7L,
        ).problem

        assertEquals(WebDavProblemKind.ERROR, problem?.kind)
        assertEquals("同步失败：网络错误", problem?.message)
    }

    @Test
    fun `missing kind falls back to error but keeps the message`() {
        val problem = WebDavSyncState(lastProblemMessage = "同步失败").problem
        assertEquals(WebDavProblemKind.ERROR, problem?.kind)
    }

    @Test
    fun `missing message means healthy even if kind is kept`() {
        assertNull(WebDavSyncState(lastProblemKind = WebDavProblemKind.ERROR.name).problem)
    }

    @Test
    fun `missing timestamp is normalized to zero`() {
        val problem = WebDavSyncState(
            lastProblemKind = WebDavProblemKind.ERROR.name,
            lastProblemMessage = "同步失败",
        ).problem

        assertEquals(0L, problem?.at)
    }

    @Test
    fun `successful sync state keeps no problem`() {
        val state = WebDavSyncState(
            lastEtag = "\"abc\"",
            lastLocalHash = "local",
            lastRemoteHash = "remote",
            lastSyncAt = 1_762_000_000_000L,
        )
        assertNull(state.problem)
    }

    // ---------------------------------------------------------------------------------------
    // afterSynced / afterSyncOutcome：仓库写状态时的纯函数口径
    // ---------------------------------------------------------------------------------------

    @Test
    fun `after synced clears the problem and keeps the auto sync history`() {
        val now = 1_762_000_100_000L
        val before = WebDavSyncState(
            lastEtag = "\"old\"",
            lastAutoSyncAt = 1_762_000_000_000L,
            lastAutoSyncResult = "已是最新",
            lastProblemKind = WebDavProblemKind.ERROR.name,
            lastProblemMessage = "自动同步失败：网络错误",
            lastProblemAt = 1_762_000_050_000L,
        )

        val after = before.afterSynced(etag = "\"new\"", localHash = "l", remoteHash = "r", now = now)

        assertNull(after.problem)
        assertEquals("\"new\"", after.lastEtag)
        assertEquals(now, after.lastSyncAt)
        assertEquals(1_762_000_000_000L, after.lastAutoSyncAt)
        assertEquals("已是最新", after.lastAutoSyncResult)
    }

    @Test
    fun `after a failed sync the problem is persisted`() {
        val now = 1_762_000_100_000L
        val problem = WebDavProblem(WebDavProblemKind.CONFLICT, "本地与远端都有修改", now)

        val after = WebDavSyncState().afterSyncOutcome(problem, autoResult = null, now = now)

        assertEquals(WebDavProblemKind.CONFLICT, after.problem?.kind)
        assertEquals("本地与远端都有修改", after.problem?.message)
        assertEquals(now, after.problem?.at)
        // 手动同步（autoResult = null）不改「上次自动同步」。
        assertNull(after.lastAutoSyncAt)
        assertNull(after.lastAutoSyncResult)
    }

    @Test
    fun `after a successful sync the problem is cleared`() {
        val before = WebDavSyncState(
            lastProblemKind = WebDavProblemKind.ERROR.name,
            lastProblemMessage = "自动同步失败：网络错误",
            lastProblemAt = 5L,
            lastAutoSyncAt = 7L,
            lastAutoSyncResult = "自动同步失败：网络错误",
        )

        val after = before.afterSyncOutcome(problem = null, autoResult = null, now = 9L)

        assertNull(after.problem)
        assertEquals(7L, after.lastAutoSyncAt)
        assertEquals("自动同步失败：网络错误", after.lastAutoSyncResult)
    }

    @Test
    fun `auto sync records its time and result`() {
        val now = 1_762_000_100_000L
        val after = WebDavSyncState().afterSyncOutcome(problem = null, autoResult = "已自动上传本地修改", now = now)

        assertEquals(now, after.lastAutoSyncAt)
        assertEquals("已自动上传本地修改", after.lastAutoSyncResult)
        assertNull(after.problem)
    }
}
