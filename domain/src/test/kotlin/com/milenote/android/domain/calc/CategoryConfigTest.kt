package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分类/项目库的用户配置：隐藏只影响候选、改名只影响显示，历史记录与九类契约不动。 */
class CategoryConfigTest {

    @Test
    fun `pick options hides builtin categories but keeps protected and custom`() {
        val ledger = testLedger().copy(
            customCategories = listOf("洗车卡"),
            hiddenCategories = listOf("罚单", "停车费", "油费"), // 油费受保护，误写也不生效
        )
        val options = CategoryConfig.pickOptions(ledger)
        assertFalse(options.contains("罚单"))
        assertFalse(options.contains("停车费"))
        assertTrue(options.contains("油费"))
        assertTrue(options.contains("保险"))
        assertTrue(options.contains("保养"))
        assertTrue(options.contains("维修"))
        assertTrue(options.contains("洗车卡"))
        // 底层九类契约与统计口径不受隐藏影响
        assertEquals(9, Categories.ALL.size)
        assertEquals(Categories.ALL, Categories.options(ledger).take(9))
        assertTrue(CategoryConfig.isProtected("油费"))
        assertFalse(CategoryConfig.isProtected("停车费"))
    }

    @Test
    fun `renamed category and project only affect display`() {
        val ledger = testLedger().copy(
            categoryLabels = mapOf("保养" to "养护"),
            serviceProjectLabels = mapOf("机油机滤" to "机油+机滤"),
        )
        assertEquals("养护", CategoryConfig.categoryLabel(ledger, "保养"))
        assertEquals("油费", CategoryConfig.categoryLabel(ledger, "油费"))
        assertEquals("机油+机滤", CategoryConfig.projectLabel(ledger, "机油机滤"))
        assertEquals("补胎", CategoryConfig.projectLabel(ledger, "补胎"))
    }

    @Test
    fun `hidden projects disappear from candidates but history text stays`() {
        val ledger = testLedger(
            records = listOf(
                com.milenote.android.domain.model.Record(
                    id = "r1",
                    date = "2026-01-01",
                    category = "保养",
                    amount = 100.0,
                    projects = "机油机滤、轮胎",
                ),
            ),
        ).copy(hiddenServiceProjects = mapOf("保养" to listOf("机油机滤")))
        val options = serviceProjectOptions(ledger, "保养")
        assertFalse(options.contains("机油机滤"))
        assertTrue(options.contains("轮胎") || options.contains("空调滤芯"))
        // 历史记录里的项目文字不改写
        assertEquals("机油机滤、轮胎", ledger.records[0].projects)
        // 全部分类候选同样过滤
        assertFalse(serviceProjectOptions(ledger).contains("机油机滤"))
    }

    @Test
    fun `category config fields survive backup round trip`() {
        val car = blankLedger().copy(
            id = "a",
            customCategories = listOf("洗车卡"),
            hiddenCategories = listOf("罚单", "汽车用品"),
            categoryLabels = mapOf("保养" to "养护"),
            hiddenServiceProjects = mapOf("保养" to listOf("空调滤芯")),
            serviceProjectLabels = mapOf("补胎" to "轮胎修补"),
        )
        val garage = Garage(version = 2, activeId = "a", theme = "blue", cars = listOf(car))
        val text = BackupCodec.encode(garage)
        val decoded = BackupCodec.decode(text).cars[0]
        assertEquals(listOf("罚单", "汽车用品"), decoded.hiddenCategories)
        assertEquals(mapOf("保养" to "养护"), decoded.categoryLabels)
        assertEquals(mapOf("保养" to listOf("空调滤芯")), decoded.hiddenServiceProjects)
        assertEquals(mapOf("补胎" to "轮胎修补"), decoded.serviceProjectLabels)
    }

    @Test
    fun `merge unions hidden sets and keeps local labels`() {
        val existing = blankLedger().copy(
            id = "a",
            hiddenCategories = listOf("罚单"),
            categoryLabels = mapOf("保养" to "养护"),
        )
        val incoming = blankLedger().copy(
            id = "a",
            hiddenCategories = listOf("停车费"),
            categoryLabels = mapOf("保养" to "维护", "维修" to "修车"),
        )
        val merged = GarageOps.mergeLedger(existing, incoming)
        assertEquals(setOf("罚单", "停车费"), merged.hiddenCategories.orEmpty().toSet())
        // 本机已有显示名优先
        assertEquals("养护", merged.categoryLabels.orEmpty()["保养"])
        assertEquals("修车", merged.categoryLabels.orEmpty()["维修"])
    }
}
