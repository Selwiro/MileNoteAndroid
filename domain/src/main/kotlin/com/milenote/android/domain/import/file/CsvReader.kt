package com.milenote.android.domain.import.file

/**
 * .csv / .tsv / .txt 文本表格读取器。
 *
 * 规则与 Web 端 `parseDelimited` 保持一致：
 * - 自动探测分隔符：首行含 tab 用 tab；否则比较 `;` 与 `,`，分号更多时用分号；
 * - 双引号包裹字段，字段内 `""` 表示一个引号，引号内允许分隔符和换行；
 * - 兼容 CRLF / LF / CR 换行与 UTF-8 BOM；
 * - 完全空白的行会被忽略。
 *
 * 输出的是未经截断的原始字符串行，表头规整由 [SpreadsheetFiles] / [SpreadsheetGrid] 完成。
 */
class CsvReader {

    /**
     * 读取文本表格并规整为 [SpreadsheetData]。
     *
     * @param fileName 仅用于判断 `.tsv` 扩展名与展示，可为空。
     */
    fun read(bytes: ByteArray, fileName: String = ""): SpreadsheetData {
        val rows = readRows(bytes, fileName)
        val grid = rows.map { row -> row.map { it as Any? } }
        return SpreadsheetGrid.normalize(
            grid = grid,
            sheetNames = listOf(SHEET_NAME),
            activeSheet = SHEET_NAME,
            fileName = fileName,
        )
    }

    /**
     * 读取文本表格，返回包含表头在内的所有非空行。
     *
     * @param fileName 提供 `.tsv` 扩展名时强制使用 tab 作为分隔符。
     */
    fun readRows(bytes: ByteArray, fileName: String = ""): List<List<String>> {
        val text = TextDecoder.decode(bytes)
        if (text.isBlank()) return emptyList()
        return parseDelimited(text, resolveDelimiter(text, fileName))
    }

    private fun resolveDelimiter(text: String, fileName: String): Char {
        if (fileName.substringAfterLast('.', "").lowercase() == "tsv") return '\t'
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: text
        if (firstLine.contains('\t')) return '\t'
        val semicolons = firstLine.count { it == ';' }
        val commas = firstLine.count { it == ',' }
        return if (semicolons > commas) ';' else ','
    }

    private fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> {
                    if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                        cell.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                }

                char == delimiter && !quoted -> {
                    row.add(cell.toString())
                    cell.clear()
                }

                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index++
                    }
                    row.add(cell.toString())
                    cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }

                else -> cell.append(char)
            }
            index++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    companion object {
        /** CSV 只有一张「工作表」，用这个固定名字填充 [SpreadsheetData.sheetNames]。 */
        const val SHEET_NAME: String = "CSV"
    }
}
