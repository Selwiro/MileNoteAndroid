package com.milenote.android.ui.screens.importer

import com.milenote.android.domain.import.ImportItem
import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入预览的逐行修改与纯函数预览生成（ImportRowEdits.kt）。
 *
 * 覆盖：编辑金额/日期/分类/数值字段 → 重新校验、非法日期/负里程 → 错误、
 * 自定义分类、按原始行号生效、预览重建（换映射 / 换默认分类 / 勾选排除）后编辑仍然生效、
 * 判重（含跨来源油费只按同来源判重）、油费口径冲突与纯电跳过、预览计数。
 */
class ImportRowEditsTest {

    // ---------- 夹具 ----------

    private fun record(
        amount: Double = 12.5,
        date: String = "2026-01-05",
        category: String = "停车费",
        source: String = "钱迹",
    ): Record = Record(
        id = "imp-$date-$amount",
        date = date,
        category = category,
        amount = amount,
        source = source,
        kind = "expense",
    )

    private fun item(record: Record, row: Int = 2, error: String = ""): ImportItem =
        ImportItem(row = row, record = record, error = error)

    /** 两行钱迹：第 2 行合法、第 3 行金额为 0（无效）。 */
    private val rows: List<List<Any?>> = listOf(
        listOf("2026-01-05", "停车费", 12.5, "q1"),
        listOf("2026-01-06", "停车费", 0.0, "q2"),
    )

    private val mapping: Map<String, Int?> = mapOf(
        "date" to 0,
        "category" to 1,
        "amount" to 2,
        "sourceId" to 3,
    )

    private fun preview(
        edits: Map<Int, ImportRowEdit> = emptyMap(),
        excludedRows: Set<Int> = emptySet(),
        existing: List<Record> = emptyList(),
        ev: Boolean = false,
        map: Map<String, Int?> = mapping,
        defaultCategory: String = "",
        customCategories: List<String> = emptyList(),
        sourceKey: String = "qianji",
    ): ImportPreview = buildImportPreview(
        rows = rows,
        mapping = map,
        sourceKey = sourceKey,
        defaultCategory = defaultCategory,
        customCategories = customCategories,
        ev = ev,
        edits = edits,
        excludedRows = excludedRows,
        existingRecords = existing,
    )

    // ---------- 单行编辑 ----------

    @Test
    fun `editing amount turns an invalid row into a valid one`() {
        val invalid = item(record(amount = 0.0), error = "金额必须大于 0")
        assertFalse(invalid.error.isEmpty())

        val fixed = applyRowEdit(invalid, ImportRowEdit(amount = "88.5"))

        assertEquals(88.5, fixed.record.amount, 1e-9)
        assertTrue(fixed.error.isEmpty())
    }

    @Test
    fun `editing an illegal date keeps the row in error`() {
        val fixed = applyRowEdit(item(record()), ImportRowEdit(date = "2026-02-30"))

        assertEquals("", fixed.record.date)
        assertTrue(fixed.error.isNotEmpty())
    }

    @Test
    fun `editing date accepts loose formats and normalizes to iso`() {
        val fixed = applyRowEdit(item(record()), ImportRowEdit(date = "2026/2/3"))

        assertEquals("2026-02-03", fixed.record.date)
        assertTrue(fixed.error.isEmpty())
    }

    @Test
    fun `editing category uses standard categories without custom config`() {
        val fixed = applyRowEdit(item(record(category = "")), ImportRowEdit(category = "维修"))

        assertEquals("维修", fixed.record.category)
        assertTrue(fixed.error.isEmpty())
    }

    @Test
    fun `custom category is valid only when the ledger declares it`() {
        val base = item(record(category = ""))
        // 「改装」不是 9 类标准分类，也不匹配任何关键词，必须靠本车自定义分类才合法。
        assertTrue(applyRowEdit(base, ImportRowEdit(category = "改装")).error.isNotEmpty())
        assertTrue(
            applyRowEdit(base, ImportRowEdit(category = "改装"), customCategories = listOf("改装")).error.isEmpty(),
        )
    }

    @Test
    fun `negative numeric field keeps the row in error and blank clears it`() {
        val negative = applyRowEdit(item(record()), ImportRowEdit(odometer = "-100"))
        assertTrue(negative.error.isNotEmpty())

        val cleared = applyRowEdit(item(record()), ImportRowEdit(payable = "88", discount = "8"))
        assertEquals(88.0, cleared.record.payable)
        assertTrue(cleared.error.isEmpty())

        val blank = applyRowEdit(cleared, ImportRowEdit(payable = ""))
        assertNull(blank.record.payable)
    }

    @Test
    fun `edit only touches the given fields and keeps others`() {
        val original = item(record().copy(note = "原备注", odometer = 12345.0, full = true))
        val edited = applyRowEdit(original, ImportRowEdit(amount = "99"))

        assertEquals(99.0, edited.record.amount, 1e-9)
        assertEquals("原备注", edited.record.note)
        assertEquals(12345.0, edited.record.odometer)
        assertTrue(edited.record.full)
        assertEquals(original.record.id, edited.record.id)
    }

    @Test
    fun `row edits are keyed by source row number not list index`() {
        val items = listOf(
            item(record(amount = 1.0, date = "2026-01-01"), row = 2),
            item(record(amount = 2.0, date = "2026-01-02"), row = 3),
        )

        val applied = applyRowEdits(items, mapOf(3 to ImportRowEdit(amount = "20")))

        assertEquals(1.0, applied[0].record.amount, 1e-9)
        assertEquals(20.0, applied[1].record.amount, 1e-9)
    }

    @Test
    fun `empty edit is a no-op`() {
        val base = item(record())
        assertSame(base, applyRowEdit(base, null))
        assertSame(base, applyRowEdit(base, ImportRowEdit()))
    }

    @Test
    fun `type and currency blockers survive a row edit`() {
        val skipped = item(record(), error = "非支出记录，已跳过")
        val edited = applyRowEdit(skipped, ImportRowEdit(amount = "50"))

        assertEquals("非支出记录，已跳过", edited.error)
        assertEquals(50.0, edited.record.amount, 1e-9)
    }

    // ---------- 预览生成（normalize → 编辑 → 排除 → 判重 → 油费冲突） ----------

    @Test
    fun `preview revalidates rows after edits`() {
        val before = preview()
        assertTrue(before.items[1].error.isNotEmpty())
        assertEquals(1, countValidRows(before.items))
        assertEquals(1, countInvalidRows(before.items))

        val after = preview(edits = mapOf(3 to ImportRowEdit(amount = "30")))
        assertEquals(30.0, after.items[1].record.amount, 1e-9)
        assertTrue(after.items[1].error.isEmpty())
        assertEquals(2, previewCounts(after.items, after.duplicates).valid)
        assertEquals(0, previewCounts(after.items, after.duplicates).invalid)
    }

    @Test
    fun `edits survive a preview rebuild with a new mapping and default category`() {
        val edits = mapOf(3 to ImportRowEdit(amount = "30"))
        val first = preview(edits = edits)
        assertEquals(30.0, first.items[1].record.amount, 1e-9)

        // 改字段映射（多映射备注列）+ 换默认分类 + 模拟换工作表后重新生成预览。
        val rebuilt = preview(
            edits = edits,
            map = mapping + ("note" to 1),
            defaultCategory = "改装",
        )

        assertEquals(30.0, rebuilt.items[1].record.amount, 1e-9)
        assertTrue(rebuilt.items[1].error.isEmpty())
    }

    @Test
    fun `excluded rows are flagged and counted`() {
        val result = preview(excludedRows = setOf(0))

        assertTrue(result.items[0].exclude)
        assertEquals(0, countValidRows(result.items))
        assertEquals(1, countExcludedRows(result.items))
        assertEquals(PreviewCounts(valid = 0, invalid = 1, excluded = 1, duplicates = 0),
            previewCounts(result.items, result.duplicates))
    }

    @Test
    fun `markRowExcluded toggles a single row`() {
        val list = preview().items
        val excluded = markRowExcluded(list, 0, exclude = true)
        assertTrue(excluded[0].exclude)
        assertFalse(excluded[1].exclude)

        val restored = markRowExcluded(excluded, 0, exclude = false)
        assertFalse(restored[0].exclude)
    }

    @Test
    fun `same source and source id is reported as duplicate`() {
        val existing = listOf(
            Record(
                id = "old",
                date = "2026-01-05",
                category = "停车费",
                amount = 12.5,
                source = "钱迹",
                sourceId = "q1",
                kind = "expense",
            ),
        )
        val result = preview(existing = existing)

        assertTrue(0 in result.duplicates)
        assertEquals(1, countDuplicateRows(result.items, result.duplicates))
    }

    @Test
    fun `fuel duplicates are scoped to the same source`() {
        // 已有小熊油费与导入的钱迹油费业务签名相同（同日期/金额/升数），
        // 但跨来源油费在合并时只按同来源判重，预览不应把它标成「重复会跳过」。
        val existing = listOf(
            Record(
                id = "old-bear",
                date = "2026-01-05",
                category = "油费",
                amount = 300.0,
                liters = 40.0,
                source = "小熊油耗",
                kind = "expense",
            ),
        )
        val fuelRows: List<List<Any?>> = listOf(listOf("2026-01-05", "加油", 300.0, 40.0))
        val result = buildImportPreview(
            rows = fuelRows,
            mapping = mapOf("date" to 0, "category" to 1, "amount" to 2, "liters" to 3),
            sourceKey = "qianji",
            defaultCategory = "",
            customCategories = emptyList(),
            ev = false,
            existingRecords = existing,
        )

        assertFalse(0 in result.duplicates)
        assertEquals(0, countDuplicateRows(result.items, result.duplicates))
    }

    @Test
    fun `fuel conflict is computed for fuel cars and skipped for ev`() {
        val existing = listOf(
            Record(
                id = "old-bear",
                date = "2025-12-01",
                category = "油费",
                amount = 280.0,
                liters = 38.0,
                source = "小熊油耗",
                kind = "expense",
            ),
        )
        val fuelRows: List<List<Any?>> = listOf(listOf("2026-01-05", "加油", 300.0, 40.0))
        fun build(ev: Boolean): ImportPreview = buildImportPreview(
            rows = fuelRows,
            mapping = mapOf("date" to 0, "category" to 1, "amount" to 2, "liters" to 3),
            sourceKey = "qianji",
            defaultCategory = "",
            customCategories = emptyList(),
            ev = ev,
            existingRecords = existing,
        )

        val fuel = build(ev = false)
        assertEquals(280.0, fuel.fuelConflict?.bear ?: 0.0, 1e-9)
        assertEquals(300.0, fuel.fuelConflict?.qianji ?: 0.0, 1e-9)
        assertNull(build(ev = true).fuelConflict)
    }
}
