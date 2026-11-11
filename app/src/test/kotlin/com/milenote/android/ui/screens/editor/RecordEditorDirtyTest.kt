package com.milenote.android.ui.screens.editor

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.ui.EditorTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页返回丢弃确认（P2）依赖的「是否有未保存改动」判断：
 * 新增记录初始为空、编辑既有记录初始与回填值一致，都算干净；改任一字段即算脏。
 */
class RecordEditorDirtyTest {

    private val ledger = blankLedger()

    @Test
    fun `new record starts clean and becomes dirty after typing`() {
        val form = RecordEditorState(ledger, EditorTarget(category = "停车费"))
        assertFalse(form.isDirty)

        form.amount = "25"
        assertTrue(form.isDirty)
    }

    @Test
    fun `editing an unchanged record is clean`() {
        val record = Record(
            id = "r1",
            date = "2026-11-01",
            category = "停车费",
            amount = 25.0,
            note = "商场",
        )
        val form = RecordEditorState(
            ledger = ledger.copy(records = listOf(record)),
            target = EditorTarget(record = record, category = record.category),
        )
        assertFalse(form.isDirty)

        form.note = "改过的备注"
        assertTrue(form.isDirty)
    }

    @Test
    fun `discard decision survives a revert`() {
        val form = RecordEditorState(ledger, EditorTarget(category = "停车费"))
        form.amount = "25"
        assertTrue(form.isDirty)

        form.amount = ""
        assertFalse(form.isDirty)
    }
}
