package com.milenote.android.domain.i18n

import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.defaultServiceReminders
import com.milenote.android.domain.calc.resolveServicePlan
import com.milenote.android.domain.export.XlsxWriter
import com.milenote.android.domain.import.file.SpreadsheetLimits
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.blankLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * domain 层用户可见校验文案的双语覆盖（B30）。
 *
 * 这些文案会经 `e.message` 原样弹到界面（导入向导、保养方案保存、导出），
 * 以前是硬编码中文，英文界面会弹中文。这里在 [DomainI18n] 英文模式下逐条断言「没有中文」，
 * 并用中文模式断言文案未走样。
 */
class DomainValidationMessagesTest {

    @Before
    fun setUp() = DomainI18n.setEnglish(true)

    @After
    fun tearDown() = DomainI18n.setEnglish(false)

    private fun hasChinese(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }

    private fun illegalMessage(block: () -> Unit): String =
        assertThrows(IllegalArgumentException::class.java) { block() }.message.orEmpty()

    @Test
    fun `service plan item validation is english`() {
        // 名称不能为空 / 重复
        assertTrue(
            !hasChinese(
                illegalMessage { resolveServicePlan(listOf(PlanEntry(key = "x", title = " ")), null) },
            ),
        )
        // 公里数 / 时长非法
        assertTrue(
            !hasChinese(
                illegalMessage {
                    resolveServicePlan(
                        listOf(PlanEntry(key = "custom-1", title = "Custom item", intervalKm = 0.0)),
                        null,
                    )
                },
            ),
        )
        // 自定义项两项都空
        assertTrue(
            !hasChinese(
                illegalMessage { resolveServicePlan(listOf(PlanEntry(key = "custom-2", title = "Only a name")), null) },
            ),
        )
    }

    @Test
    fun `service plan base validation is english`() {
        val ledger = blankLedger()
        assertTrue(
            !hasChinese(
                illegalMessage { applyServicePlan(ledger, null, baseDate = "", baseKm = 0.0, includeFirst = true) },
            ),
        )
    }

    @Test
    fun `default reminders base validation is english`() {
        assertTrue(!hasChinese(illegalMessage { defaultServiceReminders("", null) }))
    }

    @Test
    fun `xlsx writer requirement is english`() {
        assertTrue(!hasChinese(illegalMessage { XlsxWriter.write(emptyList()) }))
    }

    @Test
    fun `import row limit message is english and states the limit`() {
        val message = assertThrows(Exception::class.java) {
            throw SpreadsheetLimits.tooManyRows(SpreadsheetLimits.MAX_DATA_ROWS, actual = 21_000)
        }.message.orEmpty()
        assertEquals(false, hasChinese(message))
        assertTrue(message.contains("20,000"))
        assertTrue(message.contains("21,000"))
    }

    @Test
    fun `chinese mode keeps the original wording`() {
        DomainI18n.setEnglish(false)
        val message = illegalMessage { defaultServiceReminders("", null) }
        assertTrue(message, hasChinese(message))
        assertEquals("请填写有效基准日期和里程", message)
    }
}
