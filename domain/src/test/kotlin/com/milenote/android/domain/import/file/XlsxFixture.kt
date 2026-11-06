package com.milenote.android.domain.import.file

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用 .xlsx 合成器：手工拼最小 OOXML，不依赖 POI。
 *
 * 生成 `xl/workbook.xml` + `xl/_rels/workbook.xml.rels` + `xl/styles.xml`
 * + 可选 `xl/sharedStrings.xml` + 每个工作表一个 `xl/worksheets/sheetN.xml`。
 */
internal object XlsxFixture {

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val XML_DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    class Sheet(
        val name: String,
        val rowsXml: String,
        val hidden: Boolean = false,
    )

    fun build(
        sheets: List<Sheet>,
        sharedStrings: List<String> = emptyList(),
        sharedStringsRaw: List<String>? = null,
        cellXfs: List<Int> = listOf(0),
        numFmts: Map<Int, String> = emptyMap(),
        date1904: Boolean = false,
    ): ByteArray {
        val hasSharedStrings = sharedStrings.isNotEmpty() || sharedStringsRaw != null
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeEntry("xl/workbook.xml", workbookXml(sheets, date1904))
            zip.writeEntry("xl/_rels/workbook.xml.rels", relsXml(sheets, hasSharedStrings))
            zip.writeEntry("xl/styles.xml", stylesXml(cellXfs, numFmts))
            when {
                sharedStringsRaw != null -> zip.writeEntry(
                    "xl/sharedStrings.xml",
                    rawSharedStringsXml(sharedStringsRaw),
                )

                hasSharedStrings -> zip.writeEntry("xl/sharedStrings.xml", sharedStringsXml(sharedStrings))
            }
            sheets.forEachIndexed { index, sheet ->
                zip.writeEntry("xl/worksheets/sheet${index + 1}.xml", worksheetXml(sheet.rowsXml))
            }
        }
        return output.toByteArray()
    }

    /** 生成一个只有指定条目的 zip，用于测试损坏/缺文件场景。 */
    fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) -> zip.writeEntry(name, content) }
        }
        return output.toByteArray()
    }

    // ---- 单元格 ----

    fun row(index: Int, vararg cells: String): String = buildString {
        append("<row r=\"").append(index).append("\">")
        cells.forEach { append(it) }
        append("</row>")
    }

    fun shared(reference: String, index: Int, style: Int? = null): String =
        "<c r=\"$reference\"${styleAttribute(style)} t=\"s\"><v>$index</v></c>"

    fun inline(reference: String, text: String, style: Int? = null): String =
        "<c r=\"$reference\"${styleAttribute(style)} t=\"inlineStr\"><is>" +
            "<t xml:space=\"preserve\">${escape(text)}</t></is></c>"

    /** 内联字符串的原始 `<is>` 内容（用于富文本 `<r><t>` 测试）。 */
    fun inlineRaw(reference: String, innerXml: String, style: Int? = null): String =
        "<c r=\"$reference\"${styleAttribute(style)} t=\"inlineStr\"><is>$innerXml</is></c>"

    fun number(reference: String, value: Double, style: Int? = null): String =
        "<c r=\"$reference\"${styleAttribute(style)}><v>$value</v></c>"

    fun bool(reference: String, value: Boolean): String =
        "<c r=\"$reference\" t=\"b\"><v>${if (value) 1 else 0}</v></c>"

    fun isoDate(reference: String, text: String): String =
        "<c r=\"$reference\" t=\"d\"><v>${escape(text)}</v></c>"

    fun error(reference: String, text: String): String =
        "<c r=\"$reference\" t=\"e\"><v>${escape(text)}</v></c>"

    fun formulaString(reference: String, formula: String, cached: String): String =
        "<c r=\"$reference\" t=\"str\"><f>${escape(formula)}</f><v>${escape(cached)}</v></c>"

    fun formulaNumber(reference: String, formula: String, cached: Double): String =
        "<c r=\"$reference\"><f>${escape(formula)}</f><v>$cached</v></c>"

    fun emptyCell(reference: String): String = "<c r=\"$reference\"/>"

    // ---- XML 生成 ----

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun workbookXml(sheets: List<Sheet>, date1904: Boolean): String = buildString {
        append(XML_DECL)
        append("<workbook xmlns=\"").append(MAIN_NS).append("\" xmlns:r=\"").append(REL_NS).append("\">")
        append("<workbookPr")
        if (date1904) append(" date1904=\"1\"")
        append("/>")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"").append(escape(sheet.name)).append("\" sheetId=\"").append(index + 1).append("\"")
            if (sheet.hidden) append(" state=\"hidden\"")
            append(" r:id=\"rId").append(index + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun relsXml(sheets: List<Sheet>, hasSharedStrings: Boolean): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"").append(PKG_REL_NS).append("\">")
        sheets.forEachIndexed { index, _ ->
            append("<Relationship Id=\"rId").append(index + 1)
                .append("\" Type=\"").append(REL_NS).append("/worksheet")
                .append("\" Target=\"worksheets/sheet").append(index + 1).append(".xml\"/>")
        }
        if (hasSharedStrings) {
            append("<Relationship Id=\"rIdSharedStrings\" Type=\"").append(REL_NS)
                .append("/sharedStrings\" Target=\"sharedStrings.xml\"/>")
        }
        append("<Relationship Id=\"rIdStyles\" Type=\"").append(REL_NS)
            .append("/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun stylesXml(cellXfs: List<Int>, numFmts: Map<Int, String>): String = buildString {
        append(XML_DECL)
        append("<styleSheet xmlns=\"").append(MAIN_NS).append("\">")
        if (numFmts.isNotEmpty()) {
            append("<numFmts count=\"").append(numFmts.size).append("\">")
            numFmts.forEach { (id, code) ->
                append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"")
                    .append(escape(code)).append("\"/>")
            }
            append("</numFmts>")
        }
        append("<cellXfs count=\"").append(cellXfs.size).append("\">")
        cellXfs.forEach { numFmtId ->
            append("<xf numFmtId=\"").append(numFmtId).append("\" xfId=\"0\"/>")
        }
        append("</cellXfs></styleSheet>")
    }

    private fun sharedStringsXml(strings: List<String>): String = buildString {
        append(XML_DECL)
        append("<sst xmlns=\"").append(MAIN_NS).append("\" count=\"").append(strings.size)
            .append("\" uniqueCount=\"").append(strings.size).append("\">")
        strings.forEach { text ->
            append("<si><t xml:space=\"preserve\">").append(escape(text)).append("</t></si>")
        }
        append("</sst>")
    }

    /** 直接拼接 `<si>` 内容，用于模拟富文本/注音等结构。 */
    private fun rawSharedStringsXml(items: List<String>): String = buildString {
        append(XML_DECL)
        append("<sst xmlns=\"").append(MAIN_NS).append("\" count=\"").append(items.size)
            .append("\" uniqueCount=\"").append(items.size).append("\">")
        items.forEach { inner -> append("<si>").append(inner).append("</si>") }
        append("</sst>")
    }

    private fun worksheetXml(rowsXml: String): String = buildString {
        append(XML_DECL)
        append("<worksheet xmlns=\"").append(MAIN_NS).append("\"><sheetData>")
        append(rowsXml)
        append("</sheetData></worksheet>")
    }

    private fun styleAttribute(style: Int?): String =
        if (style == null) "" else " s=\"$style\""

    fun escape(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}
