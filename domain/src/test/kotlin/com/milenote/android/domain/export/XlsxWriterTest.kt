package com.milenote.android.domain.export

import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自写 XLSX writer 的结构正确性：OPC 包结构、内联字符串、数字单元格、样式与转义。
 * 不依赖 Excel / POI，只解包检查 OOXML 文本。
 */
class XlsxWriterTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun sheet(name: String): XlsxSheet = XlsxSheet(
        name = name,
        rows = listOf(
            listOf(XlsxCell.Text("日期"), XlsxCell.Text("金额")),
            listOf(XlsxCell.Text("2026-11-01"), XlsxCell.Number(128.5)),
            listOf(XlsxCell.Blank, XlsxCell.Number(40.0)),
        ),
    )

    @Test
    fun `writes a valid OPC package with workbook and sheets`() {
        val files = entries(XlsxWriter.write(listOf(sheet("费用明细"), sheet("分类汇总"))))

        assertTrue(files.containsKey("[Content_Types].xml"))
        assertTrue(files.containsKey("_rels/.rels"))
        assertTrue(files.containsKey("xl/workbook.xml"))
        assertTrue(files.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(files.containsKey("xl/styles.xml"))
        assertTrue(files.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(files.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(files.getValue("xl/workbook.xml").contains("费用明细"))
        assertTrue(files.getValue("xl/workbook.xml").contains("分类汇总"))
    }

    @Test
    fun `writes text as inline strings and numbers as numeric cells`() {
        val sheetXml = entries(XlsxWriter.write(listOf(sheet("明细"))))
            .getValue("xl/worksheets/sheet1.xml")

        assertTrue(sheetXml.contains("""t="inlineStr""""))
        assertTrue(sheetXml.contains("<t xml:space=\"preserve\">2026-11-01</t>"))
        assertTrue(sheetXml.contains("<v>128.5</v>"))
        // 整数值不写成 40.0
        assertTrue(sheetXml.contains("<v>40</v>"))
        // 表头加粗（样式 1）且冻结首行
        assertTrue(sheetXml.contains("""s="1""""))
        assertTrue(sheetXml.contains("""state="frozen""""))
    }

    @Test
    fun `escapes XML sensitive characters and drops control chars`() {
        val files = entries(
            XlsxWriter.write(
                listOf(
                    XlsxSheet(
                        name = "转义",
                        rows = listOf(
                            listOf(XlsxCell.Text("a & b < c > d \"e\"")),
                            listOf(XlsxCell.Text("bad\u0001char")),
                        ),
                    ),
                ),
            ),
        )
        val sheetXml = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheetXml.contains("a &amp; b &lt; c &gt; d \"e\""))
        assertFalse(sheetXml.contains("bad\u0001char"))
        assertTrue(sheetXml.contains("badchar"))
    }

    @Test
    fun `deduplicates sheet names and trims them to 31 chars`() {
        val long = "x".repeat(40)
        val files = entries(XlsxWriter.write(listOf(sheet(long), sheet(long))))
        val workbook = files.getValue("xl/workbook.xml")

        assertTrue(workbook.contains("name=\"${"x".repeat(31)}\""))
        assertTrue(workbook.contains("name=\"${"x".repeat(27)} (2)\""))
    }

    @Test
    fun `rejects an empty sheet list`() {
        val error = runCatching { XlsxWriter.write(emptyList()) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }
}
