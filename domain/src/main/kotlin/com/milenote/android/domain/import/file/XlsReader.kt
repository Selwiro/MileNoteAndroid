package com.milenote.android.domain.import.file

import com.milenote.android.domain.i18n.tr
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.floor

/**
 * .xls（BIFF8，Excel 97-2003）读取器，**纯 Kotlin 实现、零第三方依赖**。
 *
 * 历史背景：旧实现基于 LGPL 的 jxl（JExcelAPI 2.6.12，2009 年停止维护，且会带进 EOL 的
 * log4j 1.2.14）。为了让 APK 不再分发 LGPL 组件、并去掉这条陈旧的传递依赖，
 * 这里改为自行解析 OLE2 复合文档 + BIFF8 记录流。
 *
 * 支持范围（与旧实现保持一致的语义）：
 * - 字符串（共享字符串表 SST / LABEL / LABEL-SST，含 UTF-16 与压缩 8 位编码）；
 * - 数字（NUMBER、RK、MULRK，含 `/100` 标志）；
 * - 日期/时间（按 XF 的数字格式判定，输出本地日历日 `YYYY-MM-DD`）；
 * - 布尔与错误单元格（错误按空值处理）；
 * - 公式单元格取其缓存结果（数字/字符串/布尔/空）；
 * - 多工作表：默认读第一个可见表，[readSheet] 可指定表名；
 * - 1904 日期系统、隐藏/深度隐藏工作表。
 *
 * 不支持：BIFF5/BIFF7（Excel 95 及更早，会给出明确提示）、加密工作簿、宏表内容。
 * 单个文件大小与行数上限由 [SpreadsheetLimits] 统一约束。
 */
class XlsReader(
    private val maxDataRows: Int = SpreadsheetLimits.MAX_DATA_ROWS,
) {

    /**
     * 读取默认工作表（第一个可见表，没有可见表时取第一个）。
     *
     * @param fileName 仅用于 [SpreadsheetData.fileName] 展示。
     */
    fun read(bytes: ByteArray, fileName: String = ""): SpreadsheetData {
        val workbook = openWorkbook(bytes)
        val sheet = workbook.sheets.firstOrNull { !it.hidden }
            ?: workbook.sheets.firstOrNull()
            ?: throw SpreadsheetReadException(
                tr("文件不是有效的 Excel 表格：工作簿里没有工作表", "Not a valid Excel file: the workbook has no worksheets"),
            )
        return readSheet(workbook, sheet, fileName)
    }

    /**
     * 读取指定名称的工作表；名称精确匹配失败时再做一次忽略大小写匹配。
     *
     * @throws SpreadsheetReadException 找不到该工作表时抛出。
     */
    fun readSheet(bytes: ByteArray, sheetName: String, fileName: String = ""): SpreadsheetData {
        val workbook = openWorkbook(bytes)
        val index = workbook.sheets.indexOfFirst { it.name == sheetName }
            .takeIf { it >= 0 }
            ?: workbook.sheets.indexOfFirst { it.name.equals(sheetName, ignoreCase = true) }
        if (index < 0) {
            throw SpreadsheetReadException(tr("找不到工作表「%s」", "Worksheet '%s' not found", sheetName))
        }
        return readSheet(workbook, workbook.sheets[index], fileName)
    }

    /** 只读取工作簿里的工作表名，不解析单元格。 */
    fun sheetNames(bytes: ByteArray): List<String> = openWorkbook(bytes).sheets.map { it.name }

    // ---- 内部实现 ----

    private fun openWorkbook(bytes: ByteArray): XlsWorkbook {
        if (!looksLikeCdf(bytes)) {
            throw SpreadsheetReadException(
                tr("文件不是有效的 Excel 表格（.xls 文件已损坏或格式不正确）", "Not a valid Excel file (.xls is corrupted or has the wrong format)"),
            )
        }
        return try {
            XlsWorkbook.open(bytes)
        } catch (exception: SpreadsheetReadException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw SpreadsheetReadException(
                tr("文件不是有效的 Excel 表格（.xls 文件已损坏或已加密）", "Not a valid Excel file (.xls is corrupted or encrypted)"),
                exception,
            )
        }
    }

    private fun readSheet(workbook: XlsWorkbook, sheet: XlsWorkbook.Sheet, fileName: String): SpreadsheetData {
        val grid = try {
            workbook.readGrid(sheet, maxDataRows)
        } catch (exception: SpreadsheetReadException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw SpreadsheetReadException(
                tr("文件不是有效的 Excel 表格（.xls 文件内容损坏）", "Not a valid Excel file (.xls content is corrupted)"),
                exception,
            )
        }
        return SpreadsheetGrid.normalize(
            grid = grid,
            sheetNames = workbook.sheets.map { it.name },
            activeSheet = sheet.name,
            fileName = fileName,
            maxDataRows = maxDataRows,
        )
    }

    private fun looksLikeCdf(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        return magic.indices.all { bytes[it] == magic[it] }
    }
}

// ---------------------------------------------------------------------------
// BIFF8 记录号
// ---------------------------------------------------------------------------

private const val REC_FORMULA = 0x0006
private const val REC_EOF = 0x000A
private const val REC_DATEMODE = 0x0022
private const val REC_FILEPASS = 0x002F
private const val REC_CONTINUE = 0x003C
private const val REC_BOUNDSHEET = 0x0085
private const val REC_MULRK = 0x00BD
private const val REC_MULBLANK = 0x00BE
private const val REC_RSTRING = 0x00D6
private const val REC_XF = 0x00E0
private const val REC_SST = 0x00FC
private const val REC_LABELSST = 0x00FD
private const val REC_BLANK = 0x0201
private const val REC_NUMBER = 0x0203
private const val REC_LABEL = 0x0204
private const val REC_BOOLERR = 0x0205
private const val REC_STRING = 0x0207
private const val REC_RK = 0x027E
private const val REC_FORMAT = 0x041E
private const val REC_BOF = 0x0809

/** Excel 内置的日期/时间数字格式索引（含东亚语言版本的本地化格式）。 */
private val BUILTIN_DATE_FORMAT_INDEXES = setOf(
    14, 15, 16, 17, 18, 19, 20, 21, 22,
    27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
    45, 46, 47,
    50, 51, 52, 53, 54, 55, 56, 57, 58,
)

private const val MAX_SHEETS = 512
private const val MAX_SHARED_STRINGS = 2_000_000
private const val MAX_CELLS = 2_000_000

// ---------------------------------------------------------------------------
// 工作簿
// ---------------------------------------------------------------------------

/** 解析后的 BIFF8 工作簿结构（共享字符串、数字格式、工作表入口）；单元格按需解析。 */
private class XlsWorkbook(
    private val stream: ByteArray,
    val sheets: List<Sheet>,
    private val sharedStrings: List<String>,
    private val xfFormats: IntArray,
    private val customFormats: Map<Int, String>,
    private val date1904: Boolean,
) {

    /** 工作表入口：名称、是否隐藏、BIFF 记录流中的起始偏移。 */
    class Sheet(val name: String, val hidden: Boolean, val start: Int)

    fun readGrid(sheet: Sheet, maxDataRows: Int): List<List<Any?>> {
        val cursor = BiffCursor(stream, sheet.start)
        val bof = cursor.next() ?: throw IllegalStateException("工作表缺少 BOF 记录")
        if (bof.type != REC_BOF || bof.length < 4) throw IllegalStateException("工作表 BOF 记录无效")
        if (bof.u16(2) != 0x0010) throw IllegalStateException("不是工作表子流")

        val cells = HashMap<Long, Any?>()
        var maxRow = -1
        var maxCol = -1

        fun put(row: Int, col: Int, xf: Int, raw: Raw) {
            if (row < 0 || col < 0 || col > 0xFF) return
            if (row > maxRow) maxRow = row
            if (col > maxCol) maxCol = col
            val value = valueOf(raw, xf)
            if (value != null) {
                if (cells.size >= MAX_CELLS) throw IllegalStateException("单元格数量超出上限")
                cells[key(row, col)] = value
            }
        }

        while (true) {
            val record = cursor.next() ?: break
            when (record.type) {
                REC_EOF -> break

                REC_LABELSST -> if (record.length >= 10) {
                    val index = record.u32(6)
                    put(record.u16(0), record.u16(2), record.u16(4), Raw.Text(sharedStrings.getOrNull(index).orEmpty()))
                }

                REC_LABEL, REC_RSTRING -> if (record.length >= 9) {
                    put(record.u16(0), record.u16(2), record.u16(4), Raw.Text(record.unicodeString(6)))
                }

                REC_NUMBER -> if (record.length >= 14) {
                    put(record.u16(0), record.u16(2), record.u16(4), Raw.Number(record.double(6)))
                }

                REC_RK -> if (record.length >= 10) {
                    put(record.u16(0), record.u16(2), record.u16(4), Raw.Number(numberFromRk(record.u32(6))))
                }

                REC_MULRK -> {
                    val row = record.u16(0)
                    val firstCol = record.u16(2)
                    val count = (record.length - 6) / 6
                    for (index in 0 until count) {
                        val base = 4 + index * 6
                        put(row, firstCol + index, record.u16(base), Raw.Number(numberFromRk(record.u32(base + 2))))
                    }
                }

                REC_BOOLERR -> if (record.length >= 8) {
                    val raw = if (record.u8(7) != 0) Raw.Error else Raw.Boolean(record.u8(6) != 0)
                    put(record.u16(0), record.u16(2), record.u16(4), raw)
                }

                REC_FORMULA -> if (record.length >= 14) parseFormula(record, cursor, ::put)

                REC_BLANK -> if (record.length >= 6) {
                    put(record.u16(0), record.u16(2), record.u16(4), Raw.Blank)
                }

                REC_MULBLANK -> {
                    val row = record.u16(0)
                    val firstCol = record.u16(2)
                    val count = (record.length - 6) / 2
                    for (index in 0 until count) {
                        val xf = record.u16(4 + index * 2)
                        if (firstCol + index <= 0xFF) {
                            if (firstCol + index > maxCol) maxCol = firstCol + index
                            if (row > maxRow) maxRow = row
                            // MULBLANK 只标记「有格式的空单元格」，按空值处理即可。
                            if (xf >= 0) Unit
                        }
                    }
                }

                else -> Unit
            }
        }

        if (maxRow + 1 > maxDataRows + 1) throw SpreadsheetLimits.tooManyRows(maxDataRows, maxRow + 1)

        val rowCount = maxRow + 1
        val columnCount = maxCol + 1
        if (rowCount <= 0 || columnCount <= 0) return emptyList()
        val grid = ArrayList<List<Any?>>(rowCount)
        for (row in 0 until rowCount) {
            val values = ArrayList<Any?>(columnCount)
            for (column in 0 until columnCount) values.add(cells[key(row, column)])
            grid.add(values)
        }
        return grid
    }

    private fun parseFormula(
        record: BiffRecord,
        cursor: BiffCursor,
        put: (row: Int, col: Int, xf: Int, raw: Raw) -> Unit,
    ) {
        val row = record.u16(0)
        val col = record.u16(2)
        val xf = record.u16(4)
        val special = record.u8(12) == 0xFF && record.u8(13) == 0xFF
        if (!special) {
            put(row, col, xf, Raw.Number(record.double(6)))
            return
        }
        when (record.u8(6)) {
            0 -> {
                val next = cursor.peek()
                if (next != null && next.type == REC_STRING) {
                    cursor.next()
                    put(row, col, xf, Raw.Text(next.unicodeString(0)))
                } else {
                    put(row, col, xf, Raw.Text(""))
                }
            }

            1 -> put(row, col, xf, Raw.Boolean(record.u8(8) != 0))
            2 -> put(row, col, xf, Raw.Error)
            else -> put(row, col, xf, Raw.Blank)
        }
    }

    /** 把原始单元格值转换成语义值：日期转 `YYYY-MM-DD`，空字符串归为 `null`。 */
    private fun valueOf(raw: Raw, xf: Int): Any? = when (raw) {
        is Raw.Number -> if (isDateFormat(xf)) dateValue(raw.value) ?: raw.value else raw.value
        is Raw.Text -> raw.value.ifEmpty { null }
        is Raw.Boolean -> raw.value
        Raw.Blank, Raw.Error -> null
    }

    private fun isDateFormat(xf: Int): Boolean {
        if (xf < 0 || xf >= xfFormats.size) return false
        val numberFormat = xfFormats[xf]
        customFormats[numberFormat]?.let { return looksLikeDateFormat(it) }
        return numberFormat in BUILTIN_DATE_FORMAT_INDEXES
    }

    private fun dateValue(serial: Double): String? {
        if (serial.isNaN() || serial.isInfinite()) return null
        val days = floor(serial).toLong()
        if (days < -1_000_000L || days > 3_000_000L) return null
        val date = when {
            date1904 -> LocalDate.of(1904, 1, 1).plusDays(days)
            // Excel 把 1900 当闰年（序列值 60 = 不存在的 1900-02-29），60 之后按 1899-12-30 起算。
            days < 60 -> LocalDate.of(1899, 12, 31).plusDays(days)
            else -> LocalDate.of(1899, 12, 30).plusDays(days)
        }
        return date.toString()
    }

    /** 单元格坐标压缩成单个 `Long`（行号在高位），避免二维数组带来的大量空对象。 */
    private fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()

    companion object {

        fun open(bytes: ByteArray): XlsWorkbook {
            val compound = CompoundFile(bytes)
            val stream = compound.stream("Workbook") ?: compound.stream("Book")
                ?: throw IllegalStateException("复合文档里没有 Workbook 流")
            if (stream.size < 8) throw IllegalStateException("Workbook 流过短")

            val cursor = BiffCursor(stream, 0)
            val bof = cursor.next() ?: throw IllegalStateException("工作簿缺少 BOF 记录")
            if (bof.type != REC_BOF || bof.length < 4) throw IllegalStateException("工作簿 BOF 记录无效")
            val version = bof.u16(0)
            if (version != 0x0600) {
                throw SpreadsheetReadException(
                    tr(
                        "文件不是有效的 Excel 表格（不支持 Excel 95 及更早的 .xls，请另存为 .xlsx 后再导入）",
                        "Not a supported Excel file (Excel 95 and older .xls files are not supported — save as .xlsx and import again)",
                    ),
                )
            }

            var date1904 = false
            val sheets = ArrayList<Sheet>()
            val xfFormats = ArrayList<Int>()
            val customFormats = HashMap<Int, String>()
            var sharedStrings: List<String> = emptyList()

            while (true) {
                val record = cursor.next() ?: throw IllegalStateException("全局记录流被截断")
                when (record.type) {
                    REC_EOF -> break
                    REC_FILEPASS -> throw SpreadsheetReadException(
                        tr("文件不是有效的 Excel 表格（.xls 文件已加密）", "Not a valid Excel file (.xls is encrypted)"),
                    )

                    REC_BOUNDSHEET -> if (record.length >= 8 && sheets.size < MAX_SHEETS) {
                        sheets.add(parseBoundSheet(record))
                    }

                    REC_DATEMODE -> if (record.length >= 2) date1904 = record.u16(0) == 1
                    REC_XF -> if (record.length >= 4) xfFormats.add(record.u16(2))
                    REC_FORMAT -> if (record.length > 3) {
                        customFormats[record.u16(0)] = record.unicodeString(2)
                    }

                    REC_SST -> {
                        val segments = ArrayList<ByteArray>()
                        segments.add(record.slice())
                        while (cursor.peek()?.type == REC_CONTINUE) {
                            cursor.next()?.let { segments.add(it.slice()) }
                        }
                        sharedStrings = parseSharedStrings(segments)
                    }

                    else -> Unit
                }
            }

            return XlsWorkbook(
                stream = stream,
                sheets = sheets,
                sharedStrings = sharedStrings,
                xfFormats = xfFormats.toIntArray(),
                customFormats = customFormats,
                date1904 = date1904,
            )
        }

        private fun parseBoundSheet(record: BiffRecord): Sheet {
            val start = record.u32(0)
            val grbit = record.u16(4)
            val hidden = (grbit and 0x0003) != 0
            val nameLength = record.u8(6)
            val flags = record.u8(7)
            val name = if (flags and 0x01 != 0) {
                record.utf16(8, nameLength)
            } else {
                record.latin1(8, nameLength)
            }
            return Sheet(name, hidden, start)
        }

        private fun parseSharedStrings(segments: List<ByteArray>): List<String> {
            val cursor = SharedStringCursor(segments)
            cursor.u32() // 引用总数，不需要
            val unique = cursor.u32()
            if (unique < 0 || unique > MAX_SHARED_STRINGS) throw IllegalStateException("共享字符串数量异常")
            val result = ArrayList<String>(unique.toInt())
            for (index in 0 until unique.toInt()) result.add(cursor.string())
            return result
        }
    }
}

/** 单元格原始值（尚未按数字格式解释）。 */
private sealed interface Raw {
    class Number(val value: Double) : Raw
    class Text(val value: String) : Raw
    class Boolean(val value: kotlin.Boolean) : Raw
    data object Blank : Raw
    data object Error : Raw
}

/** 把 RK 编码的数字还原成 `Double`。 */
private fun numberFromRk(rk: Int): Double {
    val value = if (rk and 0x02 != 0) {
        (rk shr 2).toDouble()
    } else {
        Double.fromBits((rk.toLong() and 0xFFFFFFFCL) shl 32)
    }
    return if (rk and 0x01 != 0) value / 100.0 else value
}

/** 判断自定义数字格式串是否表示日期/时间（剔除引号、方括号、转义与填充字符后看 y/d/h/s）。 */
private fun looksLikeDateFormat(format: String): Boolean {
    val visible = StringBuilder(format.length)
    var index = 0
    while (index < format.length) {
        when (val char = format[index]) {
            '"' -> {
                index++
                while (index < format.length && format[index] != '"') index++
                index++
            }

            '\\' -> index += 2
            '[' -> {
                index++
                while (index < format.length && format[index] != ']') index++
                index++
            }

            '_', '*' -> index += 2
            else -> {
                visible.append(char)
                index++
            }
        }
    }
    val text = visible.toString()
    if (text.isBlank()) return false
    return text.any { char ->
        char == 'y' || char == 'Y' || char == 'd' || char == 'D' ||
            char == 'h' || char == 'H' || char == 's' || char == 'S'
    }
}

// ---------------------------------------------------------------------------
// BIFF 记录游标
// ---------------------------------------------------------------------------

private class BiffRecord(
    val type: Int,
    private val data: ByteArray,
    private val offset: Int,
    val length: Int,
) {
    /** 记录中的第 [index] 个字节（无符号）。 */
    fun u8(index: Int): Int {
        requireRange(index, 1)
        return data[offset + index].toInt() and 0xFF
    }

    fun u16(index: Int): Int {
        requireRange(index, 2)
        return leU16(data, offset + index)
    }

    fun u32(index: Int): Int {
        requireRange(index, 4)
        return leU32(data, offset + index)
    }

    fun double(index: Int): Double {
        requireRange(index, 8)
        return Double.fromBits(leU64(data, offset + index))
    }

    /** 记录数据段的副本（用于 SST 这类需要跨 CONTINUE 拼接的记录）。 */
    fun slice(): ByteArray = data.copyOfRange(offset, offset + length)

    /** BIFF8 `XLUnicodeString`（cch 两字节 + flags + 字符数据，可选 rich/ext 尾部）。 */
    fun unicodeString(index: Int): String {
        val chars = u16(index)
        val flags = u8(index + 2)
        var position = index + 3
        if (flags and 0x08 != 0) position += 2 // cRun
        if (flags and 0x04 != 0) position += 4 // cbExtRst
        return if (flags and 0x01 != 0) utf16(position, chars) else latin1(position, chars)
    }

    fun utf16(index: Int, chars: Int): String {
        requireRange(index, chars * 2)
        val builder = StringBuilder(chars)
        for (charIndex in 0 until chars) {
            val low = data[offset + index + charIndex * 2].toInt() and 0xFF
            val high = data[offset + index + charIndex * 2 + 1].toInt() and 0xFF
            builder.append(((high shl 8) or low).toChar())
        }
        return builder.toString()
    }

    fun latin1(index: Int, chars: Int): String {
        requireRange(index, chars)
        return String(data, offset + index, chars, Charsets.ISO_8859_1)
    }

    private fun requireRange(index: Int, size: Int) {
        if (index < 0 || size < 0 || index + size > length) {
            throw IllegalStateException("BIFF 记录长度不足")
        }
    }
}

/** 顺序读取 BIFF 记录，支持一次 lookahead（公式的字符串结果在紧随其后的 STRING 记录里）。 */
private class BiffCursor(
    private val data: ByteArray,
    private var position: Int,
) {
    private var pending: BiffRecord? = null

    fun peek(): BiffRecord? {
        if (pending == null) pending = read()
        return pending
    }

    fun next(): BiffRecord? {
        val cached = pending
        if (cached != null) {
            pending = null
            return cached
        }
        return read()
    }

    private fun read(): BiffRecord? {
        if (position < 0 || position + 4 > data.size) return null
        val type = leU16(data, position)
        val length = leU16(data, position + 2)
        val start = position + 4
        if (start + length > data.size) return null
        position = start + length
        return BiffRecord(type, data, start, length)
    }
}

/** 共享字符串表游标：把 SST 与其 CONTINUE 记录当成一条连续字节流读取。 */
private class SharedStringCursor(private val segments: List<ByteArray>) {
    private var segment = 0
    private var offset = 0

    fun u8(): Int {
        advanceSegment()
        if (segment >= segments.size) throw IllegalStateException("SST 记录被截断")
        return segments[segment][offset++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

    fun string(): String {
        val chars = u16()
        val flags = u8()
        var highByte = flags and 0x01 != 0
        val richRuns = if (flags and 0x08 != 0) u16() else 0
        val extensionBytes = if (flags and 0x04 != 0) u32() else 0
        if (extensionBytes < 0 || extensionBytes > Int.MAX_VALUE) throw IllegalStateException("SST 扩展长度异常")

        val builder = StringBuilder(chars)
        var remaining = chars
        while (remaining > 0) {
            if (segment >= segments.size) throw IllegalStateException("SST 字符串被截断")
            val current = segments[segment]
            if (offset >= current.size) {
                // 字符数据跨 CONTINUE：新段以新的 fHighByte 标志字节开头。
                segment++
                offset = 0
                if (segment >= segments.size) throw IllegalStateException("SST 字符串被截断")
                highByte = u8() and 0x01 != 0
                continue
            }
            val charBytes = if (highByte) 2 else 1
            if (offset + charBytes > current.size) {
                segment++
                offset = 0
                if (segment >= segments.size) throw IllegalStateException("SST 字符串被截断")
                highByte = u8() and 0x01 != 0
                continue
            }
            if (highByte) {
                val low = current[offset].toInt() and 0xFF
                val high = current[offset + 1].toInt() and 0xFF
                builder.append(((high shl 8) or low).toChar())
                offset += 2
            } else {
                builder.append((current[offset].toInt() and 0xFF).toChar())
                offset++
            }
            remaining--
        }
        skip(richRuns.toLong() * 4)
        skip(extensionBytes)
        return builder.toString()
    }

    private fun skip(count: Long) {
        var remaining = count
        while (remaining > 0) {
            advanceSegment()
            if (segment >= segments.size) throw IllegalStateException("SST 记录被截断")
            val available = (segments[segment].size - offset).toLong()
            val take = minOf(available, remaining)
            offset += take.toInt()
            remaining -= take
        }
    }

    private fun advanceSegment() {
        while (segment < segments.size && offset >= segments[segment].size) {
            segment++
            offset = 0
        }
    }
}

// ---------------------------------------------------------------------------
// OLE2 / 复合文档（CFB）解析
// ---------------------------------------------------------------------------

/**
 * 最小化 OLE2 复合文档读取器：只做「按名字取出一个流」这一件事。
 *
 * 结构参考 [MS-CFB]：512 字节头部 → FAT/DIFAT 扇区链 → 目录项 → 普通流 / 迷你流。
 */
private class CompoundFile(private val bytes: ByteArray) {

    private class Entry(val name: String, val type: Int, val startSector: Int, val size: Long)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    init {
        if (bytes.size < 512) throw IllegalStateException("复合文档头部不完整")
        val sectorShift = leU16(bytes, 0x1E)
        if (sectorShift !in 9..12) throw IllegalStateException("扇区大小异常")
        sectorSize = 1 shl sectorShift
        val miniSectorShift = leU16(bytes, 0x20)
        if (miniSectorShift != 6) throw IllegalStateException("迷你扇区大小异常")
        miniSectorSize = 1 shl miniSectorShift
        miniCutoff = leU32(bytes, 0x38).toLong() and 0xFFFFFFFFL

        val fatSectorCount = leU32(bytes, 0x2C)
        val directoryStart = leU32(bytes, 0x30)
        val miniFatStart = leU32(bytes, 0x3C)
        val miniFatSectorCount = leU32(bytes, 0x40)
        val difatStart = leU32(bytes, 0x44)

        // 1) 收集 FAT 扇区号：头部 DIFAT 数组 + DIFAT 扇区链。
        val fatSectors = ArrayList<Int>()
        val headerEntries = minOf(fatSectorCount, 109)
        for (index in 0 until headerEntries) {
            val sector = leU32(bytes, 0x4C + index * 4)
            if (sector >= 0) fatSectors.add(sector)
        }
        var difatSector = difatStart
        var difatGuard = 0
        while (difatSector >= 0 && fatSectors.size < fatSectorCount && difatGuard++ < MAX_FAT_SECTORS) {
            val sector = sector(difatSector)
            val last = sectorSize / 4 - 1
            for (index in 0 until last) {
                if (fatSectors.size >= fatSectorCount) break
                val fatSector = leU32(sector, index * 4)
                if (fatSector >= 0) fatSectors.add(fatSector)
            }
            difatSector = leU32(sector, last * 4)
        }
        if (fatSectors.isEmpty()) throw IllegalStateException("缺少 FAT 扇区")

        // 2) 读 FAT 表。
        fat = IntArray(fatSectors.size * (sectorSize / 4))
        var fatIndex = 0
        for (fatSector in fatSectors) {
            val sector = sector(fatSector)
            for (index in 0 until sectorSize / 4) fat[fatIndex++] = leU32(sector, index * 4)
        }

        // 3) 目录项。
        entries = parseDirectory(readFatChain(directoryStart))
        val root = entries.firstOrNull { it.type == 5 }
            ?: throw IllegalStateException("复合文档缺少根目录项")

        // 4) 迷你流与迷你 FAT。
        miniStream = if (root.size > 0) readFatChain(root.startSector) else ByteArray(0)
        miniFat = if (miniFatSectorCount > 0 && miniFatStart >= 0) {
            val raw = readFatChain(miniFatStart)
            IntArray(raw.size / 4) { index -> leU32(raw, index * 4) }
        } else {
            IntArray(0)
        }
    }

    /** 按名字（忽略大小写）取出流内容；找不到返回 `null`。 */
    fun stream(vararg names: String): ByteArray? {
        for (name in names) {
            val entry = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) }
            if (entry != null) return readStream(entry)
        }
        return null
    }

    private fun readStream(entry: Entry): ByteArray {
        if (entry.size <= 0) return ByteArray(0)
        if (entry.size > bytes.size * 4L) throw IllegalStateException("流长度异常")
        val raw = if (entry.size < miniCutoff) readMiniChain(entry.startSector) else readFatChain(entry.startSector)
        if (raw.size < entry.size) throw IllegalStateException("流被截断")
        return raw.copyOf(entry.size.toInt())
    }

    private fun readFatChain(start: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var sectorId = start
        var guard = 0
        while (sectorId >= 0) {
            if (sectorId >= fat.size) throw IllegalStateException("FAT 扇区号越界")
            output.write(sector(sectorId))
            val next = fat[sectorId]
            if (next < 0) break
            sectorId = next
            if (guard++ > fat.size + 8) throw IllegalStateException("FAT 扇区链成环")
        }
        return output.toByteArray()
    }

    private fun readMiniChain(start: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var sectorId = start
        var guard = 0
        while (sectorId >= 0) {
            if (sectorId >= miniFat.size) throw IllegalStateException("迷你 FAT 扇区号越界")
            val startOffset = sectorId * miniSectorSize
            if (startOffset < 0 || startOffset + miniSectorSize > miniStream.size) {
                throw IllegalStateException("迷你流越界")
            }
            output.write(miniStream, startOffset, miniSectorSize)
            val next = miniFat[sectorId]
            if (next < 0) break
            sectorId = next
            if (guard++ > miniFat.size + 8) throw IllegalStateException("迷你 FAT 链成环")
        }
        return output.toByteArray()
    }

    private fun sector(sectorId: Int): ByteArray {
        if (sectorId < 0) throw IllegalStateException("扇区号非法")
        val start = (sectorId.toLong() + 1) * sectorSize
        if (start >= bytes.size) throw IllegalStateException("扇区越界")
        val end = minOf(start + sectorSize, bytes.size.toLong())
        val sector = ByteArray(sectorSize)
        System.arraycopy(bytes, start.toInt(), sector, 0, (end - start).toInt())
        return sector
    }

    private fun parseDirectory(data: ByteArray): List<Entry> {
        if (data.size < 128) throw IllegalStateException("目录为空")
        val entries = ArrayList<Entry>(data.size / 128)
        var offset = 0
        while (offset + 128 <= data.size) {
            val type = data[offset + 0x42].toInt() and 0xFF
            if (type != 0) {
                val nameLength = leU16(data, offset + 0x40)
                val chars = if (nameLength >= 2) nameLength / 2 - 1 else 0
                val name = StringBuilder(chars)
                for (index in 0 until chars) {
                    val low = data[offset + index * 2].toInt() and 0xFF
                    val high = data[offset + index * 2 + 1].toInt() and 0xFF
                    name.append(((high shl 8) or low).toChar())
                }
                val startSector = leU32(data, offset + 0x74)
                val size = leU64(data, offset + 0x78) and 0xFFFFFFFFL
                entries.add(Entry(name.toString(), type, startSector, size))
            }
            offset += 128
        }
        return entries
    }

    private companion object {
        const val MAX_FAT_SECTORS = 1 shl 16
    }
}

// ---------------------------------------------------------------------------
// 小端读取工具
// ---------------------------------------------------------------------------

private fun leU16(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

private fun leU32(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

private fun leU64(data: ByteArray, offset: Int): Long =
    (leU32(data, offset).toLong() and 0xFFFFFFFFL) or (leU32(data, offset + 4).toLong() shl 32)
