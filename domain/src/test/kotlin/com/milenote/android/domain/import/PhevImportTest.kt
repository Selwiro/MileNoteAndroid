package com.milenote.android.domain.import

import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入的行级能量种类（PHEV 关键路径）：同一张表里加油行与充电行各自只产一种能量字段，
 * 小熊来源只产加油行；PHEV 备注识别取燃油标题 + 三电诊断并集。
 */
class PhevImportTest {

    @Test
    fun `phev table splits fuel and charging rows by columns`() {
        val rows = listOf(
            listOf("2026-11-01", "油费", 300, 40, null, "95#"),
            listOf("2026-11-02", "油费", 30, null, 45, "95#"),
        )
        val items = normalizeImport(
            rows = rows,
            map = detectMap(listOf("日期", "分类", "金额", "加油量", "充电量", "燃油标号")),
            source = "qianji",
            powertrain = POWERTRAIN_PHEV,
        )
        assertTrue(items.all { it.error.isEmpty() })

        val fuel = items[0].record
        assertEquals(40.0, fuel.liters!!, 1e-9)
        assertNull(fuel.kwh)
        assertEquals("95#", fuel.fuelGrade)

        // 充电行即使标号列有值也清空 fuelGrade（不是加油记录）
        val charging = items[1].record
        assertEquals(45.0, charging.kwh!!, 1e-9)
        assertNull(charging.liters)
        assertNull(charging.fuelGrade)
    }

    @Test
    fun `charging hint in text marks the row electric without kwh column`() {
        val items = normalizeImport(
            rows = listOf(
                listOf("2026-11-01", "油费", 36, "快充 30 度"),
                listOf("2026-11-02", "油费", 300, "加满 92#"),
            ),
            map = detectMap(listOf("日期", "分类", "金额", "备注")),
            source = "qianji",
            powertrain = POWERTRAIN_PHEV,
        )
        assertTrue(items.all { it.error.isEmpty() })
        // 「快充」→ 充电行：不写加油量、不写标号
        assertNull(items[0].record.liters)
        assertNull(items[0].record.fuelGrade)
        // 不含充电提示 → 加油行（没有量列传空，不臆造升数）
        assertNull(items[1].record.kwh)
        assertNull(items[1].record.liters)
    }

    @Test
    fun `bear source only produces fuel rows`() {
        val items = normalizeImport(
            rows = listOf(listOf("2026-11-01", 1000, 30, 250, 230, "是", "是", -1)),
            map = detectMap(listOf("日期时间", "总里程", "加油量", "机显金额", "实付金额", "加满", "漏记", "油耗")),
            source = "bear",
            powertrain = POWERTRAIN_PHEV,
        )
        val record = items[0].record
        assertEquals(30.0, record.liters!!, 1e-9)
        assertNull(record.kwh)
        assertEquals("小熊油耗", record.source)
    }

    @Test
    fun `ev powertrain treats rows without energy columns as charging`() {
        val items = normalizeImport(
            rows = listOf(listOf("2026-11-01", "油费", 36)),
            map = detectMap(listOf("日期", "分类", "金额")),
            source = "qianji",
            powertrain = POWERTRAIN_EV,
        )
        assertNull(items[0].record.liters)
        assertNull(items[0].record.kwh)
        assertEquals("", items[0].error)
    }

    @Test
    fun `phev service note inference unions fuel and ev titles`() {
        val phev = inferServiceNote(
            Record(category = "保养", note = "表显18000km，机油机滤、三电诊断、刹车油"),
            powertrain = POWERTRAIN_PHEV,
        )
        assertEquals(18000.0, phev.odometer!!, 1e-9)
        val projects = phev.projects!!
        assertTrue(projects.contains("机油机滤"))
        assertTrue(projects.contains("三电诊断"))
        // PHEV 方案沿用燃油标题「制动液」
        assertTrue(projects.contains("制动液"))
        assertFalse(projects.contains("刹车油"))
    }
}
