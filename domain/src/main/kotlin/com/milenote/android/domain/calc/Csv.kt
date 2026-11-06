package com.milenote.android.domain.calc

/**
 * 分隔符表格解析（Web `parseDelimited`）。
 *
 * 支持逗号 / 分号 / 制表符、双引号包裹、转义双引号（`""`）、
 * 引号内换行、CRLF 与 UTF-8 BOM；自动丢弃全空行。
 */
fun parseDelimited(text: String): List<List<String>> {
    val source = text.removePrefix("\uFEFF")
    if (source.isEmpty()) return emptyList()

    val firstLine = source.split(Regex("\\r?\\n")).firstOrNull().orEmpty()
    val delimiter = when {
        firstLine.contains('\t') -> '\t'
        firstLine.count { it == ';' } > firstLine.count { it == ',' } -> ';'
        else -> ','
    }

    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '"') {
            if (quoted && i + 1 < source.length && source[i + 1] == '"') {
                cell.append('"')
                i++
            } else {
                quoted = !quoted
            }
        } else if (c == delimiter && !quoted) {
            row.add(cell.toString())
            cell.setLength(0)
        } else if ((c == '\n' || c == '\r') && !quoted) {
            if (c == '\r' && i + 1 < source.length && source[i + 1] == '\n') i++
            row.add(cell.toString())
            if (row.any { it.trim().isNotEmpty() }) rows.add(row)
            row = mutableListOf()
            cell.setLength(0)
        } else {
            cell.append(c)
        }
        i++
    }
    row.add(cell.toString())
    if (row.any { it.trim().isNotEmpty() }) rows.add(row)
    return rows
}
