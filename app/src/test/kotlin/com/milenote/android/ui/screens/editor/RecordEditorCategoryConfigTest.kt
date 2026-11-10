package com.milenote.android.ui.screens.editor

import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.I18n
import com.milenote.android.ui.EditorTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 分类 / 项目库的改名与隐藏对**编辑器**的影响（接口约定见 `CategoryConfig`）：
 * 隐藏只砍新建候选，改名只换显示名，两者都不改写历史记录里的分类值与项目文字。
 */
class RecordEditorCategoryConfigTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    private fun ledger(
        ev: Boolean = false,
        records: List<Record> = emptyList(),
        hiddenCategories: List<String> = emptyList(),
        categoryLabels: Map<String, String> = emptyMap(),
        hiddenServiceProjects: Map<String, List<String>> = emptyMap(),
        serviceProjectLabels: Map<String, String> = emptyMap(),
    ): Ledger = Ledger(
        id = if (ev) "car-ev" else "car-fuel",
        vehicle = Vehicle(
            name = if (ev) "纯电车" else "燃油车",
            powertrain = if (ev) POWERTRAIN_EV else null,
        ),
        records = records,
        hiddenCategories = hiddenCategories.takeIf { it.isNotEmpty() },
        categoryLabels = categoryLabels.takeIf { it.isNotEmpty() },
        hiddenServiceProjects = hiddenServiceProjects.takeIf { it.isNotEmpty() },
        serviceProjectLabels = serviceProjectLabels.takeIf { it.isNotEmpty() },
    )

    @Test
    fun `hidden category disappears from new record options`() {
        val form = RecordEditorState(
            ledger(hiddenCategories = listOf("罚单")),
            EditorTarget(),
        )
        assertFalse(form.categoryOptions.contains("罚单"))
        assertTrue(form.categoryOptions.contains("停车费"))
        assertTrue(form.categoryOptions.contains("汽车用品"))
    }

    @Test
    fun `protected category stays in options even if written to hidden`() {
        val form = RecordEditorState(
            ledger(hiddenCategories = listOf("油费", "保养")),
            EditorTarget(),
        )
        assertTrue(form.categoryOptions.contains("油费"))
        assertTrue(form.categoryOptions.contains("保养"))
    }

    @Test
    fun `editing a record whose category was hidden keeps the chip and the stored value`() {
        val record = Record(id = "r1", date = "2026-01-01", category = "罚单", amount = 100.0)
        val form = RecordEditorState(
            ledger(records = listOf(record), hiddenCategories = listOf("罚单")),
            EditorTarget(record = record),
        )
        assertEquals("罚单", form.category)
        assertTrue(form.categoryOptions.contains("罚单"))
        // 保存时仍按账本存储值写回，不因「已隐藏」被改写或丢弃。
        assertEquals("罚单", form.buildRecord().category)
    }

    @Test
    fun `hidden project drops from the picker but a selected one stays visible`() {
        val record = Record(
            id = "r1",
            date = "2026-01-01",
            category = "保养",
            amount = 300.0,
            projects = "机油机滤、轮胎",
        )
        val form = RecordEditorState(
            ledger(
                records = listOf(record),
                hiddenServiceProjects = mapOf("保养" to listOf("空调滤芯", "机油机滤")),
            ),
            EditorTarget(record = record, category = "保养"),
        )
        assertFalse(form.projectOptions.contains("空调滤芯"))
        // 已选中的项目即使被隐藏也要保留，否则编辑既有记录会丢勾选。
        assertTrue(form.projectOptions.contains("机油机滤"))
        assertEquals("机油机滤、轮胎", form.buildRecord().projects)
    }

    @Test
    fun `renamed category and project only change the display name`() {
        val fuel = ledger(
            categoryLabels = mapOf("保养" to "养护", "油费" to "能源费"),
            serviceProjectLabels = mapOf("机油机滤" to "机油+机滤"),
        )
        assertEquals("养护", DataLabels.categoryFor(fuel, "保养", EnergyProfile.FUEL_ONLY))
        assertEquals("能源费", DataLabels.categoryFor(fuel, "油费", EnergyProfile.FUEL_ONLY))
        assertEquals("机油+机滤", DataLabels.projectFor(fuel, "机油机滤"))
        // 未改名的项目仍然走内置英文表 / 原值
        assertEquals("补胎", DataLabels.projectFor(fuel, "补胎"))

        I18n.applyEnglish(true)
        // 自定义显示名在英文界面下原样显示（不改写数据值）
        assertEquals("养护", DataLabels.categoryFor(fuel, "保养", EnergyProfile.FUEL_ONLY))
    }

    @Test
    fun `energy category display still follows the powertrain profile after rename`() {
        val ev = ledger(ev = true)
        assertEquals("电费", DataLabels.categoryFor(ev, "油费", EnergyProfile.ELECTRIC_ONLY))
        assertEquals("能源", DataLabels.categoryFor(ev, "油费", EnergyProfile.DUAL))
        assertEquals("油费", DataLabels.categoryFor(ev, "油费", EnergyProfile.FUEL_ONLY))
    }
}
