package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「保险」项目分组：默认三项、自定义合并、历史记录合并，且不影响其它分类。 */
class ServiceProjectCatalogTest {

    @Test
    fun `insurance group exposes three built in projects`() {
        val options = serviceProjectOptions(blankLedger(), "保险")
        assertEquals(listOf("交强险", "车损险", "三者险"), options)

        val group = SERVICE_PROJECT_GROUPS.first { it.title == "保险" }
        assertEquals(listOf("交强险", "车损险", "三者险"), group.projects)
        assertEquals(setOf("保养", "维修", "汽车美容", "保险"), PROJECT_CATEGORIES)
        assertTrue(PROJECT_CATEGORIES.contains("保险"))
    }

    @Test
    fun `insurance custom projects merge and dedupe`() {
        val ledger = blankLedger().copy(
            serviceProjectsByCategory = mapOf(
                "保险" to listOf("交强险", "驾乘险", "驾乘险"),
                "维修" to listOf("车窗修复"),
            ),
        )
        val options = serviceProjectOptions(ledger, "保险")
        assertEquals(listOf("交强险", "车损险", "三者险", "驾乘险"), options)
        assertFalse(options.contains("车窗修复"))
        assertFalse(options.contains("补胎"))
    }

    @Test
    fun `insurance history records merge and other categories unchanged`() {
        val ledger = blankLedger().copy(
            records = listOf(
                Record(
                    id = "i1",
                    date = "2026-01-01",
                    category = "保险",
                    amount = 3280.0,
                    projects = "交强险、玻璃险",
                ),
                Record(
                    id = "m1",
                    date = "2026-01-02",
                    category = "维修",
                    amount = 80.0,
                    projects = "补胎",
                ),
            ),
        )
        val insurance = serviceProjectOptions(ledger, "保险")
        assertTrue(insurance.contains("玻璃险"))
        assertEquals(1, insurance.count { it == "交强险" })
        assertFalse(insurance.contains("补胎"))

        // 其他分类行为不变：保险项目不会串入保养 / 维修 / 美容项目库
        val maintenance = serviceProjectOptions(ledger, "保养")
        val repair = serviceProjectOptions(ledger, "维修")
        val beauty = serviceProjectOptions(ledger, "汽车美容")
        assertFalse(maintenance.contains("交强险"))
        assertFalse(repair.contains("交强险"))
        assertFalse(beauty.contains("交强险"))
        assertTrue(repair.contains("补胎"))

        // 不传分类时汇总所有分组项目
        val all = serviceProjectOptions(ledger)
        assertTrue(all.contains("交强险"))
        assertTrue(all.contains("玻璃险"))
        assertTrue(all.contains("补胎"))
    }
}
