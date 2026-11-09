package com.milenote.android.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 分类显示名：燃油显示「油费」，纯电显示「电费 / Charging」；默认参数保持旧行为。 */
class DataLabelsTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `zh fuel category stays fuel`() {
        assertEquals("油费", DataLabels.category("油费"))
        assertEquals("油费", DataLabels.category("油费", ev = false))
    }

    @Test
    fun `zh ev category becomes electricity`() {
        assertEquals("电费", DataLabels.category("油费", ev = true))
        assertEquals("电费", DataLabels.energyCategory(true))
        assertEquals("油费", DataLabels.energyCategory(false))
    }

    @Test
    fun `en category maps fuel and charging`() {
        I18n.applyEnglish(true)
        assertEquals("Fuel", DataLabels.category("油费", ev = false))
        assertEquals("Charging", DataLabels.category("油费", ev = true))
    }

    @Test
    fun `custom categories are left untouched`() {
        assertEquals("洗车卡", DataLabels.category("洗车卡", ev = true))
    }
}
