package com.milenote.android.domain.export

/**
 * 最小 XLSX 写入器（零依赖，只用 `java.util.zip`）。
 *
 * 为什么不用 Apache POI：本应用刻意保持运行期依赖精简（见 `work/docs/licensing` 审计），
 * 而导出只需要「文本 + 数字 + 表头加粗 + 冻结首行」，自写 OOXML 足够且可单测。
 *
 * 支持：多工作表、数字单元格、文本单元格（inlineStr，不写 sharedStrings）、
 * 首行加粗、冻结首行、按内容估算列宽。不支持公式、合并单元格、样式扩展。
 */

/** 一个单元格：文本 / 数字 / 空。 */
sealed interface XlsxCell {
    data object Blank : XlsxCell
    data class Text(val value: String) : XlsxCell
    data class Number(val value: Double) : XlsxCell
}

/** 一张工作表；[rows] 第一行按表头处理（加粗 + 冻结）。 */
data class XlsxSheet(val name: String, val rows: List<List<XlsxCell>>)

object XlsxWriter {

    /** 生成完整 `.xlsx` 字节。工作表名超长 / 重名时按 Excel 规则截断去重。 */
    fun write(sheets: List<XlsxSheet>): ByteArray {
        require(sheets.isNotEmpty()) { "至少需要一张工作表" }
        val names = uniqueSheetNames(sheets)
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(path: String, body: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes(sheets.size))
            entry("_rels/.rels", rootRels())
            entry("xl/workbook.xml", workbook(names))
            entry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            entry("xl/styles.xml", STYLES)
            names.forEachIndexed { index, _ ->
                entry("xl/worksheets/sheet${index + 1}.xml", sheetXml(sheets[index]))
            }
        }
        return output.toByteArray()
    }

    private fun uniqueSheetNames(sheets: List<XlsxSheet>): List<String> {
        val used = mutableSetOf<String>()
        return sheets.mapIndexed { index, sheet ->
            var base = sheet.name.ifBlank { "Sheet${index + 1}" }
                .replace(Regex("[\\\\/*?\\[\\]:]"), " ")
                .trim()
                .ifEmpty { "Sheet${index + 1}" }
                .take(31)
            var candidate = base
            var suffix = 2
            while (!used.add(candidate)) {
                val tail = " ($suffix)"
                candidate = base.take((31 - tail.length).coerceAtLeast(1)) + tail
                suffix += 1
            }
            candidate
        }
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    private fun workbook(names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        names.forEachIndexed { index, name ->
            append("""<sheet name="${escapeAttribute(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun sheetXml(sheet: XlsxSheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        columnWidths(sheet.rows).forEachIndexed { index, width ->
            append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        sheet.rows.forEachIndexed { rowIndex, cells ->
            val style = if (rowIndex == 0) """ s="1"""" else ""
            append("""<row r="${rowIndex + 1}">""")
            cells.forEachIndexed { colIndex, cell ->
                val ref = cellRef(colIndex, rowIndex + 1)
                when (cell) {
                    XlsxCell.Blank -> Unit
                    is XlsxCell.Text -> append(
                        """<c r="$ref" t="inlineStr"$style><is><t xml:space="preserve">${escapeText(cell.value)}</t></is></c>""",
                    )
                    is XlsxCell.Number -> append(
                        """<c r="$ref"$style><v>${formatNumber(cell.value)}</v></c>""",
                    )
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 列宽按该列最长文本估算（中文按 2 个字符宽），限制在 8–48。 */
    private fun columnWidths(rows: List<List<XlsxCell>>): List<Double> {
        val columns = rows.maxOfOrNull { it.size } ?: 0
        return (0 until columns).map { column ->
            val longest = rows.maxOfOrNull { row ->
                when (val cell = row.getOrNull(column)) {
                    is XlsxCell.Text -> cell.value.sumOf { if (it.code > 0x2E80) 2 else 1 }
                    is XlsxCell.Number -> formatNumber(cell.value).length
                    else -> 0
                }
            } ?: 0
            (longest + 2).coerceIn(8, 48).toDouble()
        }
    }

    private fun cellRef(columnIndex: Int, rowNumber: Int): String {
        var index = columnIndex
        val letters = StringBuilder()
        while (true) {
            letters.insert(0, ('A' + index % 26))
            index = index / 26 - 1
            if (index < 0) break
        }
        return "$letters$rowNumber"
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        // XML 1.0 不允许的控制字符：直接剔除，避免文件打不开。
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")

    /** 最小样式表：0 = 默认，1 = 加粗（表头）。 */
    private val STYLES: String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
        // 声明默认「Normal」样式：缺这段时 Excel / openpyxl 会警告工作簿没有默认样式。
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""
}
