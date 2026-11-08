package com.milenote.android.ui.screens.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.BackupException
import com.milenote.android.domain.backup.BackupPayload
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.import.FIELDS
import com.milenote.android.domain.import.file.SpreadsheetData
import com.milenote.android.domain.import.file.SpreadsheetFiles
import com.milenote.android.domain.import.file.SpreadsheetLimits
import com.milenote.android.domain.import.file.SpreadsheetReadException
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

// ---------------------------------------------------------------------------------------------
// 来源
// ---------------------------------------------------------------------------------------------

/** 导入向导的来源：账本备份 JSON / 小熊 / 钱迹。 */
internal enum class ImportSource(val key: String) {
    BACKUP(key = "backup"),
    BEAR(key = "bear"),
    QIANJI(key = "qianji"),
    ;

    /** 用户可见的来源名（随语言切换）。 */
    val title: String
        get() = when (this) {
            BACKUP -> tr("账本备份 JSON", "Ledger backup JSON")
            BEAR -> tr("小熊油耗 XLS / XLSX", "Bear XLS / XLSX")
            QIANJI -> tr("钱迹 TXT / JSON / CSV", "Qianji TXT / JSON / CSV")
        }

    /** 用户可见的来源说明（随语言切换）。 */
    val description: String
        get() = when (this) {
            BACKUP -> tr(
                "恢复完整备份（version 2 或旧版单车）",
                "Restore a full backup (version 2 or legacy single-car)",
            )
            BEAR -> tr(
                "加油记录 → 油费，按满箱法统计",
                "Refuel records → fuel expenses, counted with the full-tank method",
            )
            QIANJI -> tr(
                "支出记录 → 费用，可自动完成保养待办",
                "Expense records → expenses; can auto-complete service to-dos",
            )
        }

    companion object {
        /** 接受 `bear/qianji/backup` 及其常见别名；未知返回 null。 */
        fun fromKey(value: String?): ImportSource? {
            val key = value?.trim()?.lowercase() ?: return null
            return when (key) {
                "backup", "json", "restore", "ledger_backup", "账本备份" -> BACKUP
                "bear", "xiaoxiong", "fuel", "小熊", "小熊油耗" -> BEAR
                "qianji", "qianji_records", "钱迹", "钱迹记账" -> QIANJI
                else -> entries.firstOrNull { it.key == key }
            }
        }
    }
}

/**
 * 「手动选择来源」列表：只列小熊 / 钱迹。
 *
 * 账本备份（[ImportSource.BACKUP]）枚举保留，仅用于系统分享 JSON 的自动识别，
 * 不出现在手动来源列表里。
 */
internal val MANUAL_IMPORT_SOURCES: List<ImportSource> = listOf(
    ImportSource.BEAR,
    ImportSource.QIANJI,
)

/** 备份文件上限（Web 恢复备份 20 MB）。 */
internal const val BACKUP_MAX_BYTES: Long = 20L * 1024 * 1024

/** 表格文件上限（与 domain `SpreadsheetLimits.MAX_FILE_BYTES` 一致）。 */
internal val TABLE_MAX_BYTES: Long = SpreadsheetLimits.MAX_FILE_BYTES

/** 预览最多渲染的行数（其余只提示数量，避免长列表卡顿）。 */
internal const val PREVIEW_ROW_LIMIT: Int = 200

// ---------------------------------------------------------------------------------------------
// 值对象
// ---------------------------------------------------------------------------------------------

/** 已选文件（字节保留在内存，用于改映射/换工作表时重新解析）。 */
internal data class PickedFile(
    val name: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

/** 备份 JSON 的恢复前摘要。 */
internal data class BackupPreview(
    val version: Int,
    val cars: Int,
    val records: Int,
    val reminders: Int,
)

/** 导入结果态（步骤 3）。 */
internal data class WizardResult(
    val title: String,
    val lines: List<String>,
    val note: String = "",
)

/** 可读的导入/读取错误（message 直接展示给用户）。 */
internal class ImportFileException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

// ---------------------------------------------------------------------------------------------
// 字段标签（对齐 Web fieldNames）
// ---------------------------------------------------------------------------------------------

/** 费用导入字段标签，顺序与 Web `fieldNames` 一致（随语言切换，故用函数）。 */
internal fun importFieldLabels(): Map<String, String> = linkedMapOf(
    "projects" to tr("项目（保养 / 维修 / 美容）", "Items (service / repair / detailing)"),
    "location" to tr("地点 / 门店", "Location / shop"),
    "date" to tr("日期 *", "Date *"),
    "category" to tr("费用分类", "Expense category"),
    "amount" to tr("实付金额 *", "Amount paid *"),
    "payable" to tr("应付金额", "Amount due"),
    "discount" to tr("优惠金额", "Discount"),
    "unitPrice" to tr("油价（元/升）", "Fuel price (CNY/L)"),
    "fuelGrade" to tr("燃油标号", "Fuel grade"),
    "odometer" to tr("总里程（公里）", "Odometer (km)"),
    "liters" to tr("加油量（升）", "Fuel amount (L)"),
    "full" to tr("是否加满", "Full tank"),
    "missed" to tr("是否漏记", "Missed entry"),
    "rate" to tr("原始 / 手填油耗", "Original / manual fuel economy"),
    "distance" to tr("手填油耗对应里程", "Mileage for manual fuel economy"),
    "note" to tr("备注", "Note"),
    "type" to tr("收支类型", "Income / expense type"),
    "sourceId" to tr("记录唯一编号", "Record unique ID"),
    "currency" to tr("币种", "Currency"),
)

// ---------------------------------------------------------------------------------------------
// 文件读取
// ---------------------------------------------------------------------------------------------

/** 查询 SAF 文件的展示名；失败时回退 `lastPathSegment`。 */
internal fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}

/** 查询 SAF 文件大小；未知返回 null。 */
internal fun queryFileSize(context: Context, uri: Uri): Long? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
    }.getOrNull()
}

/**
 * 读取 URI 字节，超过 [maxBytes] 时抛出可读错误（不把超大文件读满内存）。
 * 调用方需在 IO 线程执行。
 */
internal fun readUriBytes(context: Context, uri: Uri, maxBytes: Long, limitMessage: String): ByteArray {
    val resolver = context.contentResolver
    val knownSize = queryFileSize(context, uri)
    if (knownSize != null && knownSize > maxBytes) throw ImportFileException(limitMessage)
    val input = resolver.openInputStream(uri)
        ?: throw ImportFileException(tr("无法打开所选文件，请重新选择", "Could not open the selected file — pick it again"))
    input.use { return readLimited(it, maxBytes, limitMessage) }
}

private fun readLimited(input: InputStream, maxBytes: Long, limitMessage: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw ImportFileException(limitMessage)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/** 文件大小展示：`860 B` / `12.3 KB` / `1.2 MB`。 */
internal fun displaySize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 读取表格文件；钱迹 JSON 文本在 domain 读取器尚未覆盖时使用本地兜底解析。 */
internal fun readSpreadsheet(
    bytes: ByteArray,
    fileName: String,
    allowJsonFallback: Boolean,
): SpreadsheetData {
    return try {
        SpreadsheetFiles().read(bytes, fileName)
    } catch (e: SpreadsheetReadException) {
        if (allowJsonFallback && looksLikeJson(bytes)) {
            parseJsonSpreadsheet(decodeText(bytes))
        } else {
            throw e
        }
    }
}

/** 读取指定工作表；CSV/JSON 忽略工作表名。 */
internal fun readSpreadsheetSheet(
    bytes: ByteArray,
    fileName: String,
    sheetName: String,
    allowJsonFallback: Boolean,
): SpreadsheetData {
    return try {
        SpreadsheetFiles().readSheet(bytes, fileName, sheetName)
    } catch (e: SpreadsheetReadException) {
        if (allowJsonFallback && looksLikeJson(bytes)) {
            parseJsonSpreadsheet(decodeText(bytes))
        } else {
            throw e
        }
    }
}

/** 读取工作簿工作表名列表；解析失败时返回空列表。 */
internal fun readSheetNames(bytes: ByteArray, fileName: String): List<String> {
    return runCatching { SpreadsheetFiles().sheetNames(bytes, fileName) }.getOrDefault(emptyList())
}

/** UTF-8 解码并去掉 BOM（钱迹导出常见 BOM）。 */
internal fun decodeText(bytes: ByteArray): String =
    bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")

/** 是否像 JSON（忽略 BOM 与空白）。 */
internal fun looksLikeJson(bytes: ByteArray): Boolean {
    val text = decodeText(bytes).trim()
    if (text.length < 2) return false
    return (text.first() == '[' && text.last() == ']') ||
        (text.first() == '{' && text.last() == '}')
}

/**
 * 钱迹 JSON → 表格中间表示（Web `readImport` 的 JSON 分支）：
 * 顶层数组，或对象里的 `records / data / list` 数组；表头取所有对象键的并集。
 */
internal fun parseJsonSpreadsheet(text: String): SpreadsheetData {
    val element = try {
        Json.parseToJsonElement(text.trim())
    } catch (e: Exception) {
        throw ImportFileException(
            tr(
                "JSON 账单解析失败：%s",
                "Failed to parse the JSON ledger: %s",
                e.message ?: tr("格式不正确", "invalid format"),
            ),
            e,
        )
    }
    val objects: List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val array = element["records"] ?: element["data"] ?: element["list"]
            (array as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        }
        else -> emptyList()
    }
    if (objects.isEmpty()) throw ImportFileException(tr("未找到钱迹记录数组", "No Qianji record array found"))
    val headers = LinkedHashSet<String>()
    objects.forEach { headers.addAll(it.keys) }
    val headerList = headers.toList()
    val rows = objects.map { obj -> headerList.map { key -> jsonCell(obj[key]) } }
    return SpreadsheetData(
        headers = headerList,
        rows = rows,
        sheetNames = listOf("JSON"),
        activeSheet = "JSON",
        fileName = "",
    )
}

private fun jsonCell(element: JsonElement?): Any? = when (element) {
    null, JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull == true
        else -> element.content.toDoubleOrNull() ?: element.content
    }
    else -> element.toString()
}

// ---------------------------------------------------------------------------------------------
// 备份预览
// ---------------------------------------------------------------------------------------------

/**
 * 只读校验备份文本并统计数量：非法备份会抛 [BackupException]（中文文案），
 * 与 `vm.restoreBackup` 使用同一套 domain 校验，提前暴露错误。
 */
internal fun inspectBackup(text: String): BackupPreview {
    val payload = BackupCodec.decodePayload(text)
    val garage = BackupCodec.decode(text)
    return BackupPreview(
        version = if (payload is BackupPayload.MulticarGarage) 2 else 1,
        cars = garage.cars.size,
        records = garage.cars.sumOf { it.records.size },
        reminders = garage.cars.sumOf { it.reminders.size },
    )
}

// ---------------------------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------------------------

/** 默认费用分类选项：空值=跳过 + 9 类 + 本车自定义/已出现分类。 */
internal fun defaultCategoryOptions(ledger: com.milenote.android.domain.model.Ledger?): List<Pair<String, String>> {
    val options = mutableListOf("" to tr("跳过未识别分类", "Skip unrecognized categories"))
    val names = ledger?.let { Categories.options(it) } ?: Categories.ALL
    names.forEach { name -> options += name to DataLabels.category(name) }
    return options
}

/** 记录来源展示名（账本里存的是中文来源值，只在显示层映射）。 */
internal fun importSourceLabel(source: String?): String {
    val raw = source.orEmpty()
    return when (raw) {
        "小熊油耗" -> tr("小熊油耗", "Bear")
        "钱迹", "钱迹记账" -> tr("钱迹记账", "Qianji")
        else -> raw
    }
}

/** 金额格式化（与 Web `money()` 一致）。 */
internal fun moneyText(amount: Double?): String = Format.money(amount)

/** 表头列展示名：空表头回退「第 N 列」。 */
internal fun headerLabel(headers: List<String>, index: Int): String {
    val header = headers.getOrNull(index)
    return if (header.isNullOrBlank()) tr("第 %d 列", "Column %d", index + 1) else header
}

/** 费用导入字段键顺序（与 Web fieldNames 一致）。 */
internal val IMPORT_FIELD_ORDER: List<String> = FIELDS.keys.toList()
