package com.milenote.android.domain.export

import com.milenote.android.domain.evLedger
import com.milenote.android.domain.phevLedger
import com.milenote.android.domain.testLedger
import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 费用工作簿口径：列定义与 CSV 一致（17 / 18 列）、金额/里程写成数字单元格、
 * 插混充电行只在「充电量」列出现、分类汇总的合计等于 `costOf` 合计。
 */
class ExpenseWorkbookTest {

    private fun fuelLedger() = testLedger(
        records = listOf(
            Record(
                id = "r1", date = "2026-11-01", category = "油费", amount = 300.0,
                odometer = 12000.0, liters = 40.0, unitPrice = 7.5, fuelGrade = "92#",
                full = true, note = "加满 40L",
            ),
            Record(id = "r2", date = "2026-10-20", category = "停车费", amount = 25.0, note = "商场"),
        ),
    )

    @Test
    fun `fuel workbook has 17 columns and numeric amount cell`() {
        val sheets = ExpenseWorkbook.sheets(fuelLedger())
        val detail = sheets.first { it.name == "费用明细" }
        assertEquals(17, detail.rows.first().size)
        assertEquals("日期", (detail.rows.first().first() as XlsxCell.Text).value)
        // 表头 + 两条记录
        assertEquals(3, detail.rows.size)
        // 金额列（第 5 列）是数字单元格
        val firstRow = detail.rows[1]
        assertTrue(firstRow[4] is XlsxCell.Number)
        assertEquals(300.0, (firstRow[4] as XlsxCell.Number).value, 1e-9)
        assertEquals("加满 40L", (firstRow[15] as XlsxCell.Text).value)
    }

    @Test
    fun `phev workbook adds a charging column and only charging rows fill it`() {
        val ledger = phevLedger(
            records = listOf(
                Record(
                    id = "fuel", date = "2026-11-01", category = "油费", amount = 300.0,
                    liters = 40.0, unitPrice = 7.5, fuelGrade = "92#", full = true,
                    source = "fuel-row",
                ),
                Record(
                    id = "charge", date = "2026-11-02", category = "油费", amount = 60.0,
                    kwh = 30.0, unitPrice = 2.0, full = true,
                    source = "charge-row",
                ),
            ),
        )
        val detail = ExpenseWorkbook.sheets(ledger).first { it.name == "费用明细" }
        assertEquals(18, detail.rows.first().size)
        assertEquals("充电量", (detail.rows.first()[17] as XlsxCell.Text).value)

        val fuelRow = detail.rows.first { (it[16] as XlsxCell.Text).value == "fuel-row" }
        assertEquals(XlsxCell.Blank, fuelRow[17])
        val chargeRow = detail.rows.first { (it[16] as XlsxCell.Text).value == "charge-row" }
        assertEquals(30.0, (chargeRow[17] as XlsxCell.Number).value, 1e-9)
        // 充电行燃油标号留空
        assertEquals("", (chargeRow[8] as XlsxCell.Text).value)
    }

    @Test
    fun `ev workbook uses charging headers`() {
        val ledger = evLedger(records = listOf(Record(id = "c1", date = "2026-11-01", category = "油费", amount = 60.0, kwh = 30.0)))
        val headers = ExpenseWorkbook.sheets(ledger).first { it.name == "费用明细" }.rows.first()
            .map { (it as XlsxCell.Text).value }
        assertEquals(17, headers.size)
        assertEquals("充电量", headers[10])
        assertEquals("电价", headers[7])
    }

    @Test
    fun `summary sheet totals match costOf and list every category`() {
        val ledger = fuelLedger()
        val summary = ExpenseWorkbook.sheets(ledger).first { it.name == "分类汇总" }
        assertEquals(listOf("分类", "金额", "记录数", "占比"), summary.rows.first().map { (it as XlsxCell.Text).value })

        val totalRow = summary.rows.last()
        assertEquals("合计", (totalRow[0] as XlsxCell.Text).value)
        assertEquals(325.0, (totalRow[1] as XlsxCell.Number).value, 1e-9)
        assertEquals(2.0, (totalRow[2] as XlsxCell.Number).value, 1e-9)
        assertEquals("100.0%", (totalRow[3] as XlsxCell.Text).value)

        val oil = summary.rows.first { (it[0] as XlsxCell.Text).value == "油费" }
        assertEquals(300.0, (oil[1] as XlsxCell.Number).value, 1e-9)
    }

    @Test
    fun `build produces a non-empty xlsx package`() {
        val bytes = ExpenseWorkbook.build(fuelLedger())
        assertTrue(bytes.size > 1000)
        // ZIP 魔数 PK\x03\x04
        assertEquals(0x50, bytes[0].toInt())
        assertEquals(0x4B, bytes[1].toInt())
    }
}
