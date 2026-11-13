package com.milenote.android.ui.screens.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 费用明细多选集合规则。 */
class RecordsSelectionTest {

    @Test
    fun `toggle adds and removes a record`() {
        val once = toggleSelection(emptySet(), "a")
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), toggleSelection(once, "a"))
        assertEquals(setOf("a", "b"), toggleSelection(once, "b"))
    }

    @Test
    fun `prune drops records hidden by the current filter`() {
        assertEquals(setOf("b"), pruneSelection(setOf("a", "b"), setOf("b", "c")))
        assertEquals(emptySet<String>(), pruneSelection(setOf("a"), emptySet()))
    }

    @Test
    fun `select all ignores an empty result list`() {
        assertFalse(isAllSelected(setOf("a"), emptySet()))
        assertFalse(isAllSelected(emptySet(), setOf("a", "b")))
        assertTrue(isAllSelected(setOf("a", "b"), setOf("a", "b")))
        assertTrue(isAllSelected(setOf("a", "b", "c"), setOf("a", "b")))
    }

    @Test
    fun `toggle select all fills the visible list or clears it`() {
        assertEquals(setOf("a", "b"), toggleSelectAll(emptySet(), setOf("a", "b")))
        assertEquals(setOf("a", "b"), toggleSelectAll(setOf("a"), setOf("a", "b")))
        assertEquals(emptySet<String>(), toggleSelectAll(setOf("a", "b"), setOf("a", "b")))
        assertEquals(emptySet<String>(), toggleSelectAll(emptySet(), emptySet()))
    }
}
