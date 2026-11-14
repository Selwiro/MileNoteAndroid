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
import com.milenote.android.i18n.Units
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

/** 导入向导的来源：账本备份 JSON / 小熊 / 钱迹 / 里程志 CSV。 */
internal enum class ImportSource(val key: String) {
    BACKUP(key = "backup"),
    BEAR(key = "bear"),
    QIANJI(key = "qianji"),
    CSV(key = "milenote"),
    ;

    /** 用户可见的来源名（随语言切换）。 */
    val title: String
        get() = when (this) {
            BACKUP -> tr("完整备份 JSON", "Full backup JSON")
            BEAR -> tr("小熊油耗 XLS", "Bear XLS")
            QIANJI -> tr("钱迹 XLSX / JSON", "Qianji XLSX / JSON")
            CSV -> tr("里程志 CSV（回灌）", "MileNote CSV (write-back)")
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
            CSV -> tr(
                "导出的费用 CSV 改完再导入，同编号记录原位更新",
                "Re-import an edited expense CSV; same-ID records are updated in place",
            )
        }

    companion object {
        /** 接受 `bear/qianji/backup` 及其常见别名；未知返回 null。 */
        fun fromKey(value: String?): ImportSource? {
            val key = value?.trim()?.lowercase() ?: return null
            return when (key) {
                "backup", "json", "restore", "ledger_backup", "账本备份", "完整备份" -> BACKUP
                "bear", "xiaoxiong", "fuel", "小熊", "小熊油耗" -> BEAR
                "qianji", "qianji_records", "钱迹", "钱迹记账" -> QIANJI
                "milenote", "csv", "回灌", "里程志", "里程志 csv" -> CSV
                else -> entries.firstOrNull { it.key == key }
            }
        }
    }
}

/**
 * 「手动选择来源」列表：里程志 CSV（回灌）/ 小熊 / 钱迹。
 *
 * 账本备份（[ImportSource.BACKUP]）枚举保留，仅用于系统分享 JSON 的自动识别，
 * 不出现在手动来源列表里。
 */
internal val MANUAL_IMPORT_SOURCES: List<ImportSource> = listOf(
    ImportSource.CSV,
    ImportSource.BEAR,
    ImportSource.QIANJI,
)

/**
 * 系统分享（ACTION_SEND）进来的文件 → 来源识别（纯函数，便于单测）。
 *
 * 顺序：里程志 CSV（有「记录编号」，或「来源 + 分类 + 日期」）→ 小熊（总里程 / 加油量 / 日期时间）
 * → 钱迹（时间 / 类型 / 金额）→ 文件名与 MIME 兜底 → 默认钱迹（界面可切回来源选择）。
 */
internal fun detectSharedImportSource(
    fileName: String,
    headers: List<String>,
    mimeType: String?,
): ImportSource {
    val trimmedHeaders = headers.map { it.trim() }
    // 里程志 CSV 先判：它同样有「总里程 / 加油量」，靠「记录编号 / 来源」区分。
    if (trimmedHeaders.any { it == "记录编号" } ||
        (trimmedHeaders.contains("来源") && trimmedHeaders.contains("分类") && trimmedHeaders.contains("日期"))
    ) {
        return ImportSource.CSV
    }
    if (trimmedHeaders.any { it == "总里程" || it == "加油量" || it == "日期时间" }) {
        return ImportSource.BEAR
    }
    if (trimmedHeaders.any { it == "时间" || it == "类型" || it == "金额" }) {
        return ImportSource.QIANJI
    }
    val lowerName = fileName.lowercase()
    val mime = mimeType.orEmpty().lowercase()
    if (lowerName.contains("小熊") || lowerName.endsWith(".xls") || mime.contains("vnd.ms-excel")) {
        return ImportSource.BEAR
    }
    if (lowerName.contains("钱迹") || lowerName.contains("qianji") ||
        lowerName.endsWith(".json") || mime.contains("json") || mime.contains("csv")
    ) {
        return ImportSource.QIANJI
    }
    // 无法判断时默认钱迹，界面可切回来源选择。
    return ImportSource.QIANJI
}

/**
 * 「手动选择来源」列表（按动力类型过滤）。
 *
 * 纯电当前车不提供小熊油耗：小熊是加油记录（升/满箱法），对纯电无意义；
 * 钱迹 / 账本备份不受影响。
 */
internal fun manualImportSources(ev: Boolean): List<ImportSource> =
    if (ev) MANUAL_IMPORT_SOURCES.filter { it != ImportSource.BEAR } else MANUAL_IMPORT_SOURCES

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

/**
 * 费用导入字段标签，顺序与 Web `fieldNames` 一致（随语言切换，故用函数）。
 *
 * @param ev 当前车是否纯电：纯电用「电价 / 充电量 / 能耗」措辞，并隐藏燃油专属的标号 / 加油量；
 *   燃油保持原有文案逐字不变（充电量 `kwh` 字段是纯电专属，燃油不展示）。
 */
internal fun importFieldLabels(ev: Boolean = false): Map<String, String> {
    val labels = linkedMapOf(
        "projects" to tr("项目（保养 / 维修 / 美容）", "Items (service / repair / detailing)"),
        "location" to tr("地点 / 门店", "Location / shop"),
        "date" to tr("日期 *", "Date *"),
        "category" to tr("费用分类", "Expense category"),
        "amount" to tr("实付金额 *", "Amount paid *"),
        "payable" to tr("应付金额", "Amount due"),
        "discount" to tr("优惠金额", "Discount"),
        "unitPrice" to tr("油价（%s）", "Fuel price (%s)", Units.pricePerVolume),
        "fuelGrade" to tr("燃油标号", "Fuel grade"),
        "odometer" to tr("总里程（%s）", "Odometer (%s)", Units.distanceUnit),
        "liters" to tr("加油量（%s）", "Fuel amount (%s)", Units.volumeUnit),
        "full" to tr("是否加满", "Full tank"),
        "missed" to tr("是否漏记", "Missed entry"),
        "rate" to tr("原始 / 手填油耗", "Original / manual fuel economy"),
        "distance" to tr("手填油耗对应里程", "Mileage for manual fuel economy"),
        "note" to tr("备注", "Note"),
        "type" to tr("收支类型", "Income / expense type"),
        "sourceId" to tr("记录唯一编号", "Record unique ID"),
        "currency" to tr("币种", "Currency"),
    )
    if (ev) {
        labels.remove("fuelGrade")
        labels.remove("liters")
        labels["unitPrice"] = tr("电价（%s）", "Price (%s)", Units.pricePerEnergy)
        labels["kwh"] = tr("充电量（%s）", "Charge amount (%s)", Units.energyUnit)
        labels["full"] = tr("是否充满", "Full charge")
        labels["rate"] = tr("原始 / 手填能耗", "Original / manual energy consumption")
        labels["distance"] = tr("手填能耗对应里程", "Mileage for manual energy consumption")
    }
    return labels
}

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

/**
 * 来源允许的导入格式校验（按内容判定，不只看扩展名）：
 * - 小熊油耗：只接受 Excel 97-2003 工作簿（OLE2/BIFF8 的 `.xls`）；
 * - 钱迹：接受 XLSX 工作簿，或内容是 JSON 的文本（钱迹的「JSON 导出」也可能存成 `.txt`）；
 * - 账本备份：格式由 [inspectBackup] 校验，这里不限制。
 *
 * @return 可直接展示给用户的错误文案；null 表示通过。
 */
internal fun sourceFileError(source: ImportSource, bytes: ByteArray): String? = when (source) {
    ImportSource.BACKUP -> null

    ImportSource.BEAR -> if (isOle2Signature(bytes)) {
        null
    } else {
        tr(
            "小熊油耗只支持 Excel 97-2003 工作簿（.xls），当前文件不是有效的 .xls；请在小熊油耗里导出 Excel 后重试",
            "Bear imports accept only Excel 97-2003 workbooks (.xls). This file isn't a valid .xls — export Excel from Bear and try again",
        )
    }

    ImportSource.QIANJI -> if (isXlsxSignature(bytes) || looksLikeJson(bytes)) {
        null
    } else {
        tr(
            "钱迹只支持 XLSX 或 JSON 文件，当前文件不是这两种格式；请在钱迹里导出 Excel 或 JSON 后重试",
            "Qianji imports accept only XLSX or JSON files. This file is neither — export Excel or JSON from Qianji and try again",
        )
    }

    ImportSource.CSV -> when {
        isXlsxSignature(bytes) || isOle2Signature(bytes) -> tr(
            "里程志 CSV 只接受文本表格（.csv / .tsv）：请先在「我的 → 备份与还原」里导出费用 CSV，改完再导入",
            "MileNote CSV accepts text tables only (.csv / .tsv). Export the expense CSV from " +
                "Me → Backup & restore, edit it, then import it here",
        )
        !looksLikeMileNoteCsv(bytes) -> tr(
            "这不是里程志导出的费用 CSV（缺少「日期 / 分类 / 金额」表头）；请先导出 CSV 再修改导入",
            "This isn't a MileNote expense CSV (missing Date / Category / Amount headers). " +
                "Export the CSV first, then edit and import it",
        )
        else -> null
    }
}

/**
 * 里程志 CSV 表头校验（读取后按列名判定，扩展名不可信）。
 *
 * @return 可直接展示的错误文案；null 表示通过。
 */
internal fun mileNoteCsvHeaderError(headers: List<String>): String? {
    val names = headers.map { it.trim() }.toSet()
    val missing = buildList {
        if ("日期" !in names) add(tr("日期", "Date"))
        if ("分类" !in names) add(tr("分类", "Category"))
        if ("金额" !in names) add(tr("金额", "Amount"))
    }
    if (missing.isNotEmpty()) {
        return tr(
            "里程志 CSV 缺少列：%s；请用「备份与还原 → 导出费用 CSV」生成的文件",
            "The MileNote CSV is missing these columns: %s — use the file from " +
                "Backup & restore → Export expense CSV",
            missing.joinToString(tr("、", ", ")),
        )
    }
    return null
}

/** 文本前缀是否像里程志费用 CSV（BOM + 表头一定在开头，只解前 4 KB）。 */
private fun looksLikeMileNoteCsv(bytes: ByteArray): Boolean {
    val prefix = decodeText(bytes.copyOfRange(0, minOf(bytes.size, 4096)))
    return prefix.contains("日期") && prefix.contains("分类") && prefix.contains("金额")
}

/** XLSX（ZIP）文件头 `PK\x03\x04`。 */
private fun isXlsxSignature(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4B.toByte() &&
        bytes[2] == 0x03.toByte() &&
        bytes[3] == 0x04.toByte()

/** XLS（OLE2 复合文档）文件头 `D0 CF 11 E0`。 */
private fun isOle2Signature(bytes: ByteArray): Boolean =
    bytes.size >= 8 &&
        bytes[0] == 0xD0.toByte() &&
        bytes[1] == 0xCF.toByte() &&
        bytes[2] == 0x11.toByte() &&
        bytes[3] == 0xE0.toByte()

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
                "Failed to parse the JSON data: %s",
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
internal fun defaultCategoryOptions(
    ledger: com.milenote.android.domain.model.Ledger?,
    ev: Boolean = false,
): List<Pair<String, String>> {
    val options = mutableListOf("" to tr("跳过未识别分类", "Skip unrecognized categories"))
    val names = ledger?.let { Categories.options(it) } ?: Categories.ALL
    names.forEach { name -> options += name to DataLabels.category(name, ev) }
    return options
}

/**
 * 「修改第 N 行」弹层的分类下拉：标准 9 类 + 本车自定义分类 + 该行当前分类。
 *
 * 保留当前值：识别出的分类可能是账本里出现过的旧分类 / 别的车分类，
 * 不出现在选项里会让用户误以为自己改不了。值是数据值（分类名，不翻译），
 * 展示名走 [DataLabels.category]（纯电「油费」显示为「电费」）。
 */
internal fun editCategoryOptions(
    current: String,
    customCategories: List<String>,
    ev: Boolean = false,
): List<Pair<String, String>> {
    val names = LinkedHashSet<String>()
    names.addAll(Categories.ALL)
    customCategories.forEach { name -> if (name.isNotEmpty()) names.add(name) }
    if (current.isNotEmpty()) names.add(current)
    return names.map { name -> name to DataLabels.category(name, ev) }
}

/** 记录来源展示名（账本里存的是中文来源值，只在显示层映射）。 */
internal fun importSourceLabel(source: String?): String {
    val raw = source.orEmpty()
    return when (raw) {
        "小熊油耗" -> tr("小熊油耗", "Bear")
        "钱迹", "钱迹记账" -> tr("钱迹记账", "Qianji")
        "里程志 CSV" -> tr("里程志 CSV", "MileNote CSV")
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

/**
 * 费用导入字段键顺序（与 Web fieldNames 一致）；按动力类型隐藏另一动力类型的专属字段。
 *
 * `recordId` / `exportSource` 是里程志 CSV 回灌的隐藏列，不进入手动字段映射界面
 * （读文件时按表头自动识别）。
 */
internal fun importFieldOrder(ev: Boolean = false): List<String> {
    val hidden = if (ev) setOf("liters", "fuelGrade") else setOf("kwh")
    return FIELDS.keys.filter { it !in hidden && it != "recordId" && it != "exportSource" }
}
