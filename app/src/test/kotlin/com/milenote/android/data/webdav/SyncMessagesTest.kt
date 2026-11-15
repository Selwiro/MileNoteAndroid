package com.milenote.android.data.webdav

import com.milenote.android.domain.backup.MergeSummary
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 合并结果文案（审计 B27）：无基线降级合并必须在结果里说明，否则用户看不出裁决依据变弱了。
 */
class SyncMessagesTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `merged summary does not mention the baseline when one was used`() {
        val text = SyncMessages.merged(MergeSummary(added = 2, updated = 1, deleted = 1))
        assertTrue(text, text.contains("新增 2"))
        assertFalse(text, text.contains("未使用上次基线"))
    }

    @Test
    fun `degraded merge says the previous baseline was not used`() {
        val text = SyncMessages.merged(MergeSummary(added = 1), noBaseline = true)
        assertTrue(text, text.contains("本次未使用上次基线"))
    }

    @Test
    fun `degraded merge says it in English too`() {
        I18n.applyEnglish(true)
        val text = SyncMessages.merged(MergeSummary(added = 1), noBaseline = true)
        assertTrue(text, text.contains("no previous baseline was used"))
    }
}
