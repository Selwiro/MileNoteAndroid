package com.milenote.android.domain.calc

import com.milenote.android.domain.import.ImportItem
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Record

/** 导入合并结果（Web `{records, added, merged, skipped}`）。 */
data class MergeResult(
    val records: List<Record>,
    val added: Int,
    val merged: Int,
    val skipped: Int,
)

/** 跨来源同日油费冲突（Web `fuelImportConflicts` 行）。 */
data class FuelConflict(
    val date: String,
    val bear: Double,
    val qianji: Double,
)

/** 整批油费口径冲突汇总（Web `fuelBatchConflict` 返回值）。 */
data class FuelBatchConflict(val bear: Double, val qianji: Double)

private val FUEL_SOURCES = setOf("小熊油耗", "钱迹")
private val FUEL_CHOICES = setOf("bear", "qianji", "both")

/**
 * 业务判重签名（Web `signature`）。
 *
 * 由日期、归一分类、金额（两位小数）、里程、油量、油耗、项目、地点、旧时刻拼接；
 * 用于跨导入通道识别「同一笔业务」。
 */
fun signature(record: Record): String = listOf(
    record.date,
    Categories.normalize(record.category),
    Numbers.toFixed2(record.amount),
    record.odometer?.let { Numbers.jsString(it) } ?: "",
    record.liters?.let { Numbers.jsString(it) } ?: "",
    record.rate?.let { Numbers.jsString(it) } ?: "",
    record.projects.orEmpty(),
    record.location.orEmpty(),
    record.time.orEmpty(),
).joinToString("|")

/**
 * 判重（Web `duplicateOf`，顺序敏感）：
 * 0. 记录 id 相同（里程志 CSV 回灌带「记录编号」，优先按 id 命中）；
 * 1. 已有记录的 `importedKeys` 含 `来源:编号`；
 * 2. 同来源且都有 `sourceId`，编号相同；
 * 3. 业务签名相同；
 * 4. 双方均为油费、同一天、金额差 < 0.005、来源不同（跨源同额视为同一笔）。
 */
fun duplicateOf(records: List<Record>, record: Record): Record? {
    if (record.id.isNotEmpty()) {
        records.firstOrNull { it.id == record.id }?.let { return it }
    }
    val sourceId = record.sourceId
    val key = if (!sourceId.isNullOrEmpty()) "${record.source}:$sourceId" else ""
    for (x in records) {
        if (key.isNotEmpty() && x.importedKeys.orEmpty().contains(key)) return x
        if (record.source == x.source && !sourceId.isNullOrEmpty() && !x.sourceId.isNullOrEmpty()) {
            if (record.sourceId == x.sourceId) return x
            continue
        }
        if (signature(x) == signature(record)) return x
        // 跨源同额只在「同为燃油」或「同为纯电」时视为同一笔，升与度不互相判重。
        if (Categories.isEnergy(record.category) && Categories.isEnergy(x.category) &&
            (record.kwh == null) == (x.kwh == null) &&
            record.date == x.date && kotlin.math.abs(x.amount - record.amount) < 0.005 &&
            x.source != record.source
        ) {
            return x
        }
    }
    return null
}

/**
 * 手动记账的「疑似重复」提醒规则（比 [duplicateOf] 宽松）：
 * 同一天 + 归一分类相同 + 金额相差 < 0.005 视为疑似同一笔。
 *
 * 只用于录入后提醒用户确认，**不参与**导入判重、合并或删除，也不改写任何数据；
 * 用户确认「仍要保存」时照常写入。
 */
fun manualDuplicateOf(records: List<Record>, record: Record): Record? = records.firstOrNull { x ->
    x.id != record.id &&
        x.date == record.date &&
        Categories.normalize(x.category) == Categories.normalize(record.category) &&
        kotlin.math.abs(x.amount - record.amount) < 0.005
}

/**
 * 合并导入行（Web `mergeImported`）。
 *
 * 判重命中时：写入 `importedKeys`；油费新记录有升数而旧记录没有时，
 * 保留旧 id 合并升数并把备注用 ` / ` 拼接，计入 `merged`；否则计入 `skipped`。
 */
fun mergeImported(records: List<Record>, items: List<ImportItem>): MergeResult {
    val result = records.toMutableList()
    var added = 0
    var merged = 0
    var skipped = 0
    for (item in items) {
        if (item.error.isNotEmpty() || item.exclude) {
            skipped++
            continue
        }
        val r = item.record
        // 同编号（里程志 CSV 回灌）永远原位更新，即使勾了「独立的一笔」也不能写出重复 id。
        val sameId = if (r.id.isNotEmpty()) result.firstOrNull { it.id == r.id } else null
        val old = if (sameId == null && !item.force) duplicateOf(result, r) else sameId
        if (old != null) {
            if (sameId != null) {
                // 回灌更新：保留账本级 id 与 CSV 没导出的字段（计入成本口径 / 合并键 / 来源编号 / 旧时刻）。
                val index = indexOfSame(result, old)
                val key = if (!r.sourceId.isNullOrEmpty()) "${r.source}:${r.sourceId}" else ""
                val keys = LinkedHashSet(old.importedKeys.orEmpty())
                if (key.isNotEmpty()) keys.add(key)
                val oldKey = if (!old.sourceId.isNullOrEmpty()) "${old.source}:${old.sourceId}" else ""
                if (oldKey.isNotEmpty()) keys.add(oldKey)
                result[index] = r.copy(
                    id = old.id,
                    fuelCounted = r.fuelCounted ?: old.fuelCounted,
                    importedKeys = keys.toList().ifEmpty { null },
                    sourceId = r.sourceId?.takeIf { it.isNotBlank() } ?: old.sourceId,
                    time = r.time ?: old.time,
                )
                merged++
                continue
            }
            val key = if (!r.sourceId.isNullOrEmpty()) "${r.source}:${r.sourceId}" else ""
            val keys = LinkedHashSet(old.importedKeys.orEmpty())
            if (key.isNotEmpty()) keys.add(key)
            val index = indexOfSame(result, old)
            val newEnergy = r.liters ?: r.kwh
            val oldEnergy = old.liters ?: old.kwh
            // 只允许「同量纲」补量：充电记录（kwh）不能覆盖加油记录（liters），反之亦然。
            if (Categories.isEnergy(r.category) && (newEnergy ?: 0.0) != 0.0 && (oldEnergy ?: 0.0) == 0.0 &&
                (r.kwh != null) == (old.kwh != null)
            ) {
                val oldKey = if (!old.sourceId.isNullOrEmpty()) "${old.source}:${old.sourceId}" else ""
                if (oldKey.isNotEmpty()) keys.add(oldKey)
                val note = listOf(old.note, r.note).filter { it.isNotEmpty() }.joinToString(" / ")
                val replacement = r.copy(id = old.id, importedKeys = keys.toList(), note = note)
                result[index] = replacement
                merged++
            } else {
                result[index] = old.copy(importedKeys = keys.toList())
                if (old.source != r.source) merged++ else skipped++
            }
        } else {
            result.add(r)
            added++
        }
    }
    return MergeResult(result.toList(), added, merged, skipped)
}

private fun indexOfSame(records: List<Record>, target: Record): Int {
    for (i in records.indices) if (records[i] === target) return i
    return records.indexOfFirst { it.id == target.id }
}

/**
 * 未接 UI 的历史导入策略（Web `applyImportPolicy`）：
 * 小熊口径下跳过钱迹油费，避免与「小熊实付」重复计成本。
 */
fun applyImportPolicy(items: List<ImportItem>, basis: String): List<ImportItem> = items.map { item ->
    if (basis == "bear" && item.record.source == "钱迹" && item.record.category == "油费" &&
        item.record.kwh == null
    ) {
        item.copy(error = tr("按成本设置跳过钱迹油费（以小熊实付为准）", "Skipped Qianji fuel per cost setting (Bear paid amount takes precedence)"))
    } else {
        item
    }
}

/** 是否「燃油口径」记录：能源分类且没有充电量（`kwh`）。充电记录不参与小熊/钱迹口径。 */
private fun isFuelSourceRecord(record: Record): Boolean =
    record.category == "油费" && record.kwh == null && record.source in FUEL_SOURCES

/**
 * 跨来源同日油费冲突（Web `fuelImportConflicts`）。
 *
 * 只有「待导入 / 已有」两侧同一天同时存在小熊与钱迹油费时才算冲突；
 * 拆分付款（同日多笔钱迹）按日期合计展示。**充电记录（`kwh`）不参与**。
 */
fun fuelImportConflicts(records: List<Record>, items: List<ImportItem>): List<FuelConflict> {
    val valid = items.filter { it.isValid }.map { it.record }
    val dates = LinkedHashSet<String>()
    for (r in valid) {
        if (isFuelSourceRecord(r) &&
            duplicateOf(records.filter { it.source == r.source }, r) == null
        ) {
            dates.add(r.date)
        }
    }
    val conflicts = mutableListOf<FuelConflict>()
    for (date in dates.sorted()) {
        val rows = records.toMutableList()
        for (r in valid.filter { it.date == date }) {
            if (duplicateOf(rows.filter { it.source == r.source }, r) == null) rows.add(r)
        }
        val fuels = rows.filter { it.date == date && isFuelSourceRecord(it) }
        val hasBear = fuels.any { it.source == "小熊油耗" }
        val hasQianji = fuels.any { it.source == "钱迹" }
        if (hasBear && hasQianji) {
            conflicts += FuelConflict(
                date = date,
                bear = fuels.filter { it.source == "小熊油耗" }.sumOf { it.amount },
                qianji = fuels.filter { it.source == "钱迹" }.sumOf { it.amount },
            )
        }
    }
    return conflicts
}

/**
 * 带口径选择的导入合并（Web `mergeWithFuelChoices`）。
 *
 * `choices` 为 `日期 → bear/qianji/both`；冲突日期缺少选择时抛异常。
 * 油费统一写 `fuelCounted` 三态，跨源冲突按选择只计一侧或两侧合计。
 */
fun mergeWithFuelChoices(
    records: List<Record>,
    items: List<ImportItem>,
    choices: Map<String, String> = emptyMap(),
): MergeResult {
    val conflicts = fuelImportConflicts(records, items)
    for (c in conflicts) {
        if (choices[c.date] !in FUEL_CHOICES) {
            throw IllegalArgumentException(tr("%s 有油费冲突，请选择处理方式", "%s has a fuel conflict — choose how to handle it", c.date))
        }
    }
    var result = records.toMutableList()
    var added = 0
    var merged = 0
    var skipped = 0
    for (item in items) {
        if (item.error.isNotEmpty() || item.exclude) {
            skipped++
            continue
        }
        val r = item.record
        if (isFuelSourceRecord(r)) {
            val old = duplicateOf(result.filter { it.source == r.source }, r)
            if (old != null) {
                skipped++
            } else {
                result.add(r.copy(fuelCounted = true))
                added++
            }
        } else {
            val next = mergeImported(result.toList(), listOf(item))
            result = next.records.toMutableList()
            added += next.added
            merged += next.merged
            skipped += next.skipped
        }
    }
    for (c in conflicts) {
        val choice = choices.getValue(c.date)
        for (i in result.indices) {
            val r = result[i]
            if (r.date == c.date && isFuelSourceRecord(r)) {
                val counted = choice == "both" ||
                    r.source == if (choice == "bear") "小熊油耗" else "钱迹"
                result[i] = r.copy(fuelCounted = counted)
            }
        }
    }
    return MergeResult(result.toList(), added, merged, skipped)
}

/**
 * 整批油费口径冲突（Web `fuelBatchConflict`）：不要求同一天，
 * 只要有新导入的油费且账本内小熊/钱迹并存，就需要选择一次口径。
 */
fun fuelBatchConflict(records: List<Record>, items: List<ImportItem>): FuelBatchConflict? {
    val valid = items.filter { it.isValid }.map { it.record }
        .filter { it.category == "油费" && it.kwh == null && it.source in FUEL_SOURCES }
    val fresh = valid.filter { duplicateOf(records.filter { x -> x.source == it.source }, it) == null }
    if (fresh.isEmpty()) return null
    val all = records.filter { it.category == "油费" && it.kwh == null } + fresh
    if (all.none { it.source == "小熊油耗" } || all.none { it.source == "钱迹" }) return null
    return FuelBatchConflict(
        bear = all.filter { it.source == "小熊油耗" }.sumOf { it.amount },
        qianji = all.filter { it.source == "钱迹" }.sumOf { it.amount },
    )
}

/**
 * 整批油费口径导入（Web `mergeFuelBatch`，默认小熊）：
 * 先按日期补全冲突选择，再按整批口径回写所有油费的 `fuelCounted`。
 */
fun mergeFuelBatch(
    records: List<Record>,
    items: List<ImportItem>,
    basis: String = "bear",
): MergeResult {
    if (basis !in FUEL_CHOICES) throw IllegalArgumentException(tr("请选择油费处理方式", "Choose how to handle the fuel entries"))
    val conflict = fuelBatchConflict(records, items)
    val choices = fuelImportConflicts(records, items).associate { it.date to basis }
    val result = mergeWithFuelChoices(records, items, choices)
    if (conflict != null) {
        val updated = result.records.map { r ->
            if (isFuelSourceRecord(r)) {
                val counted = basis == "both" || r.source == if (basis == "bear") "小熊油耗" else "钱迹"
                r.copy(fuelCounted = counted)
            } else {
                r
            }
        }
        return result.copy(records = updated)
    }
    return result
}
