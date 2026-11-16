package com.milenote.android.ui.screens.settings

import com.milenote.android.domain.calc.HealthIssue
import com.milenote.android.domain.calc.HealthSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据体检清单的分块（审计回归）：
 *
 * 体检规则是「每条记录最多一条」，一次批量脏数据导入可能产生上万条问题；
 * 界面必须分块渲染并限制总量，否则一次组合上万行会卡死 / ANR。
 */
class HealthIssueChunksTest {

    private fun issues(count: Int): List<HealthIssue> = List(count) { index ->
        HealthIssue(
            code = "DATE_FUTURE",
            severity = HealthSeverity.INFO,
            title = "未来日期",
            detail = "第 $index 条",
            recordId = "r$index",
        )
    }

    @Test
    fun `chunks are bounded by size`() {
        val chunks = healthIssueChunks(issues(HealthIssueShownMax))

        assertTrue(chunks.all { it.size <= HealthIssueChunkSize })
        assertEquals(HealthIssueShownMax, chunks.sumOf { it.size })
    }

    @Test
    fun `total shown is capped`() {
        val total = 1_234
        val chunks = healthIssueChunks(issues(total))

        assertEquals(HealthIssueShownMax, chunks.sumOf { it.size })
        assertEquals(HealthIssueShownMax, healthIssuesShown(total))
        assertEquals(total - HealthIssueShownMax, total - healthIssuesShown(total))
    }

    @Test
    fun `empty list has no chunks`() {
        assertTrue(healthIssueChunks(emptyList()).isEmpty())
        assertEquals(0, healthIssuesShown(0))
    }
}
