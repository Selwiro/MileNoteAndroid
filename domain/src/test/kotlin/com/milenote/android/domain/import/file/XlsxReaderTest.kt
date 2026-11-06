package com.milenote.android.domain.import.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxReaderTest {

    private val reader = XlsxReader()

    @Test
    fun `reads shared strings numbers dates booleans and empty cells`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "账单",
                    buildString {
                        append(
                            XlsxFixture.row(
                                1,
                                XlsxFixture.shared("A1", 0),
                                XlsxFixture.shared("B1", 1),
                                XlsxFixture.shared("C1", 2),
                                XlsxFixture.shared("D1", 3),
                            ),
                        )
                        append(
                            XlsxFixture.row(
                                2,
                                XlsxFixture.shared("A2", 4),
                                XlsxFixture.number("B2", 12.5),
                                XlsxFixture.number("C2", 45292.0, style = 1),
                            ),
                        )
                        append(
                            XlsxFixture.row(
                                3,
                                XlsxFixture.inline("A3", "午餐"),
                                XlsxFixture.number("B3", 20.0),
                                XlsxFixture.isoDate("C3", "2024-01-02T00:00:00"),
                                XlsxFixture.bool("D3", true),
                            ),
                        )
                        append(
                            XlsxFixture.row(
                                4,
                                XlsxFixture.formulaString("A4", "B2+B3", "合计"),
                                XlsxFixture.formulaNumber("B4", "SUM(B2:B3)", 32.5),
                                XlsxFixture.error("C4", "#DIV/0!"),
                            ),
                        )
                    },
                ),
            ),
            sharedStrings = listOf("名称", "金额", "日期", "备注", "早餐"),
            cellXfs = listOf(0, 14, 164, 2),
            numFmts = mapOf(164 to "yyyy/mm/dd"),
        )

        val data = reader.read(bytes, "账单.xlsx")

        assertEquals(listOf("名称", "金额", "日期", "备注"), data.headers)
        assertEquals(3, data.rows.size)
        // D2 是空单元格，按列索引补齐为 null。
        assertEquals(listOf<Any?>("早餐", 12.5, "2024-01-01", null), data.rows[0])
        assertEquals("午餐", data.rows[1][0])
        assertEquals(20.0, data.rows[1][1])
        assertEquals("2024-01-02", data.rows[1][2])
        assertEquals(true, data.rows[1][3])
        assertEquals("合计", data.rows[2][0])
        assertEquals(32.5, data.rows[2][1])
        assertEquals("#DIV/0!", data.rows[2][2])
        assertEquals("账单", data.activeSheet)
        assertEquals(listOf("账单"), data.sheetNames)
        assertEquals("账单.xlsx", data.fileName)
    }

    @Test
    fun `joins rich text runs and ignores phonetic hints`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "表",
                    XlsxFixture.row(
                        1,
                        XlsxFixture.shared("A1", 0),
                        XlsxFixture.shared("B1", 1),
                        XlsxFixture.shared("C1", 2),
                        XlsxFixture.inlineRaw("D1", "<r><t>内</t></r><r><t>联</t></r>"),
                    ) + XlsxFixture.row(2, XlsxFixture.shared("A2", 3)),
                ),
            ),
            sharedStringsRaw = listOf(
                "<t>名称</t>",
                "<r><rPr><b/></rPr><t>合</t></r><r><t>计</t></r>",
                "<r><t>单价</t></r><rPh sb=\"0\" eb=\"2\"><t>タンカ</t></rPh>",
                "<r><t>早</t></r><r><t>餐</t></r>",
            ),
        )

        val data = reader.read(bytes)

        assertEquals(listOf("名称", "合计", "单价", "内联"), data.headers)
        assertEquals("早餐", data.rows[0][0])
    }

    @Test
    fun `skips leading empty rows and pads gaps by column index`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "表",
                    buildString {
                        append(XlsxFixture.row(1))
                        append(XlsxFixture.row(2, XlsxFixture.inline("A2", "项目")))
                        append(XlsxFixture.row(3, XlsxFixture.inline("B3", "保养")))
                    },
                ),
            ),
        )

        val data = reader.read(bytes)

        assertEquals(listOf("项目", ""), data.headers)
        assertEquals(1, data.rows.size)
        assertEquals(listOf<Any?>(null, "保养"), data.rows[0])
    }

    @Test
    fun `reads all sheet names and the requested sheet`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "第一张",
                    XlsxFixture.row(1, XlsxFixture.inline("A1", "名称")) +
                        XlsxFixture.row(2, XlsxFixture.inline("A2", "早餐")),
                ),
                XlsxFixture.Sheet("隐藏表", XlsxFixture.row(1, XlsxFixture.inline("A1", "内部")), hidden = true),
                XlsxFixture.Sheet(
                    "第二张",
                    XlsxFixture.row(1, XlsxFixture.inline("A1", "项目")) +
                        XlsxFixture.row(2, XlsxFixture.inline("A2", "保养")),
                ),
            ),
        )

        assertEquals(listOf("第一张", "隐藏表", "第二张"), reader.sheetNames(bytes))
        val first = reader.read(bytes)
        assertEquals("第一张", first.activeSheet)
        assertEquals(listOf("名称"), first.headers)

        val second = reader.readSheet(bytes, "第二张")
        assertEquals(listOf("项目"), second.headers)
        assertEquals("保养", second.rows[0][0])

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.readSheet(bytes, "不存在")
        }
        assertTrue(exception.message!!.contains("找不到工作表"))
    }

    @Test
    fun `reads first visible sheet when the leading sheet is hidden`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet("隐藏表", XlsxFixture.row(1, XlsxFixture.inline("A1", "内部")), hidden = true),
                XlsxFixture.Sheet("可见表", XlsxFixture.row(1, XlsxFixture.inline("A1", "名称"))),
            ),
        )

        assertEquals("可见表", reader.read(bytes).activeSheet)
    }

    @Test
    fun `converts date styles but keeps plain numbers`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "表",
                    XlsxFixture.row(
                        1,
                        XlsxFixture.inline("A1", "自定义"),
                        XlsxFixture.inline("B1", "普通"),
                    ) +
                        XlsxFixture.row(
                            2,
                            XlsxFixture.number("A2", 45292.0, style = 1),
                            XlsxFixture.number("B2", 3.0, style = 2),
                        ),
                ),
            ),
            cellXfs = listOf(0, 164, 2),
            numFmts = mapOf(164 to "yyyy/m/d"),
        )

        val data = reader.read(bytes)

        assertEquals("2024-01-01", data.rows[0][0])
        assertEquals(3.0, data.rows[0][1])
    }

    @Test
    fun `supports the 1904 date system`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet(
                    "表",
                    XlsxFixture.row(1, XlsxFixture.inline("A1", "日期")) +
                        XlsxFixture.row(2, XlsxFixture.number("A2", 0.0, style = 1)),
                ),
            ),
            cellXfs = listOf(0, 14),
            date1904 = true,
        )

        assertEquals("1904-01-01", reader.read(bytes).rows[0][0])
    }

    @Test
    fun `allows a workbook with only headers`() {
        val bytes = XlsxFixture.build(
            sheets = listOf(
                XlsxFixture.Sheet("表", XlsxFixture.row(1, XlsxFixture.inline("A1", "名称"))),
            ),
        )

        val data = reader.read(bytes)

        assertEquals(listOf("名称"), data.headers)
        assertTrue(data.rows.isEmpty())
    }

    @Test
    fun `rejects workbooks over the data row limit`() {
        val rowsXml = buildString {
            append(XlsxFixture.row(1, XlsxFixture.inline("A1", "名称")))
            for (rowIndex in 2..5) {
                append(XlsxFixture.row(rowIndex, XlsxFixture.inline("A$rowIndex", "值$rowIndex")))
            }
        }
        val bytes = XlsxFixture.build(sheets = listOf(XlsxFixture.Sheet("表", rowsXml)))

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            XlsxReader(maxDataRows = 2).read(bytes)
        }
        assertTrue(exception.message!!.contains("单次最多导入 2 行"))
    }

    @Test
    fun `enforces the default twenty thousand row limit`() {
        val rowsXml = buildString {
            append(XlsxFixture.row(1, XlsxFixture.inline("A1", "名称")))
            for (rowIndex in 2..20_002) {
                append(XlsxFixture.row(rowIndex, XlsxFixture.inline("A$rowIndex", "值")))
            }
        }
        val bytes = XlsxFixture.build(sheets = listOf(XlsxFixture.Sheet("表", rowsXml)))

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            XlsxReader().read(bytes)
        }
        assertTrue(exception.message!!.contains("20,000"))
    }

    @Test
    fun `rejects bytes that are not a zip`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `rejects corrupted zip content`() {
        val bytes = byteArrayOf(
            'P'.code.toByte(),
            'K'.code.toByte(),
            3,
            4,
            0,
            0,
            0,
            0,
        )
        val exception = assertThrows(SpreadsheetReadException::class.java) { reader.read(bytes) }
        assertTrue(exception.message!!.contains("不是有效的 Excel 表格"))
    }

    @Test
    fun `rejects a workbook without workbook xml`() {
        val bytes = XlsxFixture.zipOf(
            "xl/styles.xml" to """<?xml version="1.0"?><styleSheet/>""",
        )
        val exception = assertThrows(SpreadsheetReadException::class.java) { reader.read(bytes) }
        assertTrue(exception.message!!.contains("缺少 workbook.xml"))
    }

    @Test
    fun `rejects a workbook without sheets`() {
        val bytes = XlsxFixture.zipOf(
            "xl/workbook.xml" to
                """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets/></workbook>""",
        )
        val exception = assertThrows(SpreadsheetReadException::class.java) { reader.read(bytes) }
        assertTrue(exception.message!!.contains("没有工作表"))
    }
}
