package com.milenote.android.ui.screens.dashboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「支出」卡「分类」视图的聚合：跨月合并、金额降序、固定顺序兜底、忽略 0 值。 */
class CategoryTotalsTest {

    private fun bar(vararg categories: Pair<String, Double>): MonthBar = MonthBar(
        key = "2026-11",
        label = "11 月",
        total = categories.sumOf { it.second },
        categories = categories.map { ChartCategory(it.first, it.second, Color.Black) },
    )

    @Test
    fun `merges the same category across months and sorts by amount`() {
        val totals = categoryTotalsOf(
            listOf(
                bar("油费" to 100.0, "保养" to 300.0),
                bar("油费" to 250.0, "停车费" to 20.0),
            ),
        )
        assertEquals(listOf("油费", "保养", "停车费"), totals.map { it.name })
        assertEquals(350.0, totals.first().value, 0.0001)
    }

    @Test
    fun `ignores zero and negative segments`() {
        val totals = categoryTotalsOf(listOf(bar("油费" to 100.0, "保险" to 0.0, "罚单" to -5.0)))
        assertEquals(listOf("油费"), totals.map { it.name })
    }

    @Test
    fun `empty bars produce an empty ranking`() {
        assertTrue(categoryTotalsOf(emptyList()).isEmpty())
        assertTrue(categoryTotalsOf(listOf(bar())).isEmpty())
    }

    @Test
    fun `equal amounts keep the built-in category order`() {
        val totals = categoryTotalsOf(listOf(bar("罚单" to 50.0, "油费" to 50.0, "保养" to 50.0)))
        assertEquals(listOf("油费", "保养", "罚单"), totals.map { it.name })
    }

    @Test
    fun `custom categories come after the nine built-ins`() {
        val totals = categoryTotalsOf(listOf(bar("洗车卡" to 80.0, "美容套餐" to 80.0, "保险" to 80.0)))
        assertEquals(listOf("保险", "洗车卡", "美容套餐"), totals.map { it.name })
    }

    @Test
    fun `legacy maintenance alias keeps the maintenance slot`() {
        // 历史账本里可能存「维修保养」，聚合时按「保养」归到同一个固定位置（名称保持原样）。
        val totals = categoryTotalsOf(listOf(bar("维修保养" to 40.0, "油费" to 40.0)))
        assertEquals(listOf("油费", "维修保养"), totals.map { it.name })
    }
}
