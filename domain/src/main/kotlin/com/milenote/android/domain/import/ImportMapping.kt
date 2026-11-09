package com.milenote.android.domain.import

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.fillEnergyAmounts
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.import.file.SpreadsheetData
import com.milenote.android.domain.model.FUEL_GRADES
import com.milenote.android.domain.model.Record

/**
 * 一条导入行（Web `importRows` 返回的 `{row, record, error, rawCategory}`，
 * 外加预览界面写入的 `exclude` / `force`）。
 */
data class ImportItem(
    /** Excel/CSV 行号（含表头，从 2 开始，便于直接提示用户）。 */
    val row: Int,
    val record: Record,
    /** 行级错误；空串表示通过。 */
    val error: String = "",
    /** 原始分类文本（预览页展示）。 */
    val rawCategory: String = "",
    /** 预览页取消勾选。 */
    val exclude: Boolean = false,
    /** 「是独立的一笔」：跳过判重强制导入。 */
    val force: Boolean = false,
) {
    /** 参与合并的有效行。 */
    val isValid: Boolean get() = error.isEmpty() && !exclude
}

/**
 * 导入字段别名表（Web `FIELDS`）。顺序即别名优先级：
 * 金额同时有「实付金额」和「机显金额」时取实付。
 */
val FIELDS: Map<String, List<String>> = linkedMapOf(
    "projects" to listOf("项目", "保养项目", "维修项目", "美容项目"),
    "location" to listOf("地点", "门店", "保养地点"),
    "date" to listOf("日期时间", "日期", "时间", "date", "交易时间"),
    "category" to listOf("分类", "类别", "category", "一级分类"),
    "amount" to listOf("实付金额", "金额", "money", "支出金额", "机显金额"),
    "payable" to listOf("应付金额", "应付", "原价", "应收金额"),
    "discount" to listOf("优惠金额", "优惠", "折扣", "减免"),
    "unitPrice" to listOf("油价", "电价", "单价", "油单价", "电单价", "元/升", "元/度", "元/kWh", "价格"),
    "fuelGrade" to listOf("燃油标号", "标号", "油品", "汽油标号", "fuelGrade"),
    "odometer" to listOf("总里程", "里程", "公里数", "odometer"),
    "liters" to listOf("加油量", "油量", "升数", "liters"),
    "kwh" to listOf("充电量", "电量", "千瓦时", "度电", "kWh"),
    "full" to listOf("加满", "是否加满", "充满", "是否充满", "满充", "full"),
    "missed" to listOf("漏记", "是否漏记", "missed"),
    "rate" to listOf("油耗", "百公里油耗", "电耗", "百公里电耗", "能耗", "rate"),
    "distance" to listOf("行驶里程", "本次里程", "distance"),
    "note" to listOf("备注", "remark", "note"),
    "type" to listOf("类型", "收支", "收支类型", "type"),
    "sourceId" to listOf("key", "id"),
    "currency" to listOf("币种", "currency"),
)

/** 表头 → 字段列下标；缺失字段为 null（Web `detectMap`）。 */
fun detectMap(headers: List<String>): Map<String, Int?> {
    val map = LinkedHashMap<String, Int?>()
    for ((key, names) in FIELDS) {
        map[key] = names
            .map { name -> headers.indexOfFirst { it.trim() == name } }
            .firstOrNull { it >= 0 }
    }
    return map
}

private val TYPE_SKIP = Regex("收入|转账|转入|转出")
private val FULL_TRUE = Regex("^(是|满|加满|已加满|充满|已充满|满电|true|1|yes)$", RegexOption.IGNORE_CASE)
private val MISSED_TRUE = Regex("^(是|true|1|yes)$", RegexOption.IGNORE_CASE)

private fun rawCell(row: List<Any?>, map: Map<String, Int?>, key: String): Any? {
    val index = map[key] ?: return null
    if (index < 0 || index >= row.size) return null
    return row[index]
}

private fun textCell(row: List<Any?>, map: Map<String, Int?>, key: String): String =
    Numbers.cellString(rawCell(row, map, key))

/** JS `String(get(k) || '')` 的语义：0/false/null 都当空串。 */
private fun textOrEmpty(row: List<Any?>, map: Map<String, Int?>, key: String): String {
    val raw = rawCell(row, map, key) ?: return ""
    return when (raw) {
        is Boolean -> if (raw) "true" else ""
        is Double -> if (raw == 0.0) "" else Numbers.jsString(raw)
        is Float -> if (raw == 0f) "" else Numbers.jsString(raw.toDouble())
        is Int -> if (raw == 0) "" else raw.toString()
        is Long -> if (raw == 0L) "" else raw.toString()
        else -> raw.toString()
    }
}

/**
 * 原始行 → 记录（Web `importRows`）。
 *
 * @param source `bear`（小熊油耗）或 `qianji`（钱迹）
 * @param defaultCategory 钱迹无法识别分类时的兜底（预览页下拉，默认跳过）
 */
fun importRows(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    ev: Boolean = false,
): List<ImportItem> = rows.mapIndexed { i, row ->
    val rawCategory = textCell(row, map, "category")
    val type = textCell(row, map, "type").trim()
    val amount = Numbers.parse(rawCell(row, map, "amount"))
    var record = Record(
        id = Ids.newId(),
        date = Dates.dateValue(rawCell(row, map, "date")),
        category = if (source == "bear") {
            "油费"
        } else {
            Categories.categoryOf(rawCategory, customCategories).ifEmpty { defaultCategory }
        },
        amount = amount ?: 0.0,
        odometer = Numbers.parse(rawCell(row, map, "odometer")),
        liters = Numbers.parse(rawCell(row, map, "liters")),
        kwh = Numbers.parse(rawCell(row, map, "kwh")),
        unitPrice = Numbers.parse(rawCell(row, map, "unitPrice")),
        payable = Numbers.parse(rawCell(row, map, "payable")),
        discount = Numbers.parse(rawCell(row, map, "discount")),
        fuelGrade = textCell(row, map, "fuelGrade").trim().takeIf { it in FUEL_GRADES },
        rate = Numbers.parse(rawCell(row, map, "rate")),
        distance = Numbers.parse(rawCell(row, map, "distance")),
        full = FULL_TRUE.matches(textCell(row, map, "full").trim()),
        note = textCell(row, map, "note").take(1000),
        source = if (source == "bear") "小熊油耗" else "钱迹",
        kind = "expense",
    )
    if ((record.rate ?: 0.0) > 0 && (record.liters ?: 0.0) == 0.0 && (record.kwh ?: 0.0) == 0.0) {
        record = record.copy(kind = "manual")
    }
    // 小熊/钱迹新导出可能只有部分金额字段，空白项按「量×价=应付，应付-优惠=实付」补齐。
    record = fillEnergyAmounts(record, ev)
    val error = if (TYPE_SKIP.containsMatchIn(type)) {
        "非支出记录，已跳过"
    } else {
        validateRecord(record, customCategories)
    }
    ImportItem(row = i + 2, record = record, error = error, rawCategory = rawCategory)
}

/** 表格文件版 [importRows]（消费另一位队友产出的 [SpreadsheetData]）。 */
fun importRows(
    data: SpreadsheetData,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    ev: Boolean = false,
): List<ImportItem> = importRows(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    ev,
)

/**
 * 单元格版 [normalizeImport]：在 [importRows] 基础上补齐
 * 来源编号、漏记、币种、原始油耗，并识别备注里的保养里程 / 项目。
 */
fun normalizeImport(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    ev: Boolean = false,
): List<ImportItem> = importRows(rows, map, source, defaultCategory, customCategories, ev)
    .mapIndexed { i, item ->
        var record = inferServiceNote(
            item.record.copy(
                projects = textOrEmpty(rows[i], map, "projects"),
                location = textOrEmpty(rows[i], map, "location"),
            ),
            ev = ev,
        )
        record = record.copy(
            sourceId = textCell(rows[i], map, "sourceId"),
            missed = MISSED_TRUE.matches(textCell(rows[i], map, "missed")),
            currency = textOrEmpty(rows[i], map, "currency").ifEmpty { "CNY" },
        )
        if ((record.rate ?: 0.0) <= 0.0) record = record.copy(rate = null)
        if (record.kind == "expense") {
            record = record.copy(reportedRate = record.rate, rate = null)
        }
        val type = textCell(rows[i], map, "type")
        val currency = record.currency.orEmpty().uppercase()
        val error = when {
            TYPE_SKIP.containsMatchIn(type) -> "非支出记录，已跳过"
            currency !in setOf("CNY", "人民币", "RMB", "") -> "非人民币记录，请先换算"
            else -> validateRecord(record, customCategories)
        }
        item.copy(record = record, error = error)
    }

/** 表格文件版 [normalizeImport]。 */
fun normalizeImport(
    data: SpreadsheetData,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    ev: Boolean = false,
): List<ImportItem> = normalizeImport(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    ev,
)

private val MILEAGE = Regex(
    "(\\d+(?:[,，]\\d{3})*(?:\\.\\d+)?)\\s*(万|千)?\\s*(?:km|公里|千米)",
    RegexOption.IGNORE_CASE,
)
private val PLAN_WORDS = Regex("下次|周期|间隔|每|还剩|再跑|行驶|距保养|建议")
private val EXPLICIT_ODOMETER = Regex("总里程|表显|仪表|当前里程|累计里程")
private val NEGATIVE_CLAUSE = Regex("(?:未|没|不|无需|暂不|下次|建议|计划|检查|咨询|报价)")

private val PROJECT_ALIASES_FUEL: List<Pair<String, Regex>> = listOf(
    "机油机滤" to Regex("机油|机滤|机油滤芯|机油滤清器"),
    "空气滤芯" to Regex("空气滤芯|空气滤清器|空滤"),
    "空调滤芯" to Regex("空调滤芯|空调滤清器"),
    "冷却液" to Regex("冷却液|防冻液"),
    "制动液" to Regex("制动液|刹车油"),
    "灰滤" to Regex("灰滤"),
    "火花塞" to Regex("火花塞"),
    "轮胎" to Regex("更换轮胎|换轮胎|换胎"),
    "冷却模块" to Regex("冷却模块"),
    "变速器油" to Regex("变速器油|变速箱油"),
    "皮带" to Regex("皮带"),
    "补胎" to Regex("补胎"),
    "更换电瓶" to Regex("更换电瓶|换电瓶|更换蓄电池"),
    "钣金修复" to Regex("钣金"),
    "喷漆" to Regex("喷漆|补漆"),
    "洗车" to Regex("洗车"),
    "打蜡" to Regex("打蜡"),
    "抛光" to Regex("抛光"),
    "镀晶" to Regex("镀晶"),
    "内饰清洁" to Regex("内饰清洁|清洗内饰"),
)

/** 纯电项目别名：刹车油沿用燃油正则但写成纯电方案标题，并新增三电诊断。 */
private val PROJECT_ALIASES_EV: List<Pair<String, Regex>> = PROJECT_ALIASES_FUEL.map { (title, pattern) ->
    if (title == "制动液") "刹车油" to pattern else title to pattern
} + ("三电诊断" to Regex("三电|电池健康|电池检测|电机|电控|高压系统|三电系统"))

/**
 * 从备注中识别保养里程与项目（Web `inferServiceNote`）。
 *
 * 保守策略：有「下次 / 周期 / 间隔 / 行驶 / 建议」等前缀的里程不算；
 * 多个不同候选不猜测；已有明确字段优先保留；否定从句里的项目不算。
 * 仅对保养 / 维修 / 汽车美容类记录生效，其余原样返回。
 *
 * @param ev true=纯电（项目名用纯电方案标题，如「刹车油」「三电诊断」）
 */
fun inferServiceNote(record: Record, ev: Boolean = false): Record {
    if (record.category !in setOf("保养", "维修保养", "维修", "汽车美容")) return record
    val note = record.note
    var result = record

    if (result.odometer == null) {
        val candidates = mutableListOf<Pair<Double, Boolean>>() // value to explicit
        for (m in MILEAGE.findAll(note)) {
            val prefix = note
                .substring(maxOf(0, m.range.first - 20), m.range.first)
                .split(Regex("[，,。；;\\n]"))
                .last()
            if (PLAN_WORDS.containsMatchIn(prefix)) continue
            val raw = m.groupValues[1].replace(Regex("[,，]"), "").toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            val value = raw * when (unit) {
                "万" -> 10000.0
                "千" -> 1000.0
                else -> 1.0
            }
            if (value.isFinite() && value >= 0) {
                candidates += value to EXPLICIT_ODOMETER.containsMatchIn(prefix)
            }
        }
        val hasExplicit = candidates.any { it.second }
        val preferred = if (hasExplicit) candidates.filter { it.second } else candidates
        val values = preferred.map { it.first }.distinct()
        if (values.size == 1) result = result.copy(odometer = values[0])
    }

    if (result.projects.isNullOrBlank()) {
        val clauses = note
            .split(Regex("[，,。；;\\n、]+"))
            .filterNot { NEGATIVE_CLAUSE.containsMatchIn(it) }
        val aliases = if (ev) PROJECT_ALIASES_EV else PROJECT_ALIASES_FUEL
        val projects = aliases
            .filter { (_, pattern) -> clauses.any { pattern.containsMatchIn(it) } }
            .joinToString("、") { it.first }
        result = result.copy(projects = projects)
    }
    return result
}
