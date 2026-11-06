package com.milenote.android.domain.import.file

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

    /** xlsx 解压后所有工作表 XML 的总字节上限：64 MB。 */
    const val MAX_UNCOMPRESSED_BYTES: Long = 64L * 1024 * 1024

    /** xlsx 解压后单个 XML 条目上限：32 MB。 */
    const val MAX_ENTRY_BYTES: Long = 32L * 1024 * 1024

    /** 把行数格式化成带千分位的文案，例如 `20000` -> `20,000`。 */
    internal fun formatCount(count: Int): String =
        count.toString().reversed().chunked(3).joinToString(",").reversed()

    /** 构造统一的「超过行数上限」异常。 */
    internal fun tooManyRows(limit: Int, actual: Int? = null): SpreadsheetReadException {
        val suffix = actual?.let { "，当前文件有 ${formatCount(it)} 行" }.orEmpty()
        return SpreadsheetReadException("单次最多导入 ${formatCount(limit)} 行$suffix")
    }
}
