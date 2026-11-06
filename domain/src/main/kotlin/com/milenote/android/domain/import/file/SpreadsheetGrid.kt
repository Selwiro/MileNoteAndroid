package com.milenote.android.domain.import.file

import kotlin.math.abs
import kotlin.math.floor

/**
 * 把各格式读取器产出的二维单元格网格规整成 [SpreadsheetData]。
 *
 * 统一约定：
 * - 第一行「有效行」（至少有一个非空单元格）作为表头，单元格转成 trim 后的字符串；
 * - 表头之后的非空行作为数据行，完全空白的行丢弃（因此前导空行会被跳过）；
 * - 数据行按最宽一行补齐，缺失单元格为 `null`；表头不足宽度时补空字符串。
 */
internal object SpreadsheetGrid {

    fun normalize(
        grid: List<List<Any?>>,
        sheetNames: List<String>,
        activeSheet: String,
        fileName: String,
        maxDataRows: Int = SpreadsheetLimits.MAX_DATA_ROWS,
    ): SpreadsheetData {
        val nonEmptyRows = grid.filter { row -> row.any(::isNonBlank) }
        if (nonEmptyRows.isEmpty()) {
            throw SpreadsheetReadException("文件里没有可读取的表格内容")
        }

        val headerCells = nonEmptyRows.first().trimTrailingBlanks()
        val dataRows = nonEmptyRows.drop(1).map { it.trimTrailingBlanks() }
        if (dataRows.size > maxDataRows) {
            throw SpreadsheetLimits.tooManyRows(maxDataRows, dataRows.size)
        }

        val width = maxOf(headerCells.size, dataRows.maxOfOrNull { it.size } ?: 0)
        val headers = List(width) { index -> cellText(headerCells.getOrNull(index)) }
        val rows = dataRows.map { row -> List<Any?>(width) { index -> row.getOrNull(index) } }
        return SpreadsheetData(
            headers = headers,
            rows = rows,
            sheetNames = sheetNames,
            activeSheet = activeSheet,
            fileName = fileName,
        )
    }

    /** 单元格是否有内容（`null`、空白字符串视为空）。 */
    fun isNonBlank(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotBlank()
        is CharSequence -> value.isNotBlank()
        else -> true
    }

    /** 表头/提示文案使用的字符串形式；数字去掉多余的 `.0`。 */
    fun cellText(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        is Double -> doubleText(value)
        is Float -> doubleText(value.toDouble())
        is Number -> value.toString().trim()
        is Boolean -> value.toString()
        else -> value.toString().trim()
    }

    private fun doubleText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        return if (value == floor(value) && abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun List<Any?>.trimTrailingBlanks(): List<Any?> {
        var end = size
        while (end > 0 && !isNonBlank(this[end - 1])) end--
        return if (end == size) this else take(end)
    }
}
