package com.milenote.android.domain.import.file

import com.milenote.android.domain.i18n.tr

/**
 * 表格文件读取门面：按文件签名 + 扩展名分派到
 * [XlsxReader] / [XlsReader] / [CsvReader] / [QianJiJsonReader]。
 *
 * 分派顺序：
 * 1. 文件头是 `PK\x03\x04` → .xlsx（即使扩展名写错也能正确读取）；
 * 2. 文件头是 `D0 CF 11 E0` → .xls（OLE2/BIFF8）；
 * 3. `.json` → 钱迹 JSON 读取器；`.txt` 且内容以 `[` / `{` 开头 → 钱迹 JSON 读取器
 *    （钱迹的「导出 txt」实际是 JSON）；其它 `.txt` 仍按 CSV 读取；
 * 4. `.csv` / `.tsv` → 文本表格；其它扩展名但内容像文本 → 按 CSV 兜底；
 *    内容像 JSON 但扩展名既不是 .json 也不是 .txt 时，提示修改扩展名。
 *
 * [read] 返回的 [SpreadsheetData] 已经过统一规整：Excel/CSV 的表头 = 第一行有效行，
 * 数据行跳过完全空白的行（因此前导空行会被自动跳过）；钱迹 JSON 的表头 = 账单对象键并集。
 */
class SpreadsheetFiles(
    private val xlsxReader: XlsxReader = XlsxReader(),
    private val xlsReader: XlsReader = XlsReader(),
    private val csvReader: CsvReader = CsvReader(),
    private val qianJiJsonReader: QianJiJsonReader = QianJiJsonReader(),
) : SpreadsheetReader {

    override fun read(bytes: ByteArray, fileName: String): SpreadsheetData {
        validateFile(bytes)
        return when {
            isXlsxSignature(bytes) -> xlsxReader.read(bytes, fileName)
            isXlsSignature(bytes) -> xlsReader.read(bytes, fileName)
            else -> when (extensionOf(fileName)) {
                "xlsx" -> throw SpreadsheetReadException(
                    tr("文件不是有效的 Excel 表格（.xlsx 文件已损坏或格式不正确）", "Not a valid Excel file (.xlsx is corrupted or has the wrong format)"),
                )
                "xls" -> throw SpreadsheetReadException(
                    tr("文件不是有效的 Excel 表格（.xls 文件已损坏或格式不正确）", "Not a valid Excel file (.xls is corrupted or has the wrong format)"),
                )
                else -> readTextLike(bytes, fileName)
            }
        }
    }

    /**
     * 读取指定工作表；CSV / 钱迹 JSON 只有一张「表」，会忽略 [sheetName]。
     *
     * 界面可以在第一次 [read] 后展示 [SpreadsheetData.sheetNames] 让用户切换。
     */
    fun readSheet(bytes: ByteArray, fileName: String, sheetName: String): SpreadsheetData {
        validateFile(bytes)
        return when {
            isXlsxSignature(bytes) -> xlsxReader.readSheet(bytes, sheetName, fileName)
            isXlsSignature(bytes) -> xlsReader.readSheet(bytes, sheetName, fileName)
            else -> when (extensionOf(fileName)) {
                "xlsx" -> throw SpreadsheetReadException(
                    tr("文件不是有效的 Excel 表格（.xlsx 文件已损坏或格式不正确）", "Not a valid Excel file (.xlsx is corrupted or has the wrong format)"),
                )
                "xls" -> throw SpreadsheetReadException(
                    tr("文件不是有效的 Excel 表格（.xls 文件已损坏或格式不正确）", "Not a valid Excel file (.xls is corrupted or has the wrong format)"),
                )
                else -> readTextLike(bytes, fileName)
            }
        }
    }

    /** 读取工作簿内全部工作表名；CSV / 钱迹 JSON 返回单元素列表。 */
    fun sheetNames(bytes: ByteArray, fileName: String): List<String> {
        validateFile(bytes)
        return when {
            isXlsxSignature(bytes) -> xlsxReader.sheetNames(bytes)
            isXlsSignature(bytes) -> xlsReader.sheetNames(bytes)
            extensionOf(fileName) == "json" -> listOf(QianJiJsonReader.SHEET_NAME)
            extensionOf(fileName) == "txt" && looksLikeJson(bytes) -> listOf(QianJiJsonReader.SHEET_NAME)
            else -> listOf(CsvReader.SHEET_NAME)
        }
    }

    private fun validateFile(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw SpreadsheetReadException(tr("文件是空的，请选择有效的表格文件", "The file is empty — choose a valid spreadsheet file"))
        }
        if (bytes.size > SpreadsheetLimits.MAX_FILE_BYTES) {
            throw SpreadsheetReadException(tr("文件超过 10 MB，请拆分后再导入", "File exceeds 10 MB — split it and import again"))
        }
    }

    private fun readTextLike(bytes: ByteArray, fileName: String): SpreadsheetData =
        when (extensionOf(fileName)) {
            "json" -> qianJiJsonReader.read(bytes, fileName)
            "txt" -> if (looksLikeJson(bytes)) {
                qianJiJsonReader.read(bytes, fileName)
            } else {
                csvReader.read(bytes, fileName)
            }

            "csv", "tsv" -> csvReader.read(bytes, fileName)
            else -> if (looksLikeText(bytes)) {
                if (looksLikeJson(bytes)) {
                    throw SpreadsheetReadException(
                        tr(
                            "文件内容是 JSON 数据，请将扩展名改为 .json 或 .txt 后再导入",
                            "The file contains JSON data — rename it to .json or .txt and import again",
                        ),
                    )
                }
                csvReader.read(bytes, fileName)
            } else {
                throw SpreadsheetReadException(
                    tr(
                        "不支持的文件格式，请选择 .xlsx、.xls、.csv、.tsv、.json 或 .txt 文件",
                        "Unsupported file format — choose a .xlsx, .xls, .csv, .tsv, .json or .txt file",
                    ),
                )
            }
        }

    private fun isXlsxSignature(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private fun isXlsSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        return magic.indices.all { bytes[it] == magic[it] }
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase()

    /** 文本内容（trim 后）是否像 JSON 数组/对象。 */
    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val text = TextDecoder.decode(bytes).trim()
        if (text.length < 2) return false
        return (text.first() == '[' && text.last() == ']') ||
            (text.first() == '{' && text.last() == '}')
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 4096)
        for (index in 0 until limit) {
            if (bytes[index] == 0.toByte()) return false
        }
        return true
    }
}
