package com.milenote.android.domain.import.file

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CsvReaderTest {

    private val reader = CsvReader()

    @Test
    fun `parses utf8 bom comma separated text with crlf`() {
        val text = "\uFEFF日期,金额,备注\r\n2024-01-01,12.5,早餐\r\n2024-01-02,20,\r\n\r\n"
        val data = reader.read(text.toByteArray(Charsets.UTF_8), "账单.csv")

        assertEquals(listOf("日期", "金额", "备注"), data.headers)
        assertEquals(2, data.rows.size)
        assertEquals(listOf<Any?>("2024-01-01", "12.5", "早餐"), data.rows[0])
        // 行尾空单元格在规整后为 null。
        assertEquals(listOf<Any?>("2024-01-02", "20", null), data.rows[1])
        assertEquals(listOf(CsvReader.SHEET_NAME), data.sheetNames)
        assertEquals(CsvReader.SHEET_NAME, data.activeSheet)
    }

    @Test
    fun `keeps quoted commas newlines and escaped quotes`() {
        val text = "a,b\n\"x,y\",\"line1\nline2\"\n\"say \"\"hi\"\"\",2"
        val rows = reader.readRows(text.toByteArray(Charsets.UTF_8))

        assertEquals(3, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("x,y", "line1\nline2"), rows[1])
        assertEquals(listOf("say \"hi\"", "2"), rows[2])
    }

    @Test
    fun `detects semicolon delimiter when more semicolons than commas`() {
        val text = "日期;金额;备注\n2024-01-01;12,5;早餐"
        val data = reader.read(text.toByteArray(Charsets.UTF_8), "导出.csv")

        assertEquals(listOf("日期", "金额", "备注"), data.headers)
        assertEquals(listOf<Any?>("2024-01-01", "12,5", "早餐"), data.rows[0])
    }

    @Test
    fun `prefers tab when the first line contains a tab`() {
        val text = "a\tb,c\n1\t2,3"
        val rows = reader.readRows(text.toByteArray(Charsets.UTF_8))

        assertEquals(listOf("a", "b,c"), rows[0])
        assertEquals(listOf("1", "2,3"), rows[1])
    }

    @Test
    fun `forces tab for tsv extension`() {
        val text = "名称\t金额\n早餐\t12.5"
        val data = reader.read(text.toByteArray(Charsets.UTF_8), "导出.tsv")

        assertEquals(listOf("名称", "金额"), data.headers)
        assertEquals(listOf<Any?>("早餐", "12.5"), data.rows[0])
    }

    @Test
    fun `skips blank lines and leading empty rows`() {
        val text = "\n\r\n日期,金额\n\n2024-01-01,12\n\n"
        val data = reader.read(text.toByteArray(Charsets.UTF_8), "a.csv")

        assertEquals(listOf("日期", "金额"), data.headers)
        assertEquals(1, data.rows.size)
        assertEquals(listOf<Any?>("2024-01-01", "12"), data.rows[0])
    }

    @Test
    fun `reads a single column txt file`() {
        val data = reader.read("日期\n2024-01-01\n2024-01-02".toByteArray(Charsets.UTF_8), "记录.txt")

        assertEquals(listOf("日期"), data.headers)
        assertEquals(2, data.rows.size)
        assertEquals("2024-01-02", data.rows[1][0])
    }

    @Test
    fun `allows a text file with only headers`() {
        val data = reader.read("日期,金额".toByteArray(Charsets.UTF_8), "a.csv")

        assertEquals(listOf("日期", "金额"), data.headers)
        assertTrue(data.rows.isEmpty())
    }

    @Test
    fun `decodes utf16 little endian with bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "日期,金额\r\n2024-01-01,12".toByteArray(Charsets.UTF_16LE)
        val data = reader.read(bytes, "导出.csv")

        assertEquals(listOf("日期", "金额"), data.headers)
        assertEquals(listOf<Any?>("2024-01-01", "12"), data.rows[0])
    }

    @Test
    fun `decodes gbk when utf8 fails`() {
        assumeTrue(Charset.isSupported("GBK"))
        val bytes = "日期,金额\n2024-01-01,12".toByteArray(Charset.forName("GBK"))
        val data = reader.read(bytes, "财务导出.csv")

        assertEquals(listOf("日期", "金额"), data.headers)
        assertEquals("2024-01-01", data.rows[0][0])
    }

    @Test
    fun `rejects blank content`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("   \n\n".toByteArray(Charsets.UTF_8), "空.csv")
        }
        assertTrue(exception.message!!.contains("没有可读取"))
    }
}
