package com.milenote.android.ui.screens.ocr

import com.milenote.android.domain.ocr.FuelReceipt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR 结果页的「有没有可回填字段」判定（B18）。 */
class OcrReceiptFieldsTest {

    @Test
    fun `partial readings are still useful`() {
        assertTrue(hasUsefulReceiptField(FuelReceipt(unitPrice = 7.27)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(payable = 228.78)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(discount = 5.0)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(amount = 228.78)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(liters = 31.47)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(odometer = 42_000.0)))
        assertTrue(hasUsefulReceiptField(FuelReceipt(date = "2026-11-15")))
    }

    @Test
    fun `empty receipt is not useful`() {
        assertFalse(hasUsefulReceiptField(null))
        assertFalse(hasUsefulReceiptField(FuelReceipt()))
    }
}
