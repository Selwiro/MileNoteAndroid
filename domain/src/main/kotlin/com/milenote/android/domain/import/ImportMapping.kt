package com.milenote.android.domain.import

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.fillEnergyAmounts
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.import.file.SpreadsheetData
import com.milenote.android.domain.model.FUEL_GRADES
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.normalizePowertrain

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
    // 以下是里程志自家 CSV 回灌用的两列（不参与手动字段映射界面）：
    // 记录编号 = 原记录 id（同编号更新原记录）；来源 = 导出时的来源标签（保留「手动记录 / 小熊油耗」等）。
    "recordId" to listOf("记录编号", "record id", "recordId"),
    "exportSource" to listOf("来源", "source"),
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
 * **行级能量种类**：一行只产一种能量记录（`liters` 与 `kwh` 互斥）。
 * 该行在「充电量 / 电量 / 度电」列有值、或文本（项目/备注/分类/地点）含「充电」→ 充电记录
 * （`kwh`、`fuelGrade = null`、`fillEnergyAmounts(ev = true)`）；否则按加油记录。
 * 小熊油耗导出只有加油行，强制按燃油口径。
 *
 * @param source `bear`（小熊油耗）或 `qianji`（钱迹）
 * @param defaultCategory 钱迹无法识别分类时的兜底（预览页下拉，默认跳过）
 * @param powertrain 当前车动力类型：`ev` 车的无能量量行按充电；`phev` 按行判定；缺省按燃油
 */
fun importRows(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    powertrain: String? = null,
): List<ImportItem> = rows.mapIndexed { i, row ->
    val rawCategory = textCell(row, map, "category")
    val type = textCell(row, map, "type").trim()
    val amount = Numbers.parse(rawCell(row, map, "amount"))
    // 小熊油耗只有加油记录；钱迹 / 通用表格按行判断是加油还是充电。
    val electric = source != "bear" && rowIsCharging(row, map, powertrain)
    // 里程志 CSV 带「记录编号」：沿用原 id，回灌时按 id 更新而不是新增。
    val recordId = textCell(row, map, "recordId").trim().take(200)
    var record = Record(
        id = recordId.ifEmpty { Ids.newId() },
        date = Dates.dateValue(rawCell(row, map, "date")),
        category = if (source == "bear") {
            "油费"
        } else {
            Categories.categoryOf(rawCategory, customCategories).ifEmpty { defaultCategory }
        },
        amount = amount ?: 0.0,
        odometer = Numbers.parse(rawCell(row, map, "odometer")),
        liters = if (electric) null else Numbers.parse(rawCell(row, map, "liters")),
        kwh = if (electric) Numbers.parse(rawCell(row, map, "kwh")) else null,
        unitPrice = Numbers.parse(rawCell(row, map, "unitPrice")),
        payable = Numbers.parse(rawCell(row, map, "payable")),
        discount = Numbers.parse(rawCell(row, map, "discount")),
        fuelGrade = if (electric) {
            null
        } else {
            textCell(row, map, "fuelGrade").trim().takeIf { it in FUEL_GRADES }
        },
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
    record = fillEnergyAmounts(record, electric)
    val error = if (TYPE_SKIP.containsMatchIn(type)) {
        "非支出记录，已跳过"
    } else {
        validateRecord(record, customCategories)
    }
    ImportItem(row = i + 2, record = record, error = error, rawCategory = rawCategory)
}

/** 兼容旧调用（二值 `ev`）。新代码请用 `importRows(..., powertrain)`。 */
@Deprecated(
    "改用 importRows(..., powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("importRows(rows, map, source, defaultCategory, customCategories, if (ev) \"ev\" else null)"),
)
fun importRows(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    source: String,
    defaultCategory: String,
    customCategories: List<String>,
    ev: Boolean,
): List<ImportItem> = importRows(
    rows,
    map,
    source,
    defaultCategory,
    customCategories,
    if (ev) POWERTRAIN_EV else null,
)

/** 充电记录提示词：项目 / 备注 / 分类 / 地点里出现这些词即视为充电行。 */
private val CHARGING_HINT = Regex("充电|快充|慢充|超充|家充|换电|满电")

/**
 * 判断一行是否充电记录（用于 [importRows] 的行级能量种类）。
 *
 * 优先级：充电量列有值 → 充电；加油量列有值 → 加油；文本含「充电」→ 充电；
 * 否则按车（`ev` 车无能量量时只可能是充电）兜底。
 */
private fun rowIsCharging(row: List<Any?>, map: Map<String, Int?>, powertrain: String?): Boolean {
    val kwh = Numbers.parse(rawCell(row, map, "kwh"))
    if (kwh != null && kwh > 0) return true
    val liters = Numbers.parse(rawCell(row, map, "liters"))
    if (liters != null && liters > 0) return false
    val text = listOf("projects", "note", "category", "location")
        .joinToString(" ") { textCell(row, map, it) }
    if (CHARGING_HINT.containsMatchIn(text)) return true
    return normalizePowertrain(powertrain) == POWERTRAIN_EV
}

/** 表格文件版 [importRows]（消费另一位队友产出的 [SpreadsheetData]）。 */
fun importRows(
    data: SpreadsheetData,
    source: String,
    defaultCategory: String = "",
    customCategories: List<String> = emptyList(),
    powertrain: String? = null,
): List<ImportItem> = importRows(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    powertrain,
)

/** 兼容旧调用（二值 `ev`）。新代码请用 `importRows(data, ...)`。 */
@Deprecated(
    "改用 importRows(data, ..., powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("importRows(data, source, defaultCategory, customCategories, if (ev) \"ev\" else null)"),
)
fun importRows(
    data: SpreadsheetData,
    source: String,
    defaultCategory: String,
    customCategories: List<String>,
    ev: Boolean,
): List<ImportItem> = importRows(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    if (ev) POWERTRAIN_EV else null,
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
    powertrain: String? = null,
): List<ImportItem> = importRows(rows, map, source, defaultCategory, customCategories, powertrain)
    .mapIndexed { i, item ->
        var record = inferServiceNote(
            item.record.copy(
                projects = textOrEmpty(rows[i], map, "projects"),
                location = textOrEmpty(rows[i], map, "location").take(200),
            ),
            powertrain = powertrain,
        )
        // 里程志 CSV：保留导出时的来源标签（手动记录 / 小熊油耗…），缺失时标为「里程志 CSV」。
        if (source == "milenote") {
            record = record.copy(
                source = textOrEmpty(rows[i], map, "exportSource").trim().take(40).ifEmpty { "里程志 CSV" },
            )
        }
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
    powertrain: String? = null,
): List<ImportItem> = normalizeImport(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    powertrain,
)

/** 兼容旧调用（单元格版，二值 `ev`）。 */
@Deprecated(
    "改用 normalizeImport(..., powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("normalizeImport(rows, map, source, defaultCategory, customCategories, if (ev) \"ev\" else null)"),
)
fun normalizeImport(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    source: String,
    defaultCategory: String,
    customCategories: List<String>,
    ev: Boolean,
): List<ImportItem> = normalizeImport(
    rows,
    map,
    source,
    defaultCategory,
    customCategories,
    if (ev) POWERTRAIN_EV else null,
)

/** 兼容旧调用（表格文件版，二值 `ev`）。 */
@Deprecated(
    "改用 normalizeImport(data, ..., powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("normalizeImport(data, source, defaultCategory, customCategories, if (ev) \"ev\" else null)"),
)
fun normalizeImport(
    data: SpreadsheetData,
    source: String,
    defaultCategory: String,
    customCategories: List<String>,
    ev: Boolean,
): List<ImportItem> = normalizeImport(
    data.rows,
    detectMap(data.headers),
    source,
    defaultCategory,
    customCategories,
    if (ev) POWERTRAIN_EV else null,
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
 * 插混项目别名：燃油 12 项（标题沿用燃油方案，含「制动液」）+ 三电诊断。
 *
 * PHEV 方案 title 是燃油超集，所以「三电诊断 / 刹车油 / 制动液」三种写法都要能识别。
 */
private val PROJECT_ALIASES_PHEV: List<Pair<String, Regex>> = PROJECT_ALIASES_FUEL +
    ("三电诊断" to Regex("三电|电池健康|电池检测|电机|电控|高压系统|三电系统"))

/**
 * 从备注中识别保养里程与项目（Web `inferServiceNote`）。
 *
 * 保守策略：有「下次 / 周期 / 间隔 / 行驶 / 建议」等前缀的里程不算；
 * 多个不同候选不猜测；已有明确字段优先保留；否定从句里的项目不算。
 * 仅对保养 / 维修 / 汽车美容类记录生效，其余原样返回。
 *
 * @param powertrain 动力类型：`ev` 用纯电方案标题（「刹车油」「三电诊断」）；
 *   `phev` 用燃油标题 + 三电诊断（并集）；缺省/燃油用燃油标题
 */
fun inferServiceNote(record: Record, powertrain: String? = null): Record {
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
        val aliases = when (normalizePowertrain(powertrain)) {
            POWERTRAIN_EV -> PROJECT_ALIASES_EV
            POWERTRAIN_PHEV -> PROJECT_ALIASES_PHEV
            else -> PROJECT_ALIASES_FUEL
        }
        val projects = aliases
            .filter { (_, pattern) -> clauses.any { pattern.containsMatchIn(it) } }
            .joinToString("、") { it.first }
        result = result.copy(projects = projects)
    }
    return result
}

/** 兼容旧调用（二值 `ev`）。新代码请用 `inferServiceNote(record, powertrain)`。 */
@Deprecated(
    "改用 inferServiceNote(record, powertrain: String?)；ev = true 等价于 powertrain = \"ev\"",
    ReplaceWith("inferServiceNote(record, if (ev) \"ev\" else null)"),
)
fun inferServiceNote(record: Record, ev: Boolean): Record =
    inferServiceNote(record, if (ev) POWERTRAIN_EV else null)
