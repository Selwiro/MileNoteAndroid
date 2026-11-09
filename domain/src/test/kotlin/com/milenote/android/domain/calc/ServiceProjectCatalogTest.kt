package com.milenote.android.domain.calc

import com.milenote.android.domain.model.POWERTRAIN_EV
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

    // ---- 纯电项目库 ----

    @Test
    fun `ev maintenance group has ev projects and no fuel only items`() {
        val evLedger = blankLedger(POWERTRAIN_EV)
        val options = serviceProjectOptions(evLedger, "保养")
        assertTrue(options.contains("三电诊断"))
        assertTrue(options.contains("空调滤芯"))
        assertTrue(options.contains("刹车油"))
        assertTrue(options.contains("冷却液"))
        assertTrue(options.contains("轮胎"))
        listOf("机油机滤", "空气滤芯", "灰滤", "火花塞", "冷却模块", "变速器油", "皮带").forEach {
            assertFalse("纯电保养组不应含 $it", options.contains(it))
        }
        // 分组由 6 项方案去掉首保派生 = 5 项
        val group = serviceProjectGroups(ev = true).first { it.title == "保养" }
        assertEquals(5, group.projects.size)
        assertEquals(listOf("三电诊断", "空调滤芯", "刹车油", "冷却液", "轮胎"), group.projects)
        // 燃油组保持 11 项且不含纯电专属项
        val fuelGroup = serviceProjectGroups(ev = false).first { it.title == "保养" }
        assertEquals(11, fuelGroup.projects.size)
        assertFalse(fuelGroup.projects.contains("三电诊断"))
    }

    @Test
    fun `ev project options drop fuel only history and keep other categories`() {
        val evLedger = blankLedger(POWERTRAIN_EV).copy(
            serviceProjects = listOf("机油机滤", "天窗轨道润滑"),
            records = listOf(
                Record(
                    id = "old-fuel",
                    date = "2026-01-01",
                    category = "保养",
                    amount = 480.0,
                    projects = "机油机滤、三电诊断",
                ),
                Record(id = "repair", date = "2026-01-02", category = "维修", amount = 80.0, projects = "补胎"),
            ),
        )
        val options = serviceProjectOptions(evLedger, "保养")
        assertFalse(options.contains("机油机滤"))
        assertTrue(options.contains("三电诊断"))
        assertTrue(options.contains("天窗轨道润滑"))

        // 维修组与动力类型无关
        val repair = serviceProjectOptions(evLedger, "维修")
        assertTrue(repair.contains("补胎"))
        assertFalse(repair.contains("三电诊断"))

        // 不传分类时也不出现燃油专属项
        val all = serviceProjectOptions(evLedger)
        assertFalse(all.contains("机油机滤"))
        assertTrue(all.contains("三电诊断"))
    }
}
