package com.milenote.android.domain.import.file

import jxl.BooleanCell
import jxl.Cell
import jxl.CellType
import jxl.DateCell
import jxl.NumberCell
import jxl.Sheet
import jxl.Workbook
import jxl.WorkbookSettings
import jxl.read.biff.BiffException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * .xls（BIFF8，OLE2 复合文档）读取器，基于 jxl。
 *
 * 处理：
 * - 日期单元格（[DateCell]）→ `YYYY-MM-DD` 字符串；
 * - 数字/布尔/字符串/错误单元格；
 * - 公式单元格取其缓存结果（jxl 暴露为 `*_FORMULA` 类型）；
 * - 多工作表：默认读第一个可见表，[readSheet] 可指定表名。
 */
class XlsReader(
    private val maxDataRows: Int = SpreadsheetLimits.MAX_DATA_ROWS,
) {

    /**
     * 读取默认工作表（第一个可见表，没有可见表时取第一个）。
     *
     * @param fileName 仅用于 [SpreadsheetData.fileName] 展示。
     */
    fun read(bytes: ByteArray, fileName: String = ""): SpreadsheetData =
        useWorkbook(bytes) { workbook ->
            val sheet = firstVisibleSheet(workbook)
                ?: throw SpreadsheetReadException("文件不是有效的 Excel 表格：工作簿里没有工作表")
            readSheet(workbook, sheet, fileName)
        }

    /**
     * 读取指定名称的工作表；名称精确匹配失败时再做一次忽略大小写匹配。
     *
     * @throws SpreadsheetReadException 找不到该工作表时抛出。
     */
    fun readSheet(bytes: ByteArray, sheetName: String, fileName: String = ""): SpreadsheetData =
        useWorkbook(bytes) { workbook ->
            val names = workbook.sheetNames.toList()
            val index = names.indexOfFirst { it == sheetName }
                .takeIf { it >= 0 }
                ?: names.indexOfFirst { it.equals(sheetName, ignoreCase = true) }
            if (index < 0) throw SpreadsheetReadException("找不到工作表「$sheetName」")
            readSheet(workbook, workbook.getSheet(index), fileName)
        }

    /** 只读取工作簿里的工作表名，不解析单元格。 */
    fun sheetNames(bytes: ByteArray): List<String> =
        useWorkbook(bytes) { workbook -> workbook.sheetNames.toList() }

    // ---- 内部实现 ----

    private fun <T> useWorkbook(bytes: ByteArray, block: (Workbook) -> T): T {
        if (!looksLikeCdf(bytes)) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xls 文件已损坏或格式不正确）")
        }
        val settings = WorkbookSettings().apply {
            setSuppressWarnings(true)
            setGCDisabled(true)
        }
        val workbook = try {
            Workbook.getWorkbook(ByteArrayInputStream(bytes), settings)
        } catch (exception: BiffException) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xls 文件已损坏或已加密）", exception)
        } catch (exception: IOException) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xls 文件读取失败）", exception)
        } catch (exception: RuntimeException) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xls 文件已损坏或已加密）", exception)
        }
        return try {
            block(workbook)
        } catch (exception: SpreadsheetReadException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xls 文件内容损坏）", exception)
        } finally {
            workbook.close()
        }
    }

    @Suppress("DEPRECATION") // jxl 读模式下 Sheet.getSettings() 为 null，只能用 isHidden()。
    private fun firstVisibleSheet(workbook: Workbook): Sheet? {
        val sheets = workbook.sheets
        return sheets.firstOrNull { !it.isHidden } ?: sheets.firstOrNull()
    }

    private fun readSheet(workbook: Workbook, sheet: Sheet, fileName: String): SpreadsheetData {
        val totalRows = sheet.rows
        if (totalRows > maxDataRows + 1) {
            throw SpreadsheetLimits.tooManyRows(maxDataRows, totalRows)
        }
        val columns = sheet.columns
        val grid = ArrayList<List<Any?>>(totalRows.coerceAtLeast(0))
        for (rowIndex in 0 until totalRows) {
            val row = ArrayList<Any?>(columns.coerceAtLeast(0))
            for (columnIndex in 0 until columns) {
                row.add(cellValue(sheet.getCell(columnIndex, rowIndex)))
            }
            grid.add(row)
        }
        return SpreadsheetGrid.normalize(
            grid = grid,
            sheetNames = workbook.sheetNames.toList(),
            activeSheet = sheet.name,
            fileName = fileName,
            maxDataRows = maxDataRows,
        )
    }

    private fun cellValue(cell: Cell): Any? = when (cell.type) {
        CellType.EMPTY -> null
        CellType.LABEL, CellType.STRING_FORMULA -> cell.contents.ifEmpty { null }
        CellType.NUMBER, CellType.NUMBER_FORMULA -> numberValue(cell)
        CellType.DATE, CellType.DATE_FORMULA -> dateValue(cell)
        CellType.BOOLEAN, CellType.BOOLEAN_FORMULA ->
            (cell as? BooleanCell)?.value ?: cell.contents.equals("true", ignoreCase = true)

        CellType.ERROR, CellType.FORMULA_ERROR -> cell.contents.ifEmpty { null }
        else -> cell.contents.ifEmpty { null }
    }

    private fun numberValue(cell: Cell): Any? {
        val numberCell = cell as? NumberCell
        if (numberCell != null) return numberCell.value
        return cell.contents.toDoubleOrNull() ?: cell.contents.ifEmpty { null }
    }

    private fun dateValue(cell: Cell): Any? {
        val dateCell = cell as? DateCell
        if (dateCell != null) {
            return Instant.ofEpochMilli(dateCell.date.time)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }
        return cell.contents.ifEmpty { null }
    }

    private fun looksLikeCdf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        return magic.indices.all { bytes[it] == magic[it] }
    }
}
