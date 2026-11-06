package com.milenote.android.domain.import.file

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 钱迹 App 导出文件（`.txt` / `.json`）读取器。
 *
 * 钱迹的「导出 txt」实际是 JSON：顶层通常是账单对象数组，
 * 每个对象形如 `{"key":"qj...","date":"2026-10-21 11:22:22","category":"停车费",
 * "type":"支出","money":150.0,"currency":"CNY","remark":"..."}`。
 *
 * 输出的 [SpreadsheetData] 交给 `detectMap` / `normalizeImport` 使用：
 * - headers = 所有账单对象键的并集（按首次出现顺序，即英文 key/date/category/money/...）；
 * - rows = 按 headers 依次取值，缺失字段为 `null`；
 * - 值保留 [String] / [Double] / [Boolean] / `null`，数字不会转成 `"150.0"` 字符串。
 *
 * 兼容性：顶层数组、单个对象、以及 `{"data":[...]}` / `{"data":{"list":[...]}}`
 * 之类常见包裹；UTF-8 BOM 交给 [TextDecoder] 处理。
 */
class QianJiJsonReader(
    private val maxDataRows: Int = SpreadsheetLimits.MAX_DATA_ROWS,
) {

    /**
     * 读取钱迹 JSON 导出。
     *
     * @param fileName 仅用于 [SpreadsheetData.fileName] 展示。
     * @throws SpreadsheetReadException 文件为空/超限/不是 JSON/没有账单数据时抛出，message 可直接展示。
     */
    fun read(bytes: ByteArray, fileName: String = ""): SpreadsheetData {
        if (bytes.isEmpty()) {
            throw SpreadsheetReadException("文件是空的，请选择有效的钱迹导出文件")
        }
        if (bytes.size.toLong() > SpreadsheetLimits.MAX_FILE_BYTES) {
            throw SpreadsheetReadException("文件超过 10 MB，请拆分后再导入")
        }
        val text = TextDecoder.decode(bytes).trim()
        if (text.isEmpty()) {
            throw SpreadsheetReadException("文件是空的，请选择有效的钱迹导出文件")
        }

        val root = try {
            JSON.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            throw SpreadsheetReadException("钱迹 JSON 格式不正确，无法解析", exception)
        } catch (exception: IllegalArgumentException) {
            throw SpreadsheetReadException("钱迹 JSON 格式不正确，无法解析", exception)
        }

        val records = extractRecords(root)
        if (records.isEmpty()) {
            throw SpreadsheetReadException("钱迹 JSON 里没有可导入的账单数据")
        }
        if (records.size > maxDataRows) {
            throw SpreadsheetLimits.tooManyRows(maxDataRows, records.size)
        }

        val objects = records.mapIndexed { index, element ->
            element as? JsonObject
                ?: throw SpreadsheetReadException("钱迹 JSON 格式不正确：第 ${index + 1} 条记录不是对象")
        }
        val headers = LinkedHashSet<String>()
        objects.forEach { record -> headers.addAll(record.keys) }
        if (headers.isEmpty()) {
            throw SpreadsheetReadException("钱迹 JSON 里没有可识别的字段")
        }
        val headerList = headers.toList()
        val rows = objects.map { record ->
            headerList.map { key -> cellValue(record[key]) }
        }
        return SpreadsheetData(
            headers = headerList,
            rows = rows,
            sheetNames = listOf(SHEET_NAME),
            activeSheet = SHEET_NAME,
            fileName = fileName,
        )
    }

    /** 从任意 JSON 根节点里取出账单对象列表。 */
    private fun extractRecords(root: JsonElement): List<JsonElement> = when (root) {
        is JsonArray -> root.toList()
        is JsonObject -> when {
            // 单个账单对象：直接当一条记录。
            root.keys.any { it in RECORD_KEYS } -> listOf(root)
            else -> findRecordArray(root, depth = 0)?.toList() ?: listOf(root)
        }

        JsonNull -> emptyList()
        else -> throw SpreadsheetReadException("钱迹 JSON 格式不正确：顶层不是数组或对象")
    }

    /** 在包裹对象里递归找第一个账单数组，例如 `{"data":{"list":[...]}}`。 */
    private fun findRecordArray(element: JsonElement, depth: Int): JsonArray? {
        if (depth > MAX_WRAPPER_DEPTH || element !is JsonObject) return null
        WRAPPER_KEYS.firstNotNullOfOrNull { key -> element[key] as? JsonArray }?.let { return it }
        for (value in element.values) {
            findRecordArray(value, depth + 1)?.let { return it }
        }
        return null
    }

    private fun cellValue(element: JsonElement?): Any? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            val boolean = element.booleanOrNull
            when {
                element.isString -> element.content
                boolean != null -> boolean
                else -> element.doubleOrNull ?: element.content
            }
        }

        // 嵌套对象/数组（钱迹偶尔带标签列表等）保留成 JSON 字符串。
        else -> element.toString()
    }

    companion object {
        /** JSON 只有一张「工作表」，用这个固定名字填充 [SpreadsheetData.sheetNames]。 */
        const val SHEET_NAME: String = "钱迹"

        private val JSON = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

        private val RECORD_KEYS = setOf("key", "date", "money", "category", "type", "amount", "remark")

        private val WRAPPER_KEYS = listOf("data", "list", "records", "items", "result", "rows", "bills")

        private const val MAX_WRAPPER_DEPTH = 4
    }
}
