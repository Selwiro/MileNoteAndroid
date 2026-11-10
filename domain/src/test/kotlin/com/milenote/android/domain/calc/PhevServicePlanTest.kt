package com.milenote.android.domain.calc

import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.powertrainKey
import com.milenote.android.domain.phevLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHEV 保养三档：燃油/HEV 12 项、插混 13 项（燃油 12 + 三电诊断）、纯电 6 项，
 * 以及切档后的方案合并、提醒生成、项目库排除集与历史别名并集。
 */
class PhevServicePlanTest {

    @Test
    fun `three tiers keep twelve thirteen and six items`() {
        // 缺省 / 非法值 / HEV 都按燃油存，取 12 项
        assertEquals(12, defaultServicePlan(null).size)
        assertEquals(12, defaultServicePlan("fuel").size)
        assertEquals(12, defaultServicePlan("hev").size)
        assertEquals(13, defaultServicePlan(POWERTRAIN_PHEV).size)
        assertEquals(6, defaultServicePlan(POWERTRAIN_EV).size)
        // 旧布尔重载语义不变
        assertEquals(12, defaultServicePlan(false).size)
        assertEquals(6, defaultServicePlan(true).size)
    }

    @Test
    fun `phev plan is fuel plan plus ev diagnostics`() {
        val fuel = defaultServicePlan("fuel")
        val phev = defaultServicePlan(POWERTRAIN_PHEV)
        assertEquals(13, phev.size)
        assertEquals(fuel.map { it.key }.toSet() + "ev-diag", phev.map { it.key }.toSet())
        // key 集合是燃油超集：12 项燃油周期原样保留
        for (entry in fuel) {
            val actual = phev.first { it.key == entry.key }
            assertEquals(entry.title, actual.title)
            assertEquals(entry.intervalKm, actual.intervalKm)
            assertEquals(entry.intervalMonths, actual.intervalMonths)
        }
        // 首保完成判定项目 = 机油机滤 + 三电诊断
        assertEquals(listOf("机油机滤", "三电诊断"), phev.first { it.key == "first" }.projects)
        val evDiag = phev.first { it.key == "ev-diag" }
        assertEquals("三电诊断", evDiag.title)
        assertEquals(10000.0, evDiag.intervalKm!!, 1e-9)
        assertEquals(12, evDiag.intervalMonths)
        assertEquals(null, evDiag.firstOnly)
        // 非首保项 12 个，与燃油一致
        assertEquals(12, phev.count { it.firstOnly != true })
        assertEquals(11, fuel.count { it.firstOnly != true })
    }

    @Test
    fun `switching fuel plan to phev resets first service projects`() {
        val saved = listOf(
            PlanEntry(
                key = "first",
                title = "首保",
                intervalKm = 5000.0,
                intervalMonths = 3,
                firstOnly = true,
                projects = listOf("机油机滤"),
            ),
            PlanEntry(key = "oil", title = "机油机滤", intervalKm = 8000.0, intervalMonths = 6),
        )
        val resolved = resolveServicePlan(saved, POWERTRAIN_PHEV)
        // 只覆盖默认 key，计划仍为默认 13 项
        assertEquals(13, resolved.size)
        assertEquals(listOf("机油机滤", "三电诊断"), resolved.first { it.key == "first" }.projects)
        // 用户自定义周期保留
        assertEquals(8000.0, resolved.first { it.key == "oil" }.intervalKm!!, 1e-9)
        assertEquals(6, resolved.first { it.key == "oil" }.intervalMonths)

        // 纯电保存的首保项目切到 PHEV 同样重置
        val evSaved = listOf(
            PlanEntry(
                key = "first",
                title = "首保",
                intervalKm = 5000.0,
                intervalMonths = 3,
                firstOnly = true,
                projects = listOf("三电诊断"),
            ),
        )
        assertEquals(
            listOf("机油机滤", "三电诊断"),
            resolveServicePlan(evSaved, POWERTRAIN_PHEV).first { it.key == "first" }.projects,
        )

        // 用户真正自定义过的首保项目不被覆盖
        val custom = listOf(
            PlanEntry(key = "first", title = "首保", projects = listOf("四轮定位")),
        )
        assertEquals(
            listOf("四轮定位"),
            resolveServicePlan(custom, POWERTRAIN_PHEV).first { it.key == "first" }.projects,
        )
    }

    @Test
    fun `phev keeps both fuel and ev custom projects and drops nothing`() {
        val saved = listOf(
            // 燃油专属 key（PHEV 也有，保留）
            PlanEntry(key = "spark", title = "火花塞", intervalKm = 40000.0),
            // 纯电专属 key（PHEV 也有，保留）
            PlanEntry(key = "ev-diag", title = "三电诊断", intervalKm = 12000.0),
            // 自定义 key（追加）
            PlanEntry(key = "custom-x", title = "四轮定位", intervalKm = 20000.0),
        )
        val resolved = resolveServicePlan(saved, POWERTRAIN_PHEV)
        // 默认 13 项 + 自定义 key「四轮定位」= 14 项（PHEV 两类专属 key 都不丢弃）
        assertEquals(14, resolved.size)
        assertEquals(40000.0, resolved.first { it.key == "spark" }.intervalKm!!, 1e-9)
        assertEquals(12000.0, resolved.first { it.key == "ev-diag" }.intervalKm!!, 1e-9)
        assertEquals("四轮定位", resolved.first { it.key == "custom-x" }.title)
    }

    @Test
    fun `phev default reminders include thirteen items and first service note`() {
        val reminders = defaultServiceReminders("2026-01-01", 0.0, powertrain = POWERTRAIN_PHEV)
        assertEquals(13, reminders.size)
        assertEquals(12, defaultServiceReminders("2026-01-01", 0.0, false, POWERTRAIN_PHEV).size)
        assertEquals(12, defaultServiceReminders("2026-01-01", 0.0).size)
        val first = reminders.first { it.presetKey == "first" }
        assertEquals(listOf("机油机滤", "三电诊断"), first.projects)
        assertNotNull(first.note)
        assertTrue(first.note!!.contains("三电诊断"))
        assertEquals(10000.0, reminders.first { it.presetKey == "ev-diag" }.intervalKm!!, 1e-9)
    }

    @Test
    fun `phev project groups keep both categories and hide nothing`() {
        val group = serviceProjectGroups(POWERTRAIN_PHEV).first { it.title == "保养" }
        assertEquals(12, group.projects.size)
        assertTrue(group.projects.contains("机油机滤"))
        assertTrue(group.projects.contains("三电诊断"))
        assertTrue(group.projects.contains("空气滤芯"))
        // 燃油 / 纯电仍按各自档位排除
        val fuelGroup = serviceProjectGroups("fuel").first { it.title == "保养" }
        assertEquals(11, fuelGroup.projects.size)
        assertFalse(fuelGroup.projects.contains("三电诊断"))
        val evGroup = serviceProjectGroups(POWERTRAIN_EV).first { it.title == "保养" }
        assertEquals(5, evGroup.projects.size)
        assertFalse(evGroup.projects.contains("机油机滤"))
        // 旧布尔重载语义不变
        assertEquals(11, serviceProjectGroups(false).first { it.title == "保养" }.projects.size)
        assertEquals(5, serviceProjectGroups(true).first { it.title == "保养" }.projects.size)
    }

    @Test
    fun `phev project options keep fuel and ev items together`() {
        val ledger = phevLedger()
        val options = serviceProjectOptions(ledger, "保养")
        assertTrue(options.contains("机油机滤"))
        assertTrue(options.contains("三电诊断"))
        // PHEV 方案用燃油标题「制动液」，不出纯电的「刹车油」写法
        assertFalse(options.contains("刹车油"))
        // 维修组不受动力类型影响
        assertTrue(serviceProjectOptions(ledger, "维修").contains("补胎"))
        assertEquals("phev", ledger.vehicle.powertrainKey)
    }

    @Test
    fun `service title alias treats brake fluid names as one item`() {
        assertTrue(serviceTitleMatches("制动液", "刹车油"))
        assertTrue(serviceTitleMatches("刹车油", "制动液"))
        assertTrue(serviceTitleMatches("制动液", "制动液"))
        assertFalse(serviceTitleMatches("机油机滤", "制动液"))
    }
}
