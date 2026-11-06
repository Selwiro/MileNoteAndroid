package com.milenote.android.domain.import.file

/**
 * 表格文件的统一中间表示。
 *
 * 文件读取层（XLSX / XLS / CSV）只负责把字节变成「表头 + 数据行」，
 * 字段映射、类型推断、导入规则在 domain.import 层完成。
 */
data class SpreadsheetData(
    /** 第一行有效表头（已 trim）。 */
    val headers: List<String>,
    /** 表头之后的数据行；单元格为 String / Double / Boolean / null。 */
    val rows: List<List<Any?>>,
    /** 工作簿内全部工作表名；CSV 为单元素列表。 */
    val sheetNames: List<String> = emptyList(),
    /** 当前读取的工作表名。 */
    val activeSheet: String = "",
    /** 原始文件名，仅用于界面展示。 */
    val fileName: String = "",
)

/** 读取表格文件；实现必须按扩展名/文件签名选择 XLSX / XLS / CSV。 */
interface SpreadsheetReader {
    fun read(bytes: ByteArray, fileName: String): SpreadsheetData
}
