package com.milenote.android.ui.screens.editor

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「记一笔」草稿的编解码（UX1 / B22）。
 *
 * 草稿要能完整往返：金额、分类、日期、里程、项目（顿号分隔）都不能丢，
 * 否则「继续编辑」恢复出来的是一份残缺表单，比不恢复更糟。
 */
class EditorDraftStoreTest {

    private fun record(): Record = Record(
        id = "r1",
        date = "2026-11-15",
        category = "保养",
        amount = 266.0,
        odometer = 43480.0,
        note = "换机油",
        location = "家门口的店",
        projects = "机油机滤、空气滤芯",
        full = true,
        missed = false,
    )

    @Test
    fun `draft round trip keeps every field`() {
        val draft = EditorDraft(
            carId = "car-1",
            editingId = "r1",
            record = record(),
            savedAt = 1_700_000_000_000L,
        )
        val decoded = EditorDraftStore.decodeDraft(EditorDraftStore.encodeDraft(draft))!!
        assertEquals("car-1", decoded.carId)
        assertEquals("r1", decoded.editingId)
        assertEquals(1_700_000_000_000L, decoded.savedAt)
        assertEquals("2026-11-15", decoded.record.date)
        assertEquals("保养", decoded.record.category)
        assertEquals(266.0, decoded.record.amount, 1e-9)
        assertEquals(43480.0, decoded.record.odometer!!, 1e-9)
        assertEquals("换机油", decoded.record.note)
        assertEquals("家门口的店", decoded.record.location)
        assertEquals("机油机滤、空气滤芯", decoded.record.projects)
    }

    @Test
    fun `garbage draft decodes to null instead of crashing`() {
        assertNull(EditorDraftStore.decodeDraft("not json"))
        assertNull(EditorDraftStore.decodeDraft("{}"))
    }

    @Test
    fun `new record draft has no editing id`() {
        val draft = EditorDraft(carId = "car-1", record = record(), savedAt = 1L)
        assertNull(draft.editingId)
        val decoded = EditorDraftStore.decodeDraft(EditorDraftStore.encodeDraft(draft))!!
        assertNull(decoded.editingId)
    }
}
