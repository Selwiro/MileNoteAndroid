package com.milenote.android.domain.import.file

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * .xlsx（Office Open XML）读取器。
 *
 * 实现刻意不依赖 POI：用 [ZipInputStream] 解压需要的 XML，
 * 再用 [DocumentBuilderFactory] 做 DOM 解析，保证 Android 与 JVM 单测行为一致。
 *
 * 支持的单元格类型：
 * - `t="s"` 共享字符串（含富文本 `<r><t>` 拼接）；
 * - `t="inlineStr"` 内联字符串；
 * - `t="str"` 公式的字符串结果；
 * - `t="b"` 布尔；
 * - `t="d"` ISO 日期；
 * - 默认数字（[Double]）。
 *
 * 日期单元格：样式表 `cellXfs` + `numFmtId`（内置 14–22 / 27–36 / 45–47，
 * 或自定义格式串含 y/m/d/h）识别为日期时输出 `YYYY-MM-DD` 字符串；
 * 识别不了则保留 Excel 序列号 [Double]，交给上层 `dateValue(number)` 处理。
 *
 * 多个工作表时 [read] 默认读第一个可见工作表，[readSheet] 可指定表名。
 */
class XlsxReader(
    private val maxDataRows: Int = SpreadsheetLimits.MAX_DATA_ROWS,
) {

    /**
     * 读取默认工作表（第一个可见表，没有可见表时取第一个）。
     *
     * @param fileName 仅用于 [SpreadsheetData.fileName] 展示。
     */
    fun read(bytes: ByteArray, fileName: String = ""): SpreadsheetData {
        val workbook = parseWorkbook(bytes)
        val sheet = workbook.sheets.firstOrNull { !it.hidden } ?: workbook.sheets.first()
        return readSheet(workbook, sheet, fileName)
    }

    /**
     * 读取指定名称的工作表；名称精确匹配失败时再做一次忽略大小写匹配。
     *
     * @throws SpreadsheetReadException 找不到该工作表时抛出。
     */
    fun readSheet(bytes: ByteArray, sheetName: String, fileName: String = ""): SpreadsheetData {
        val workbook = parseWorkbook(bytes)
        val sheet = workbook.sheets.firstOrNull { it.name == sheetName }
            ?: workbook.sheets.firstOrNull { it.name.equals(sheetName, ignoreCase = true) }
            ?: throw SpreadsheetReadException("找不到工作表「$sheetName」")
        return readSheet(workbook, sheet, fileName)
    }

    /** 只读取工作簿里的工作表名，不解析单元格。 */
    fun sheetNames(bytes: ByteArray): List<String> = parseWorkbook(bytes).sheets.map { it.name }

    // ---- 工作簿解析 ----

    private fun parseWorkbook(bytes: ByteArray): ParsedWorkbook {
        if (!looksLikeZip(bytes)) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xlsx 文件已损坏或格式不正确）")
        }
        val entries = readZipEntries(bytes)
        val workbookPath = entries.keys.firstOrNull { it.equals("xl/workbook.xml", ignoreCase = true) }
            ?: entries.keys.firstOrNull { it.endsWith("/workbook.xml", ignoreCase = true) }
            ?: throw SpreadsheetReadException("文件不是有效的 Excel 表格：缺少 workbook.xml")

        val workbookDocument = parseXml(entries.getValue(workbookPath), "workbook.xml")
        val workbookDir = workbookPath.substringBeforeLast('/', "")
        val relsPath = if (workbookDir.isEmpty()) {
            "_rels/${workbookPath.substringAfterLast('/')}.rels"
        } else {
            "$workbookDir/_rels/${workbookPath.substringAfterLast('/')}.rels"
        }
        val relationships = entries[relsPath]
            ?.let { parseRelationships(parseXml(it, "workbook.xml.rels")) }
            .orEmpty()

        val sharedStringsPath = resolveRelatedTarget(workbookDir, entries, relationships, "/sharedStrings")
            ?: entries.keys.firstOrNull { it.endsWith("/sharedStrings.xml", ignoreCase = true) }
        val sharedStrings = sharedStringsPath
            ?.let { parseSharedStrings(parseXml(entries.getValue(it), "sharedStrings.xml")) }
            .orEmpty()

        val stylesPath = resolveRelatedTarget(workbookDir, entries, relationships, "/styles")
            ?: entries.keys.firstOrNull { it.endsWith("/styles.xml", ignoreCase = true) }
        val dateStyles = stylesPath
            ?.let { parseDateStyles(parseXml(entries.getValue(it), "styles.xml")) }
            ?: BooleanArray(0)

        val workbookPr = workbookDocument.documentElement?.firstChildElement("workbookPr")
        val date1904 = workbookPr?.getAttribute("date1904").let {
            it == "1" || it.equals("true", ignoreCase = true)
        }

        val sheetsElement = workbookDocument.documentElement?.firstChildElement("sheets")
        val sheetElements = sheetsElement?.childElements("sheet").orEmpty()
        if (sheetElements.isEmpty()) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格：工作簿里没有工作表")
        }
        val sheets = sheetElements.mapIndexed { index, element ->
            val state = element.getAttribute("state").lowercase()
            SheetRef(
                name = element.getAttribute("name").ifBlank { "Sheet${index + 1}" },
                hidden = state == "hidden" || state == "veryhidden",
                path = resolveSheetPath(
                    workbookPath = workbookPath,
                    entries = entries,
                    relationships = relationships,
                    relationId = element.attributeByLocalName("id"),
                    index = index,
                ),
            )
        }
        return ParsedWorkbook(
            entries = entries,
            sheets = sheets,
            sharedStrings = sharedStrings,
            dateStyles = dateStyles,
            date1904 = date1904,
        )
    }

    private fun readSheet(workbook: ParsedWorkbook, sheet: SheetRef, fileName: String): SpreadsheetData {
        val path = sheet.path
            ?: throw SpreadsheetReadException("文件不是有效的 Excel 表格：找不到工作表「${sheet.name}」的数据")
        val document = parseXml(workbook.entries.getValue(path), "工作表「${sheet.name}」")
        val sheetData = document.getElementsByTagNameNS("*", "sheetData").item(0) as? Element
        val grid = ArrayList<List<Any?>>()
        var headerSeen = false
        var dataRowCount = 0
        if (sheetData != null) {
            for (rowElement in sheetData.childElements("row")) {
                val row = parseRow(rowElement, workbook)
                if (row.any(SpreadsheetGrid::isNonBlank)) {
                    if (headerSeen) {
                        dataRowCount++
                        if (dataRowCount > maxDataRows) throw SpreadsheetLimits.tooManyRows(maxDataRows)
                    } else {
                        headerSeen = true
                    }
                }
                grid.add(row)
                if (grid.size > maxDataRows * 4 + 10_000) {
                    // 大量显式空行也视为异常文件，避免 DOM 之外再堆积内存。
                    throw SpreadsheetLimits.tooManyRows(maxDataRows)
                }
            }
        }
        return SpreadsheetGrid.normalize(
            grid = grid,
            sheetNames = workbook.sheets.map { it.name },
            activeSheet = sheet.name,
            fileName = fileName,
            maxDataRows = maxDataRows,
        )
    }

    private fun parseRow(rowElement: Element, workbook: ParsedWorkbook): List<Any?> {
        val cellElements = rowElement.childElements("c")
        if (cellElements.isEmpty()) return emptyList()
        val values = HashMap<Int, Any?>()
        var maxIndex = -1
        var fallbackIndex = 0
        for (cellElement in cellElements) {
            val reference = cellElement.getAttribute("r")
            val columnIndex = if (reference.isNotEmpty()) {
                columnIndexFromReference(reference) ?: fallbackIndex
            } else {
                fallbackIndex
            }
            fallbackIndex = columnIndex + 1
            val value = parseCell(cellElement, workbook)
            if (value != null) {
                values[columnIndex] = value
                if (columnIndex > maxIndex) maxIndex = columnIndex
            }
        }
        if (maxIndex < 0) return emptyList()
        return List<Any?>(maxIndex + 1) { index -> values[index] }
    }

    private fun parseCell(cellElement: Element, workbook: ParsedWorkbook): Any? {
        val type = cellElement.getAttribute("t").lowercase()
        val styleIndex = cellElement.getAttribute("s").toIntOrNull() ?: 0
        val rawValue = cellElement.firstChildElement("v")?.textContent?.trim().orEmpty()
        return when (type) {
            "s" -> {
                val sharedIndex = rawValue.toIntOrNull() ?: return null
                workbook.sharedStrings.getOrNull(sharedIndex)
                    ?: throw SpreadsheetReadException("Excel 文件内容损坏：共享字符串索引越界（$sharedIndex）")
            }

            "inlinestr" -> {
                val inline = cellElement.firstChildElement("is") ?: return null
                collectText(inline)
            }

            "str" -> rawValue.ifEmpty { null }
            "b" -> when (rawValue) {
                "1", "true", "TRUE" -> true
                "0", "false", "FALSE" -> false
                else -> null
            }

            "e" -> rawValue.ifEmpty { null }
            "d" -> isoDateText(rawValue)
            else -> numericCell(rawValue, styleIndex, workbook)
        }
    }

    private fun numericCell(rawValue: String, styleIndex: Int, workbook: ParsedWorkbook): Any? {
        if (rawValue.isEmpty()) return null
        val number = rawValue.toDoubleOrNull() ?: return rawValue
        return if (workbook.dateStyles.getOrElse(styleIndex) { false }) {
            excelSerialToDateText(number, workbook.date1904)
        } else {
            number
        }
    }

    // ---- 样式与日期 ----

    private fun parseDateStyles(document: Document): BooleanArray {
        val customFormats = HashMap<Int, String>()
        val numFmtNodes = document.getElementsByTagNameNS("*", "numFmt")
        for (index in 0 until numFmtNodes.length) {
            val element = numFmtNodes.item(index) as? Element ?: continue
            val id = element.getAttribute("numFmtId").toIntOrNull() ?: continue
            customFormats[id] = element.getAttribute("formatCode")
        }
        val cellXfs = document.getElementsByTagNameNS("*", "cellXfs").item(0) as? Element
            ?: return BooleanArray(0)
        return cellXfs.childElements("xf").map { xf ->
            val numFmtId = xf.getAttribute("numFmtId").toIntOrNull() ?: 0
            isBuiltInDateFormat(numFmtId) || customFormats[numFmtId]?.let(::isDateFormatCode) == true
        }.toBooleanArray()
    }

    private fun isBuiltInDateFormat(numFmtId: Int): Boolean =
        numFmtId in 14..22 || numFmtId in 27..36 || numFmtId in 45..47

    private fun isDateFormatCode(formatCode: String): Boolean {
        if (formatCode.isEmpty()) return false
        val cleaned = StringBuilder()
        var index = 0
        var inQuote = false
        while (index < formatCode.length) {
            val char = formatCode[index]
            when {
                inQuote -> if (char == '"') inQuote = false
                char == '"' -> inQuote = true
                char == '\\' -> index++ // 转义的下一个字符是字面量
                char == '_' || char == '*' -> index++ // 占位/填充符，下一个字符是字面量
                char == '[' -> while (index < formatCode.length && formatCode[index] != ']') index++
                else -> cleaned.append(char)
            }
            index++
        }
        return cleaned.any { char ->
            char == 'y' || char == 'Y' ||
                char == 'm' || char == 'M' ||
                char == 'd' || char == 'D' ||
                char == 'h' || char == 'H'
        }
    }

    private fun excelSerialToDateText(serial: Double, date1904: Boolean): String {
        val days = kotlin.math.floor(serial).toLong()
        val date = if (date1904) {
            LocalDate.of(1904, 1, 1).plusDays(days)
        } else if (days < 60) {
            // Excel 1900 日期系统把 1900-02-29 当成存在的一天（serial=60）。
            LocalDate.of(1899, 12, 31).plusDays(days)
        } else {
            LocalDate.of(1899, 12, 30).plusDays(days)
        }
        return date.toString()
    }

    private fun isoDateText(rawValue: String): String? {
        if (rawValue.isEmpty()) return null
        return runCatching { LocalDate.parse(rawValue.take(10)).toString() }.getOrElse { rawValue }
    }

    // ---- zip ----

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val third = bytes[2].toInt() and 0xFF
        val fourth = bytes[3].toInt() and 0xFF
        return (third == 3 && fourth == 4) || (third == 5 && fourth == 6) || (third == 7 && fourth == 8)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.removePrefix("./").removePrefix("/")
                    if (!isWantedEntry(name)) continue
                    if (entry.size > SpreadsheetLimits.MAX_ENTRY_BYTES) {
                        throw SpreadsheetReadException("Excel 文件解压后体积过大，无法导入")
                    }
                    val data = readEntry(zip)
                    totalBytes += data.size
                    if (totalBytes > SpreadsheetLimits.MAX_UNCOMPRESSED_BYTES) {
                        throw SpreadsheetReadException("Excel 文件解压后体积过大，无法导入")
                    }
                    entries[name] = data
                }
            }
        } catch (exception: SpreadsheetReadException) {
            throw exception
        } catch (exception: Exception) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格（.xlsx 压缩包无法读取）", exception)
        }
        if (entries.isEmpty()) {
            throw SpreadsheetReadException("文件不是有效的 Excel 表格：压缩包里没有表格数据")
        }
        return entries
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            if (output.size() > SpreadsheetLimits.MAX_ENTRY_BYTES) {
                throw SpreadsheetReadException("Excel 文件解压后体积过大，无法导入")
            }
        }
        return output.toByteArray()
    }

    private fun isWantedEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "xl/workbook.xml" ||
            lower.endsWith("/workbook.xml") ||
            lower.endsWith("/workbook.xml.rels") ||
            lower.endsWith("/sharedstrings.xml") ||
            lower.endsWith("/styles.xml") ||
            (lower.contains("/worksheets/") && lower.endsWith(".xml"))
    }

    // ---- 关系与路径 ----

    private fun parseRelationships(document: Document): List<Relationship> {
        val nodes = document.getElementsByTagNameNS("*", "Relationship")
        return (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as? Element ?: return@mapNotNull null
            Relationship(
                id = element.getAttribute("Id"),
                type = element.getAttribute("Type"),
                target = element.getAttribute("Target"),
            )
        }
    }

    private fun resolveRelatedTarget(
        workbookDir: String,
        entries: Map<String, ByteArray>,
        relationships: List<Relationship>,
        typeSuffix: String,
    ): String? {
        val target = relationships.firstOrNull { it.type.endsWith(typeSuffix, ignoreCase = true) }?.target
            ?: return null
        val resolved = resolvePath(workbookDir, target)
        return resolved.takeIf { entries.containsKey(it) }
    }

    private fun resolveSheetPath(
        workbookPath: String,
        entries: Map<String, ByteArray>,
        relationships: List<Relationship>,
        relationId: String,
        index: Int,
    ): String? {
        val workbookDir = workbookPath.substringBeforeLast('/', "")
        val target = relationships.firstOrNull { it.id == relationId }?.target
        if (!target.isNullOrBlank()) {
            val resolved = resolvePath(workbookDir, target)
            if (entries.containsKey(resolved)) return resolved
        }
        val conventional = resolvePath(workbookDir, "worksheets/sheet${index + 1}.xml")
        if (entries.containsKey(conventional)) return conventional
        return entries.keys
            .filter { it.contains("/worksheets/") && it.endsWith(".xml", ignoreCase = true) }
            .sorted()
            .getOrNull(index)
    }

    private fun resolvePath(baseDir: String, target: String): String {
        val raw = when {
            target.startsWith("/") -> target
            baseDir.isEmpty() -> target
            else -> "$baseDir/$target"
        }
        val parts = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    // ---- XML 辅助 ----

    private fun parseXml(bytes: ByteArray, description: String): Document {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isExpandEntityReferences = false
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (exception: Exception) {
            throw SpreadsheetReadException("Excel 文件内容损坏（$description 解析失败）", exception)
        }
    }

    private fun parseSharedStrings(document: Document): List<String> {
        val nodes = document.getElementsByTagNameNS("*", "si")
        return (0 until nodes.length).map { index -> collectText(nodes.item(index) as Element) }
    }

    private fun collectText(element: Element): String {
        val builder = StringBuilder()
        appendText(element, builder)
        return builder.toString()
    }

    private fun appendText(element: Element, builder: StringBuilder) {
        var child = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> builder.append(child.nodeValue)
                Node.ELEMENT_NODE -> {
                    val childElement = child as Element
                    val name = localName(childElement)
                    // rPh 是日文注音，不属于单元格文本。
                    if (name != "rPh" && name != "phoneticPr") appendText(childElement, builder)
                }
            }
            child = child.nextSibling
        }
    }

    private fun Element.childElements(localName: String? = null): List<Element> {
        val result = ArrayList<Element>()
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localName == null || localName(element) == localName) result.add(element)
            }
            child = child.nextSibling
        }
        return result
    }

    private fun Element.firstChildElement(localName: String): Element? {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localName(element) == localName) return element
            }
            child = child.nextSibling
        }
        return null
    }

    private fun Element.attributeByLocalName(localName: String): String {
        if (hasAttribute(localName)) return getAttribute(localName)
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = attribute.localName ?: attribute.nodeName.substringAfterLast(':')
            if (name == localName) return attribute.nodeValue ?: ""
        }
        return ""
    }

    private fun localName(element: Element): String =
        element.localName ?: element.nodeName.substringAfterLast(':')

    private fun columnIndexFromReference(reference: String): Int? {
        var index = 0
        var seenLetter = false
        for (char in reference) {
            val value = when (char) {
                in 'A'..'Z' -> char - 'A' + 1
                in 'a'..'z' -> char - 'a' + 1
                else -> break
            }
            index = index * 26 + value
            seenLetter = true
        }
        return if (seenLetter) index - 1 else null
    }

    private data class ParsedWorkbook(
        val entries: Map<String, ByteArray>,
        val sheets: List<SheetRef>,
        val sharedStrings: List<String>,
        val dateStyles: BooleanArray,
        val date1904: Boolean,
    )

    private data class SheetRef(
        val name: String,
        val hidden: Boolean,
        val path: String?,
    )

    private data class Relationship(
        val id: String,
        val type: String,
        val target: String,
    )
}
