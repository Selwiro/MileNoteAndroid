package com.milenote.android.domain.import.file

import jxl.write.Boolean as WriteBoolean
import jxl.write.DateFormat
import jxl.write.DateTime as WriteDateTime
import jxl.write.Label as WriteLabel
import jxl.write.Number as WriteNumber
import jxl.write.WritableCellFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsReaderTest {

    private val reader = XlsReader()

    @Test
    fun `reads labels numbers dates booleans and empty cells`() {
        val data = reader.read(sampleXls(), "小熊油耗.xls")

        assertEquals(listOf("日期", "金额", "备注", "完成"), data.headers)
        assertEquals(2, data.rows.size)
        assertEquals("2024-01-02", data.rows[0][0])
        assertEquals(123.5, data.rows[0][1])
        assertNull(data.rows[0][2])
        assertEquals(true, data.rows[0][3])
        assertEquals("2024-01-03", data.rows[1][0])
        assertEquals(20.0, data.rows[1][1])
        assertEquals("午餐", data.rows[1][2])
        assertEquals(false, data.rows[1][3])
        assertEquals("记录", data.activeSheet)
        assertEquals(listOf("记录", "第二页"), data.sheetNames)
        assertEquals("小熊油耗.xls", data.fileName)
    }

    @Test
    fun `reads the requested sheet by name`() {
        val bytes = sampleXls()

        assertEquals(listOf("记录", "第二页"), reader.sheetNames(bytes))
        val second = reader.readSheet(bytes, "第二页")
        assertEquals("第二页", second.activeSheet)
        assertEquals(listOf("项目"), second.headers)
        assertEquals("保养", second.rows[0][0])

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.readSheet(bytes, "不存在")
        }
        assertTrue(exception.message!!.contains("找不到工作表"))
    }

    @Test
    fun `rejects non xls bytes`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("日期,金额\n2024-01-01,12".toByteArray())
        }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `rejects corrupted xls content`() {
        val bytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val exception = assertThrows(SpreadsheetReadException::class.java) { reader.read(bytes) }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `enforces the data row limit`() {
        val bytes = XlsFixture.build { workbook ->
            val sheet = workbook.createSheet("记录", 0)
            sheet.addCell(WriteLabel(0, 0, "日期"))
            for (rowIndex in 1..3) {
                sheet.addCell(WriteLabel(0, rowIndex, "2024-01-0$rowIndex"))
            }
        }

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            XlsReader(maxDataRows = 2).read(bytes)
        }
        assertTrue(exception.message!!.contains("单次最多导入 2 行"))
    }

    private fun sampleXls(): ByteArray = XlsFixture.build { workbook ->
        val dateFormat = WritableCellFormat(DateFormat("yyyy-mm-dd"))
        val sheet = workbook.createSheet("记录", 0)
        sheet.addCell(WriteLabel(0, 0, "日期"))
        sheet.addCell(WriteLabel(1, 0, "金额"))
        sheet.addCell(WriteLabel(2, 0, "备注"))
        sheet.addCell(WriteLabel(3, 0, "完成"))
        sheet.addCell(WriteDateTime(0, 1, XlsFixture.date(2024, 1, 2), dateFormat))
        sheet.addCell(WriteNumber(1, 1, 123.5))
        // C2 留空，验证空单元格补 null。
        sheet.addCell(WriteBoolean(3, 1, true))
        sheet.addCell(WriteDateTime(0, 2, XlsFixture.date(2024, 1, 3), dateFormat))
        sheet.addCell(WriteNumber(1, 2, 20.0))
        sheet.addCell(WriteLabel(2, 2, "午餐"))
        sheet.addCell(WriteBoolean(3, 2, false))

        val second = workbook.createSheet("第二页", 1)
        second.addCell(WriteLabel(0, 0, "项目"))
        second.addCell(WriteLabel(0, 1, "保养"))
    }
}
