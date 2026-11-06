package com.milenote.android.domain.import.file

import jxl.write.Label as WriteLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetFilesTest {

    private val reader = SpreadsheetFiles()

    @Test
    fun `dispatches xlsx by file signature even when the extension is csv`() {
        val bytes = xlsxWithOneRow()
        val data = reader.read(bytes, "改名的账单.csv")

        assertEquals(listOf("名称"), data.headers)
        assertEquals("早餐", data.rows[0][0])
        assertEquals("改名的账单.csv", data.fileName)
    }

    @Test
    fun `dispatches xls by file signature even when the extension is xlsx`() {
        val bytes = XlsFixture.build { workbook ->
            val sheet = workbook.createSheet("记录", 0)
            sheet.addCell(WriteLabel(0, 0, "名称"))
            sheet.addCell(WriteLabel(0, 1, "早餐"))
        }
        val data = reader.read(bytes, "账单.xlsx")

        assertEquals(listOf("名称"), data.headers)
        assertEquals("早餐", data.rows[0][0])
        assertEquals("记录", data.activeSheet)
    }

    @Test
    fun `reads csv by extension`() {
        val data = reader.read("日期,金额\n2024-01-01,12".toByteArray(), "账单.csv")

        assertEquals(listOf("日期", "金额"), data.headers)
        assertEquals(listOf<Any?>("2024-01-01", "12"), data.rows[0])
    }

    @Test
    fun `reads tsv by extension`() {
        val data = reader.read("名称\t金额\n早餐\t12".toByteArray(), "导出.tsv")

        assertEquals(listOf("名称", "金额"), data.headers)
        assertEquals(listOf<Any?>("早餐", "12"), data.rows[0])
    }

    @Test
    fun `falls back to csv for text with unknown extension`() {
        val data = reader.read("a,b\n1,2".toByteArray(), "导出.dat")

        assertEquals(listOf("a", "b"), data.headers)
        assertEquals(listOf<Any?>("1", "2"), data.rows[0])
    }

    @Test
    fun `rejects empty file`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(ByteArray(0), "空.xlsx")
        }
        assertTrue(exception.message!!.contains("文件是空的"))
    }

    @Test
    fun `rejects files over ten megabytes`() {
        val bytes = ByteArray((SpreadsheetLimits.MAX_FILE_BYTES + 1).toInt())
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(bytes, "大文件.csv")
        }
        assertTrue(exception.message!!.contains("文件超过 10 MB"))
    }

    @Test
    fun `rejects xlsx extension without zip signature`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("not a real xlsx".toByteArray(), "账单.xlsx")
        }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `rejects xls extension without cdf signature`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("not a real xls".toByteArray(), "账单.xls")
        }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `rejects unsupported binary format`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(byteArrayOf(0, 1, 2, 3, 0, 5), "扫描件.pdf")
        }
        assertTrue(exception.message!!.contains("不支持的文件格式"))
    }

    @Test
    fun `rejects corrupted cdf content dispatched as xls`() {
        val bytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0, 0, 0)
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(bytes, "坏文件.csv")
        }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `exposes sheet names and reads the requested sheet`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "第一张",
                    XlsxFixture.row(1, XlsxFixture.inline("A1", "名称")) +
                        XlsxFixture.row(2, XlsxFixture.inline("A2", "早餐")),
                ),
                XlsxFixture.Sheet(
                    "第二张",
                    XlsxFixture.row(1, XlsxFixture.inline("A1", "项目")) +
                        XlsxFixture.row(2, XlsxFixture.inline("A2", "保养")),
                ),
            ),
        )

        assertEquals(listOf("第一张", "第二张"), reader.sheetNames(bytes, "账单.xlsx"))
        assertEquals(listOf("项目"), reader.readSheet(bytes, "账单.xlsx", "第二张").headers)
    }

    @Test
    fun `dispatches txt json content to the qianji reader`() {
        val json = """[{"key":"qj1","date":"2026-10-21 11:22:22","money":150.0},{"key":"qj2","money":20.0}]"""

        val data = reader.read(json.toByteArray(), "钱迹导出.txt")

        assertEquals(listOf("key", "date", "money"), data.headers)
        assertEquals(2, data.rows.size)
        assertEquals(150.0, data.rows[0][2])
        assertEquals("钱迹", data.activeSheet)
        assertEquals(listOf("钱迹"), reader.sheetNames(json.toByteArray(), "钱迹导出.txt"))
    }

    @Test
    fun `dispatches json extension to the qianji reader`() {
        val data = reader.read("""{"key":"qj1","money":1.0}""".toByteArray(), "备份.json")

        assertEquals(listOf("key", "money"), data.headers)
        assertEquals(1, data.rows.size)
    }

    @Test
    fun `keeps non json txt content on the csv reader`() {
        val data = reader.read("名称,金额\n早餐,12".toByteArray(), "备注.txt")

        assertEquals(listOf("名称", "金额"), data.headers)
        assertEquals(listOf<Any?>("早餐", "12"), data.rows[0])
    }

    @Test
    fun `rejects broken json extension with a readable error`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("not json at all".toByteArray(), "坏.json")
        }
        assertTrue(exception.message!!.contains("JSON"))
    }

    @Test
    fun `rejects json content with unrecognized extension`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("""[{"key":"qj1"}]""".toByteArray(), "账单.dat")
        }
        assertTrue(exception.message!!.contains("JSON"))
    }

    @Test
    fun `works through the SpreadsheetReader interface`() {
        val contract: SpreadsheetReader = SpreadsheetFiles()
        val data = contract.read("名称,金额\n早餐,12".toByteArray(), "账单.csv")

        assertEquals(listOf("名称", "金额"), data.headers)
        assertEquals("早餐", data.rows[0][0])
    }

    private fun xlsxWithOneRow(): ByteArray = XlsxFixture.build(
        sheets = listOf(
            XlsxFixture.Sheet(
                "账单",
                XlsxFixture.row(1, XlsxFixture.inline("A1", "名称")) +
                    XlsxFixture.row(2, XlsxFixture.inline("A2", "早餐")),
            ),
        ),
    )
}
