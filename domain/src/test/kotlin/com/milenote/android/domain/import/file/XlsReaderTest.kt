package com.milenote.android.domain.import.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsReaderTest {

    private val reader = XlsReader()

    @Test
    fun `reads labels numbers dates booleans and empty cells`() {
        val data = reader.read(XlsFixtures.load("sample.xls"), "小熊油耗.xls")

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
        val bytes = XlsFixtures.load("sample.xls")

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
        val bytes = XlsFixtures.load("rows4.xls")

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            XlsReader(maxDataRows = 2).read(bytes)
        }
        assertTrue(exception.message!!.contains("单次最多导入 2 行"))
    }

    @Test
    fun `falls back to the first sheet when every sheet is hidden`() {
        val data = reader.read(XlsFixtures.load("all-hidden.xls"))

        assertEquals(listOf("甲", "乙"), data.sheetNames)
        assertEquals("甲", data.activeSheet)
        assertEquals(listOf("隐藏甲"), data.headers)
    }

    @Test
    fun `reads shared strings across continue records`() {
        val data = reader.read(XlsFixtures.load("sst-continue.xls"))

        assertEquals(listOf("序号", "备注"), data.headers)
        assertEquals(800, data.rows.size)
        assertEquals(listOf<Any?>(1.0, "中文备注第1条：满箱法油耗与保养提醒的长文本样本"), data.rows[0])
        assertEquals(listOf<Any?>(800.0, "中文备注第800条：满箱法油耗与保养提醒的长文本样本"), data.rows[799])
    }

    @Test
    fun `reads numbers encoded as rk mulrk and number records`() {
        val data = reader.read(XlsFixtures.load("many-numbers.xls"))

        assertEquals(listOf("整数", "小数", "负数"), data.headers)
        assertEquals(300, data.rows.size)
        assertEquals(listOf<Any?>(3.0, 1.25, -1.0), data.rows[0])
        assertEquals(listOf<Any?>(900.0, 300.25, -300.0), data.rows[299])
    }

    @Test
    fun `reads builtin date and time formats`() {
        val data = reader.read(XlsFixtures.load("builtin-date.xls"))

        assertEquals(listOf("日期", "时间"), data.headers)
        assertEquals(2, data.rows.size)
        assertEquals("2023-12-31", data.rows[0][0])
        assertEquals("2001-02-03", data.rows[0][1])
        // jxl 把 1900-01-01 写成序列值 0（历史怪癖），这里固定住解析结果防止回归。
        assertEquals("1899-12-31", data.rows[1][0])
        assertNull(data.rows[1][1])
    }

    @Test
    fun `keeps percentage and currency formats as numbers and skips hidden sheets`() {
        val data = reader.read(XlsFixtures.load("kitchen-sink.xls"))

        assertEquals(listOf("文本", "整数", "小数", "百分比", "金额", "日期", "时间", "公式"), data.headers)
        assertEquals(listOf("数据", "隐藏页", "第三张"), data.sheetNames)
        assertEquals("数据", data.activeSheet)
        val row = data.rows.single()
        assertEquals("中文标签", row[0])
        assertEquals(42.0, row[1])
        assertEquals(3.14, row[2])
        assertEquals(0.25, row[3])
        assertEquals(1234.5, row[4])
        assertEquals("2024-03-15", row[5])
        assertEquals("2024-03-15", row[6])
        // 公式单元格取缓存结果（数值），具体值由写入方决定，这里只校验类型。
        assertTrue(row[7] is Double)
    }
}
