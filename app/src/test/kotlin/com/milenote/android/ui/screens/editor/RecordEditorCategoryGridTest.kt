package com.milenote.android.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分类网格列数随系统字号收窄（B38）。
 *
 * 旧实现固定 5 列 + 单行省略号：2.0× 下「汽车美容」只剩「汽车…」。
 * 三档边界固定为 ≤1.3× 五列 / 1.3–1.8× 四列 / ≥1.8× 三列，扩档只会让格子更宽，不会更窄。
 */
class RecordEditorCategoryGridTest {

    @Test
    fun `default and slightly enlarged font keeps five columns`() {
        assertEquals(5, categoryGridColumns(1.0f))
        assertEquals(5, categoryGridColumns(1.15f))
        assertEquals(5, categoryGridColumns(1.3f))
    }

    @Test
    fun `medium enlargement drops to four columns`() {
        assertEquals(4, categoryGridColumns(1.31f))
        assertEquals(4, categoryGridColumns(1.5f))
        assertEquals(4, categoryGridColumns(1.79f))
    }

    @Test
    fun `large font uses three columns so four-character names fit`() {
        assertEquals(3, categoryGridColumns(1.8f))
        assertEquals(3, categoryGridColumns(2.0f))
        assertEquals(3, categoryGridColumns(2.5f))
    }

    @Test
    fun `broken font scale falls back to the default grid`() {
        // 异常值不能算出 0 列（chunked(0) 会抛异常），按默认 5 列兜底。
        assertEquals(5, categoryGridColumns(Float.NaN))
        assertEquals(5, categoryGridColumns(0f))
        assertEquals(5, categoryGridColumns(-1f))
    }
}
