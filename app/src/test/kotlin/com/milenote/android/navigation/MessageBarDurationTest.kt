package com.milenote.android.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 根级消息条的停留时长按长度分档（用户反馈：「已切换到…」显示太久）。
 */
class MessageBarDurationTest {

    @Test
    fun `short switch message disappears quickly`() {
        val message = "已切换到「我的爱车」"
        assertTrue(message.length <= 18)
        assertEquals(1_600L, messageBarDurationMillis(message))
    }

    @Test
    fun `medium message stays long enough to read`() {
        val message = "已从远端合并备份，冲突项已保留本机版本，请到 WebDAV 同步页核对"
        assertTrue(message.length in 19..48)
        assertEquals(2_600L, messageBarDurationMillis(message))
    }

    @Test
    fun `long restore summary gets the longest window`() {
        val message = "已恢复备份：1 辆车 · 5000 条记录 · 0 条提醒 · 修正 3 条重复编号（已切换到「合成大账本」）"
        assertTrue(message.length > 48)
        assertEquals(3_600L, messageBarDurationMillis(message))
    }
}
