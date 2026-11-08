package com.milenote.android.platform

import com.milenote.android.domain.calc.parseDelimited
import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.importRows
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 费用 CSV 导出（纯 JVM）：在「金额」后补齐「应付金额 / 优惠金额 / 油价 / 燃油标号」，
 * 并验证导出的表头与行能被导入流程原样回灌。
 */
class LedgerExportsTest {

    private val expectedHeaders = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "油价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源",
    )

    private fun ledger(record: Record): Ledger = Ledger(id = "car-test", records = listOf(record))

    private fun fuelRecord(): Record = Record(
        id = "r-fuel",
        date = "2026-11-01",
        category = "油费",
        amount = 290.0,
        payable = 300.0,
        discount = 10.0,
        unitPrice = 7.5,
        liters = 40.0,
        odometer = 12345.0,
        full = true,
        fuelGrade = "95#",
        source = "手动记录",
    )

    private fun csvText(ledger: Ledger): String =
        String(LedgerExports.csv(ledger), StandardCharsets.UTF_8).removePrefix("\uFEFF")

    @Test
    fun `csv header inserts four fuel columns after amount`() {
        val headers = parseDelimited(csvText(ledger(fuelRecord()))).first()
        assertEquals(expectedHeaders, headers)
    }

    @Test
    fun `csv row exports payable discount unit price and fuel grade`() {
        val row = parseDelimited(csvText(ledger(fuelRecord())))[1]
        assertEquals("2026-11-01", row[0])
        assertEquals("油费", row[3])
        assertEquals("290", row[4])
        assertEquals("300", row[5])
        assertEquals("10", row[6])
        assertEquals("7.5", row[7])
        assertEquals("95#", row[8])
        // 其余列顺序不变：总里程 / 加油量 / 加满 / 来源。
        assertEquals("12345", row[9])
        assertEquals("40", row[10])
        assertEquals("是", row[11])
        assertEquals("手动记录", row[16])
    }

    @Test
    fun `empty fuel fields export as empty cells`() {
        val parking = Record(
            id = "r-park",
            date = "2026-11-02",
            category = "停车费",
            amount = 25.0,
            source = "手动记录",
        )
        val row = parseDelimited(csvText(ledger(parking)))[1]
        assertEquals("25", row[4])
        assertEquals("", row[5])
        assertEquals("", row[6])
        assertEquals("", row[7])
        assertEquals("", row[8])
    }

    @Test
    fun `exported csv imports back keeping the four fuel fields`() {
        val rows = parseDelimited(csvText(ledger(fuelRecord())))
        val item = importRows(rows.drop(1), detectMap(rows.first()), "qianji").single()
        assertEquals("", item.error)
        assertEquals("2026-11-01", item.record.date)
        assertEquals(290.0, item.record.amount, 1e-9)
        assertEquals(300.0, item.record.payable!!, 1e-9)
        assertEquals(10.0, item.record.discount!!, 1e-9)
        assertEquals(7.5, item.record.unitPrice!!, 1e-9)
        assertEquals("95#", item.record.fuelGrade)
        assertEquals(12345.0, item.record.odometer!!, 1e-9)
        assertEquals(40.0, item.record.liters!!, 1e-9)
    }

    @Test
    fun `csv keeps BOM CRLF and quoted cells`() {
        val bytes = LedgerExports.csv(ledger(fuelRecord()))
        assertTrue(bytes.size > 3)
        assertTrue(
            bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
        )
        val text = String(bytes, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF\"日期\""))
        assertTrue(text.contains("\r\n"))
        assertTrue(text.endsWith("\r\n"))
    }
}
