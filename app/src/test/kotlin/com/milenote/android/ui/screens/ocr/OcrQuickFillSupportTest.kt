package com.milenote.android.ui.screens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「手动填写读数」弹窗的纯逻辑：候选数字、输入解析、三元组补齐与一致性校验。 */
class OcrQuickFillSupportTest {

    @Test
    fun `numeric candidates keep order and dedupe`() {
        val text = "金额 228.78 元\n升数 31.47 L\n单价 7.27 元/L\n订单 228.78"
        assertEquals(listOf("228.78", "31.47", "7.27"), numericCandidates(text))
    }

    @Test
    fun `numeric candidates normalize comma decimal and skip absurd values`() {
        val text = "228,78 和 0 和 1234567 和 7.27"
        assertEquals(listOf("228.78", "7.27"), numericCandidates(text))
    }

    @Test
    fun `numeric candidates skip tiny ocr fragments`() {
        val text = "3 上 1 2 228.78 31.47"
        assertEquals(listOf("228.78", "31.47"), numericCandidates(text))
    }

    @Test
    fun `numeric candidates respect limit`() {
        val text = (1..20).joinToString(" ") { "$it" }
        assertEquals(5, numericCandidates(text, limit = 5).size)
    }

    @Test
    fun `parse decimal accepts comma and rejects empty or negative`() {
        assertEquals(228.78, parseDecimal("228,78")!!, 1e-9)
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("-1"))
        assertNull(parseDecimal("abc"))
    }

    @Test
    fun `quick fill with all three values keeps them and passes consistency`() {
        val receipt = buildQuickFillReceipt(null, "228.78", "31.47", "7.27")!!
        assertEquals(228.78, receipt.amount!!, 1e-9)
        assertEquals(31.47, receipt.liters!!, 1e-9)
        assertEquals(7.27, receipt.unitPrice!!, 1e-9)
        assertTrue(receipt.warnings.any { it.contains("手动填写") || it.contains("Manually entered") })
        assertTrue(receipt.warnings.none { it.contains("校验未通过") || it.contains("Check failed") })
    }

    @Test
    fun `amount and liters derive unit price`() {
        val receipt = buildQuickFillReceipt(null, "228.78", "31.47", "")!!
        assertEquals(228.78 / 31.47, receipt.unitPrice!!, 1e-6)
    }

    @Test
    fun `liters and unit price derive amount`() {
        val receipt = buildQuickFillReceipt(null, "", "31.47", "7.27")!!
        assertEquals(31.47 * 7.27, receipt.amount!!, 1e-6)
    }

    @Test
    fun `inconsistent amount adds check warning`() {
        val receipt = buildQuickFillReceipt(null, "300.00", "31.47", "7.27")!!
        assertEquals(300.0, receipt.amount!!, 1e-9)
        assertTrue(receipt.warnings.any { it.contains("校验未通过") || it.contains("Check failed") })
    }

    @Test
    fun `empty values return null and invalid values are ignored`() {
        assertNull(buildQuickFillReceipt(null, "", "", ""))
        assertNull(buildQuickFillReceipt(null, "0", "-5", "1e9"))
    }

    @Test
    fun `quick fill keeps date and odometer from the scan`() {
        val scanned = com.milenote.android.domain.ocr.FuelReceipt(
            date = "2026-11-09",
            odometer = 42318.0,
        )
        val receipt = buildQuickFillReceipt(scanned, "228.78", "31.47", "7.27")!!
        assertEquals("2026-11-09", receipt.date)
        assertEquals(42318.0, receipt.odometer!!, 1e-9)
        assertNotNull(receipt.payable)
    }
}
