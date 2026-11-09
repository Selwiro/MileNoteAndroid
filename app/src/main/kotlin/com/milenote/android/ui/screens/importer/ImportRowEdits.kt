package com.milenote.android.ui.screens.importer

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.FuelBatchConflict
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.duplicateOf
import com.milenote.android.domain.calc.fuelBatchConflict
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.import.ImportItem
import com.milenote.android.domain.import.normalizeImport
import com.milenote.android.domain.model.Record

// ---------------------------------------------------------------------------------------------
// 逐行修改（「修改第 N 行」弹层）
// ---------------------------------------------------------------------------------------------

/**
 * 用户在「修改第 N 行」弹层里确认的一次逐行修改。
 *
 * 每个字段都是 `null`=未改（保留行内原值）的「补丁」语义：
 * - 文本/数值字段传空串 = 显式清空（数值字段清空为 `null`）；
 * - [date] 用 `yyyy-MM-dd`（也接受 `yyyy/MM/dd`、`yyyy年MM月dd日` 等常见写法，由 `Dates.dateValue` 归一）；
 * - [category] 是**数据值**（分类名），不做翻译；
 * - [full] 燃油=是否加满，纯电=是否充满（同一个存储字段）。
 *
 * 以原始文件行号（[ImportItem.row]）为键存放在向导状态里，
 * 因此改字段映射 / 换工作表 / 改默认分类 / 勾选后重新生成预览都不会丢。
 */
internal data class ImportRowEdit(
    val date: String? = null,
    val category: String? = null,
    val amount: String? = null,
    val payable: String? = null,
    val discount: String? = null,
    val odometer: String? = null,
    /** 加油量（仅燃油）。 */
    val liters: String? = null,
    /** 充电量（仅纯电）。 */
    val kwh: String? = null,
    /** 能源单价：燃油=油价，纯电=电价。 */
    val unitPrice: String? = null,
    val note: String? = null,
    val full: Boolean? = null,
) {
    /** 未做任何修改（弹层原样保存时视为没有编辑）。 */
    val isEmpty: Boolean get() = this == ImportRowEdit()
}

/**
 * 这些错误来自「这一行本来就不该导入」的行级判断（收支类型 / 币种），
 * 弹层里的字段改不了它们，重新校验时保留，避免把收入行或非人民币行改一改就导入。
 * 文案与 `domain/import/ImportMapping.kt` 里写入的一致。
 */
private val NON_EDITABLE_IMPORT_ERRORS: Set<String> = setOf(
    "非支出记录，已跳过",
    "非人民币记录，请先换算",
)

/**
 * 把一次逐行修改应用到预览行：只覆盖 [edit] 里非 null 的字段，
 * 然后用 domain 的 [validateRecord] 重新校验并更新 [ImportItem.error]。
 *
 * @param customCategories 本车自定义分类（干系到分类合法性校验）
 */
internal fun applyRowEdit(
    item: ImportItem,
    edit: ImportRowEdit?,
    customCategories: List<String> = emptyList(),
): ImportItem {
    if (edit == null || edit.isEmpty) return item
    var record = item.record
    edit.date?.let { record = record.copy(date = Dates.dateValue(it)) }
    edit.category?.let { record = record.copy(category = it.trim()) }
    edit.amount?.let { record = record.copy(amount = Numbers.parse(it) ?: 0.0) }
    edit.payable?.let { record = record.copy(payable = Numbers.parse(it)) }
    edit.discount?.let { record = record.copy(discount = Numbers.parse(it)) }
    edit.odometer?.let { record = record.copy(odometer = Numbers.parse(it)) }
    edit.liters?.let { record = record.copy(liters = Numbers.parse(it)) }
    edit.kwh?.let { record = record.copy(kwh = Numbers.parse(it)) }
    edit.unitPrice?.let { record = record.copy(unitPrice = Numbers.parse(it)) }
    edit.note?.let { record = record.copy(note = it.take(1000)) }
    edit.full?.let { record = record.copy(full = it) }
    val error = if (item.error in NON_EDITABLE_IMPORT_ERRORS) {
        item.error
    } else {
        validateRecord(record, customCategories)
    }
    return item.copy(record = record, error = error)
}

/** 批量应用逐行修改（键为 [ImportItem.row]）。 */
internal fun applyRowEdits(
    items: List<ImportItem>,
    edits: Map<Int, ImportRowEdit>,
    customCategories: List<String> = emptyList(),
): List<ImportItem> =
    if (edits.isEmpty()) items else items.map { applyRowEdit(it, edits[it.row], customCategories) }

// ---------------------------------------------------------------------------------------------
// 预览生成（纯函数，wizard 实际调用）
// ---------------------------------------------------------------------------------------------

/** 预览生成结果：行（含错误 / 排除标记）、重复行下标、油费口径冲突。 */
internal data class ImportPreview(
    val items: List<ImportItem>,
    val duplicates: Set<Int>,
    val fuelConflict: FuelBatchConflict?,
)

/** 跨来源油费合并的判重口径：只按同来源判重（见 `mergeFuelBatch`）。 */
private val FUEL_SOURCES: Set<String> = setOf("小熊油耗", "钱迹")

/**
 * 预览生成的可测部分：normalize → 应用逐行修改 → 排除标记 → 判重 → 油费冲突。
 *
 * @param rows 表格原始行
 * @param mapping 字段 → 列下标
 * @param sourceKey `bear` / `qianji`
 * @param defaultCategory 钱迹无法识别分类时的兜底分类
 * @param customCategories 本车自定义分类
 * @param ev 当前车是否纯电（纯电不参与油费口径冲突）
 * @param edits 逐行修改，键为原始文件行号（[ImportItem.row]）
 * @param excludedRows 用户取消勾选的行下标（与 [ImportPreview.items] 下标一致）
 * @param existingRecords 账本现有记录（判重与油费冲突用）
 */
internal fun buildImportPreview(
    rows: List<List<Any?>>,
    mapping: Map<String, Int?>,
    sourceKey: String,
    defaultCategory: String,
    customCategories: List<String>,
    ev: Boolean,
    edits: Map<Int, ImportRowEdit> = emptyMap(),
    excludedRows: Set<Int> = emptySet(),
    existingRecords: List<Record> = emptyList(),
): ImportPreview {
    val normalized = normalizeImport(
        rows = rows,
        map = mapping,
        source = sourceKey,
        defaultCategory = defaultCategory,
        customCategories = customCategories,
        ev = ev,
    )
    val edited = applyRowEdits(normalized, edits, customCategories)
    val marked = markExcludedRows(edited, excludedRows)
    return ImportPreview(
        items = marked,
        duplicates = duplicateRowIndexes(existingRecords, edited),
        // 纯电不计算油费口径冲突（跨来源油费合并只对燃油车有意义）。
        fuelConflict = fuelConflictOf(existingRecords, marked, ev),
    )
}

/** 按下标给行打「取消勾选」标记（下标与 [ImportPreview.items] 一致）。 */
internal fun markExcludedRows(items: List<ImportItem>, excludedRows: Set<Int>): List<ImportItem> =
    if (excludedRows.isEmpty()) items else items.mapIndexed { i, item ->
        if (i in excludedRows) item.copy(exclude = true) else item
    }

/** 单行勾选/取消：返回新列表（不修改入参，便于 Compose 状态比较）。 */
internal fun markRowExcluded(items: List<ImportItem>, index: Int, exclude: Boolean): List<ImportItem> =
    items.mapIndexed { i, item -> if (i == index) item.copy(exclude = exclude) else item }

/**
 * 与现有账本重复的预览行下标。
 *
 * 与 `mergeFuelBatch` 的判重口径保持一致：跨来源油费在合并时只按同来源判重，
 * 不应在预览里标成「重复会跳过」，否则「预览重复但实际导入」会误导用户。
 */
internal fun duplicateRowIndexes(
    existingRecords: List<Record>,
    items: List<ImportItem>,
): Set<Int> = items.mapIndexedNotNull { i, item ->
    val record = item.record
    val scoped = if (record.category == "油费" && record.source in FUEL_SOURCES) {
        existingRecords.filter { it.source == record.source }
    } else {
        existingRecords
    }
    if (duplicateOf(scoped, record) != null) i else null
}.toSet()

/** 油费口径冲突（纯电恒为 null）。 */
internal fun fuelConflictOf(
    existingRecords: List<Record>,
    items: List<ImportItem>,
    ev: Boolean,
): FuelBatchConflict? = if (ev) null else fuelBatchConflict(existingRecords, items)

// ---------------------------------------------------------------------------------------------
// 预览计数（与向导顶部「有效 / 错误 / 已排除 / 重复」一致）
// ---------------------------------------------------------------------------------------------

/** 预览行统计。 */
internal data class PreviewCounts(
    val valid: Int = 0,
    val invalid: Int = 0,
    val excluded: Int = 0,
    val duplicates: Int = 0,
) {
    companion object {
        val EMPTY: PreviewCounts = PreviewCounts()
    }
}

internal fun countValidRows(items: List<ImportItem>): Int = items.count { it.isValid }

internal fun countInvalidRows(items: List<ImportItem>): Int = items.count { it.error.isNotEmpty() }

internal fun countExcludedRows(items: List<ImportItem>): Int =
    items.count { it.error.isEmpty() && it.exclude }

internal fun countDuplicateRows(items: List<ImportItem>, duplicates: Set<Int>): Int =
    items.indices.count { i -> i in duplicates && items[i].error.isEmpty() && !items[i].exclude }

internal fun previewCounts(items: List<ImportItem>, duplicates: Set<Int>): PreviewCounts = PreviewCounts(
    valid = countValidRows(items),
    invalid = countInvalidRows(items),
    excluded = countExcludedRows(items),
    duplicates = countDuplicateRows(items, duplicates),
)
