package com.milenote.android.domain.import.file

import com.milenote.android.domain.i18n.tr

/**
 * 表格导入的硬性上限。
 *
 * 上限同时用于保护手机内存和避免误导入超大文件；
 * 界面层可在选择文件前先用这些常量做提示。
 */
object SpreadsheetLimits {
    /** 单个文件最大字节数：10 MB。 */
    const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024

    /** 单个文件最多允许的数据行数（不含表头）：20,000 行。 */
    const val MAX_DATA_ROWS: Int = 20_000

    /** 单行最多允许的列数（1.0 审查 #16：远端列号 / 稀疏空列会直接决定缓冲大小）。 */
    const val MAX_COLUMNS: Int = 512

    /** 整张表的单元格总数上限（行 × 列），防止稀疏大表把每行都补成超宽。 */
    const val MAX_CELLS: Int = 1_000_000

    /** xlsx 解压后所有工作表 XML 的总字节上限：64 MB。 */
    const val MAX_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    /** xlsx 解压后单个 XML 条目上限：32 MB。 */
    const val MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024

    /** 把行数格式化成带千分位的文案，例如 `20000` -> `20,000`。 */
    internal fun formatCount(count: Int): String =
        count.toString().reversed().chunked(3).joinToString(",").reversed()

    /** 列数超限：明确说「这一行有太多列」，而不是让用户看到内存错误。 */
    internal fun tooManyColumns(limit: Int, atRow: Int? = null): SpreadsheetReadException =
        SpreadsheetReadException(
            tr(
                "表格列数超出支持范围（最多 %s 列%s），请删除多余的空列后再导入",
                "The sheet has too many columns (limit %s%s) — delete the extra empty columns and try again",
                formatCount(limit),
                atRow?.let { tr("，第 %s 行开始", ", starting at row %s", formatCount(it)) } ?: "",
            ),
        )

    /** 单元格总数超限：稀疏大表不该被补齐到最大宽度。 */
    internal fun tooManyCells(limit: Int, columns: Int, rows: Int): SpreadsheetReadException =
        SpreadsheetReadException(
            tr(
                "表格过大（%s 列 × %s 行，最多 %s 个单元格），请拆分文件后再导入",
                "The sheet is too large (%s columns × %s rows, limit %s cells) — split the file and import again",
                formatCount(columns),
                formatCount(rows),
                formatCount(limit),
            ),
        )

    /**
     * 构造统一的「超过行数上限」异常（B30：中英双语）。
     *
     * @param actual 解析完成时已知的总行数；边解析边计数时用 [atRow] 代替（不必读完整个文件）。
     * @param atRow 触发上限时的行号（含表头，1 起）。
     */
    internal fun tooManyRows(limit: Int, actual: Int? = null, atRow: Int? = null): SpreadsheetReadException {
        val detail = when {
            actual != null -> tr(
                "，当前文件有 %s 行",
                ", the file has %s rows",
                formatCount(actual),
            )
            atRow != null -> tr(
                "，读到第 %s 行（含表头）已超限",
                ", the limit was reached at row %s (header included)",
                formatCount(atRow),
            )
            else -> ""
        }
        return SpreadsheetReadException(
            tr(
                "单次最多导入 %s 行%s，请拆分文件后再导入",
                "At most %s rows can be imported%s — split the file and import again",
                formatCount(limit),
                detail,
            ),
        )
    }
}
