package com.milenote.android.domain.calc

import com.milenote.android.domain.NINE_CATEGORIES
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分类顺序、关键词归类、自定义分类、配色。 */
class CategoriesTest {

    @Test
    fun `nine categories in fixed order`() {
        assertEquals(
            listOf("油费", "保险", "保养", "过路费", "停车费", "维修", "罚单", "汽车用品", "汽车美容"),
            Categories.ALL,
        )
        assertEquals(NINE_CATEGORIES, Categories.ALL.toSet())
    }

    @Test
    fun `keyword classification order`() {
        assertEquals("油费", Categories.categoryOf("加油"))
        assertEquals("油费", Categories.categoryOf("92号汽油"))
        assertEquals("保险", Categories.categoryOf("交强险"))
        assertEquals("保养", Categories.categoryOf("换油"))
        assertEquals("过路费", Categories.categoryOf("ETC通行费"))
        assertEquals("停车费", Categories.categoryOf("停车"))
        assertEquals("维修", Categories.categoryOf("补胎"))
        assertEquals("罚单", Categories.categoryOf("违章罚款"))
        assertEquals("汽车美容", Categories.categoryOf("洗车打蜡"))
        assertEquals("汽车用品", Categories.categoryOf("汽车装饰"))
        assertEquals("", Categories.categoryOf("餐饮"))
        assertEquals("保养", Categories.normalize("维修保养"))
        assertEquals("充电费", Categories.categoryOf("充电费", listOf("充电费")))
    }

    @Test
    fun `category options merge custom and record categories`() {
        val ledger = testLedger(
            records = listOf(Record(id = "a", date = "2026-01-01", category = "维修保养", amount = 1.0)),
        ).copy(customCategories = listOf("充电费"))
        val options = Categories.options(ledger)
        assertEquals(10, options.size)
        assertEquals("保养", options[2])
        assertTrue(options.contains("充电费"))
        assertFalse(options.contains("维修保养"))
    }

    @Test
    fun `theme colors and custom hash color`() {
        assertEquals("#55758b", Categories.color("blue", "油费"))
        assertEquals("#7e9eaf", Categories.color("blue", "保险"))
        assertEquals("#b58ac4", Categories.color("blue", "汽车美容"))
        assertEquals("#55758b", Categories.color(null, "油费"))
        assertEquals("#55758b", Categories.color("不存在的皮肤", "油费"))
        assertEquals("#789657", Categories.color("forest", "油费"))
        // 自定义分类：hash*31 累加后对 360 取模
        assertEquals("hsl(353 40% 56%)", Categories.color("blue", "充电费"))
        assertEquals(5, Categories.THEMES.size)
        assertEquals("里程蓝", Categories.THEMES.getValue("blue").name)
    }
}
