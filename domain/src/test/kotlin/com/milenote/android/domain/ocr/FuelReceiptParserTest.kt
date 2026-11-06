package com.milenote.android.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web fuel-ocr.test：保守解析、实付优先、多候选留空、不推算。 */
class FuelReceiptParserTest {

    @Test
    fun `paid amount wins over totals and unit price`() {
        val receipt = FuelReceiptParser.parse(
            "92号汽油\n¥8.44/L\nx32.71L\n订单金额：¥276\n支付时间 2026-09-16 15:55:16\n" +
                "订单编号 260916155080000000000000\n优惠金额 10元\n优惠券 10.00元\n" +
                "实付金额 266元\n昆仑享卡 266.00元",
        )
        assertEquals(266.0, receipt.amount!!, 1e-9)
        assertEquals(32.71, receipt.liters!!, 1e-9)
        assertEquals("2026-09-16", receipt.date)
        assertNull(receipt.odometer)
    }

    @Test
    fun `full width spaces line labels and thousand separators`() {
        val receipt = FuelReceiptParser.parse(
            "实 付 金 额\n￥２６６．００\n加 油 量：３２．７１升\n支付日期\n2026年9月16日\n总里程：12,345",
        )
        assertEquals(266.0, receipt.amount!!, 1e-9)
        assertEquals(32.71, receipt.liters!!, 1e-9)
        assertEquals("2026-09-16", receipt.date)
        assertEquals(12345.0, receipt.odometer!!, 1e-9)
    }

    @Test
    fun `never infers amount from price discount or card number`() {
        val receipt = FuelReceiptParser.parse("油价 ¥8.44/L\n优惠金额 10元\n订单号 1234567890")
        assertNull(receipt.amount)
        assertNull(receipt.liters)
        assertEquals("", receipt.date)
        assertTrue(receipt.warnings.size >= 3)
    }

    @Test
    fun `conflicts left blank totals fallback and paid date wins`() {
        assertNull(FuelReceiptParser.parse("实付金额 266\n实付金额 276").amount)

        val receipt = FuelReceiptParser.parse("订单金额 276\n创建时间 2026-09-15\n支付时间 2026-09-16")
        assertEquals(276.0, receipt.amount!!, 1e-9)
        assertEquals("2026-09-16", receipt.date)
        assertTrue(receipt.warnings.any { it.contains("优惠") })

        assertEquals("", FuelReceiptParser.parse("2026-02-30").date)
        assertNull(FuelReceiptParser.parse("数量 30L\n加油量 40L").liters)
    }

    @Test
    fun `warnings for missing fields`() {
        val receipt = FuelReceiptParser.parse("欢迎光临")
        assertNull(receipt.amount)
        assertNull(receipt.liters)
        assertEquals("", receipt.date)
        assertTrue(receipt.warnings.any { it.contains("实付金额") })
        assertTrue(receipt.warnings.any { it.contains("加油量") })
        assertTrue(receipt.warnings.any { it.contains("支付日期") })
    }

    @Test
    fun `liters label and fallback scans`() {
        assertEquals(32.71, FuelReceiptParser.parse("加油量 32.71\n支付日期 2026-09-16").liters!!, 1e-9)
        assertEquals(45.0, FuelReceiptParser.parse("燃油 45L\n2026-09-16").liters!!, 1e-9)
        assertEquals(45.0, FuelReceiptParser.parse("加注 45升\n2026-09-16").liters!!, 1e-9)
        assertEquals(32.0, FuelReceiptParser.parse("x32L").liters!!, 1e-9)
    }

    @Test
    fun `inbox receipts paid amount and liters`() {
        val receipt1 = FuelReceiptParser.parse(
            "92号 车用汽油(VIB)\n¥8.44/L\nx 32.71L\n商品编码 300874\n订单金额：¥276\n" +
                "支付时间 2026-09-16 15:55:16\n优惠金额 10元\n实付金额\n266元\n昆仑e享卡 266.00元",
        )
        assertEquals(266.0, receipt1.amount!!, 1e-9)
        assertEquals(32.71, receipt1.liters!!, 1e-9)
        assertEquals(8.44, receipt1.unitPrice!!, 1e-9)
        assertEquals(276.0, receipt1.payable!!, 1e-9)
        assertEquals(10.0, receipt1.discount!!, 1e-9)
        assertEquals("2026-09-16", receipt1.date)

        val receipt2 = FuelReceiptParser.parse(
            "92号车用汽油(VIB)\n中国石化示例加油站\n2026-10-20 10:44:07\n" +
                "油品金额 ¥8.83元/L x 27.98L\n车牌号 京A12345\n应付金额 ¥247.00\n" +
                "实付金额 ¥247\n可开票金额 ¥247.00",
        )
        assertEquals(247.0, receipt2.amount!!, 1e-9)
        assertEquals(27.98, receipt2.liters!!, 1e-9)
        assertEquals(8.83, receipt2.unitPrice!!, 1e-9)
        assertEquals(247.0, receipt2.payable!!, 1e-9)
        assertEquals("2026-10-20", receipt2.date)
    }

    @Test
    fun `mlkit label column output pairs values in order`() {
        val receipt = FuelReceiptParser.parse(
            "油品金額\n车牌号\n应付金額\n实付金额\n可开票金额①\n记录详情\n" +
                "92号车用汽油(VIB)\n2026-10-20 10:44:07\n中国石化示例加油由站\n1l69\n" +
                "¥8.83元/Lx27.98L\n京A12345\n订单编号:TEST0000000000000000000000\n" +
                "¥247.00\n¥247\n¥ 247.00",
        )
        assertEquals(247.0, receipt.amount!!, 1e-9)
        assertEquals(27.98, receipt.liters!!, 1e-9)
        assertEquals("2026-10-20", receipt.date)
    }

    @Test
    fun `paid label must not eat oil unit price line`() {
        val lines = listOf(
            OcrTextLine("油品金額", 0, 100, 90, 130),
            OcrTextLine("实付金额", 0, 160, 90, 190),
            OcrTextLine("¥8.83元/Lx27.98L", 300, 400, 500, 430),
            OcrTextLine("¥247", 370, 460, 470, 490),
        )
        val receipt = FuelReceiptParser.parse(
            "油品金額\n实付金额\n¥8.83元/Lx27.98L\n¥247",
            lines,
        )
        assertEquals(247.0, receipt.amount!!, 1e-9)
        assertEquals(27.98, receipt.liters!!, 1e-9)
    }

    @Test
    fun `liters with spaces and paid label without amount`() {
        assertEquals(32.71, FuelReceiptParser.parse("x 32.71 L\n实付金额 266元").liters!!, 1e-9)
        assertEquals(266.0, FuelReceiptParser.parse("实付金额\n266元").amount!!, 1e-9)
    }

    @Test
    fun `geometry merges same row fragments`() {
        val lines = listOf(
            OcrTextLine("实付金额", 0, 100, 80, 130),
            OcrTextLine("266元", 400, 100, 500, 130),
            OcrTextLine("x 32.71L", 0, 150, 120, 180),
            OcrTextLine("支付时间 2026-09-16", 0, 200, 300, 230),
        )
        val receipt = FuelReceiptParser.parse("实付金额 266元\nx 32.71L\n支付时间 2026-09-16", lines)
        assertEquals(266.0, receipt.amount!!, 1e-9)
        assertEquals(32.71, receipt.liters!!, 1e-9)
        assertEquals("2026-09-16", receipt.date)
    }

    @Test
    fun `geometry paired label column and value column`() {
        val lines = listOf(
            OcrTextLine("油品金額", 0, 100, 90, 130),
            OcrTextLine("应付金額", 0, 160, 90, 190),
            OcrTextLine("实付金额", 0, 220, 90, 250),
            OcrTextLine("可开票金额①", 0, 280, 120, 310),
            OcrTextLine("¥8.83元/Lx27.98L", 300, 500, 500, 530),
            OcrTextLine("¥247.00", 350, 560, 470, 590),
            OcrTextLine("¥247", 370, 620, 470, 650),
            OcrTextLine("¥ 247.00", 350, 680, 470, 710),
        )
        val receipt = FuelReceiptParser.parse(
            "油品金額\n应付金額\n实付金额\n可开票金额①\n¥8.83元/Lx27.98L\n¥247.00\n¥247\n¥ 247.00",
            lines,
        )
        assertEquals(247.0, receipt.amount!!, 1e-9)
        assertEquals(27.98, receipt.liters!!, 1e-9)
    }

    @Test
    fun `pump display amount liters and unit price`() {
        val raw = "提枪加油\n金额\n228.78\n元\n油量\n31.47\n升\n单价（元/升）\n7.27"
        val receipt = FuelReceiptParser.parse(raw)
        assertEquals(228.78, receipt.amount!!, 1e-9)
        assertEquals(31.47, receipt.liters!!, 1e-9)
        assertEquals(7.27, receipt.unitPrice!!, 1e-9)

        val inline = FuelReceiptParser.parse(
            "提枪加油\n金额 228.78 元\n油量 31.47 升\n单价（元/升）\n7.27",
        )
        assertEquals(228.78, inline.amount!!, 1e-9)
        assertEquals(31.47, inline.liters!!, 1e-9)
        assertEquals(7.27, inline.unitPrice!!, 1e-9)
    }

    @Test
    fun `pump display with same row geometry`() {
        val lines = listOf(
            OcrTextLine("提枪加油", 0, 20, 200, 50),
            OcrTextLine("金额", 10, 90, 60, 120),
            OcrTextLine("228.78", 100, 88, 200, 122),
            OcrTextLine("元", 210, 90, 230, 120),
            OcrTextLine("油量", 10, 130, 60, 160),
            OcrTextLine("31.47", 100, 128, 190, 162),
            OcrTextLine("升", 200, 130, 220, 160),
            OcrTextLine("单价（元/升）", 10, 190, 160, 220),
            OcrTextLine("7.27", 80, 230, 150, 262),
        )
        val receipt = FuelReceiptParser.parse("提枪加油\n金额\n228.78\n油量\n31.47\n单价\n7.27", lines)
        assertEquals(228.78, receipt.amount!!, 1e-9)
        assertEquals(31.47, receipt.liters!!, 1e-9)
        assertEquals(7.27, receipt.unitPrice!!, 1e-9)
    }

    @Test
    fun `pump display fallback normalizes seven segment misreads`() {
        val texts = listOf(
            "全新\n提枪加油\n3L47\n单价（元\n元\n升",
            "22878\n3L47\n121-",
            "22878\n3L47",
        )
        val receipt = FuelReceiptParser.parsePumpDisplay(texts)!!
        assertEquals(228.78, receipt.amount!!, 1e-9)
        assertEquals(31.47, receipt.liters!!, 1e-9)
        assertEquals(228.78 / 31.47, receipt.unitPrice!!, 1e-9)
    }

    @Test
    fun `pump fallback requires pump markers`() {
        assertNull(FuelReceiptParser.parsePumpDisplay(listOf("22878\n3L47")))
    }

    @Test
    fun `pump reconcile forces payable equals paid and no discount`() {
        val receipt = FuelReceiptParser.reconcile(
            FuelReceipt(amount = 228.78, liters = 31.47, unitPrice = 7.27),
            pumpDisplay = true,
        )
        assertEquals(228.78, receipt.payable!!, 1e-9)
        assertEquals(0.0, receipt.discount!!, 1e-9)
        assertTrue(receipt.warnings.none { it.contains("校验未通过") })
    }

    @Test
    fun `normal receipt reconcile payable equals paid plus discount`() {
        val receipt = FuelReceiptParser.reconcile(
            FuelReceipt(amount = 266.0, payable = null, discount = 10.0, liters = 32.71, unitPrice = 8.44),
            pumpDisplay = false,
        )
        assertEquals(276.0, receipt.payable!!, 1e-9)
        assertEquals(10.0, receipt.discount!!, 1e-9)
        assertTrue(receipt.warnings.none { it.contains("校验未通过") })
    }

    @Test
    fun `reconcile warns when liters times price mismatches payable`() {
        val receipt = FuelReceiptParser.reconcile(
            FuelReceipt(amount = 200.0, payable = 247.0, discount = 0.0, liters = 27.98, unitPrice = 8.83),
            pumpDisplay = false,
        )
        assertEquals(200.0, receipt.payable!!, 1e-9)
        assertTrue(receipt.warnings.any { it.contains("校验未通过") })
    }

    @Test
    fun `odometer only from explicit labels`() {
        assertEquals(
            23456.0,
            FuelReceiptParser.parse("表显里程 23,456\n支付时间 2026-09-16").odometer!!,
            1e-9,
        )
        assertNull(FuelReceiptParser.parse("公里数 23456\n2026-09-16").odometer)
    }
}
