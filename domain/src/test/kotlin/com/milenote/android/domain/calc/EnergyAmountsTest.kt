package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 能源费金额字段自动补全（实付 / 应付 / 优惠 / 单价 / 加油量 / 充电量）。 */
class EnergyAmountsTest {

    @Test
    fun `amount and liters fill price payable and zero discount`() {
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 300.0, liters = 40.0),
        )
        assertEquals(7.5, filled.unitPrice!!, 1e-9)
        assertEquals(300.0, filled.payable!!, 1e-9)
        assertEquals(0.0, filled.discount!!, 1e-9)
        assertEquals(300.0, filled.amount, 1e-9)
    }

    @Test
    fun `liters price and amount fill payable and discount`() {
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 290.0, liters = 40.0, unitPrice = 7.5),
        )
        assertEquals(300.0, filled.payable!!, 1e-9)
        assertEquals(10.0, filled.discount!!, 1e-9)
    }

    @Test
    fun `payable and discount fill amount`() {
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 0.0, payable = 300.0, discount = 20.0, liters = 40.0),
        )
        assertEquals(280.0, filled.amount, 1e-9)
        assertEquals(7.0, filled.unitPrice!!, 1e-9)
    }

    @Test
    fun `non energy record untouched`() {
        val parking = Record(category = "停车费", amount = 10.0, liters = 40.0)
        assertEquals(parking, fillEnergyAmounts(parking))
    }

    // ---- 纯电：kWh × 电价 ----

    @Test
    fun `kwh and price fill unit price payable discount`() {
        // 家充：40 度 × 1.55 元/度 = 62 元
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 62.0, kwh = 40.0),
        )
        assertEquals(1.55, filled.unitPrice!!, 1e-9)
        assertEquals(62.0, filled.payable!!, 1e-9)
        assertEquals(0.0, filled.discount!!, 1e-9)
        assertEquals(40.0, filled.kwh!!, 1e-9)
        assertNull(filled.liters)
    }

    @Test
    fun `kwh and price fill payable and discount without touching liters`() {
        // 快充：30 度 × 1.2 元/度 = 36 元，实付 30 元 → 优惠 6 元
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 30.0, kwh = 30.0, unitPrice = 1.2),
        )
        assertEquals(36.0, filled.payable!!, 1e-9)
        assertEquals(6.0, filled.discount!!, 1e-9)
        assertNull(filled.liters)
        assertEquals(30.0, filled.kwh!!, 1e-9)
    }

    @Test
    fun `kwh payable and discount fill amount and price`() {
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 0.0, payable = 50.0, discount = 5.0, kwh = 45.0),
        )
        assertEquals(45.0, filled.amount, 1e-9)
        assertEquals(1.0, filled.unitPrice!!, 1e-9)
        assertEquals(45.0, filled.kwh!!, 1e-9)
    }

    @Test
    fun `amount and price fill fuel amount`() {
        // 只填了实付 300 与油价 7.5 → 加油量 40
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 300.0, unitPrice = 7.5),
        )
        assertEquals(40.0, filled.liters!!, 1e-9)
        assertEquals(300.0, filled.payable!!, 1e-9)
        assertEquals(0.0, filled.discount!!, 1e-9)
    }

    @Test
    fun `amount and price fill charging amount`() {
        // 只填了实付 28.8 与电价 0.64 → 充电量 45
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 28.8, unitPrice = 0.64),
            ev = true,
        )
        assertEquals(45.0, filled.kwh!!, 1e-9)
        assertNull(filled.liters)
        assertEquals(28.8, filled.payable!!, 1e-9)
    }

    @Test
    fun `payable and price fill charging amount`() {
        // 应付 50（实付 45 + 优惠 5）与电价 1.0 → 充电量 50
        val filled = fillEnergyAmounts(
            Record(category = "油费", amount = 45.0, discount = 5.0, unitPrice = 1.0),
            ev = true,
        )
        assertEquals(50.0, filled.kwh!!, 1e-9)
        assertEquals(50.0, filled.payable!!, 1e-9)
    }

    @Test
    fun `ev record with only amount is not guessed`() {
        val plain = Record(category = "油费", amount = 62.0)
        assertEquals(plain, fillEnergyAmounts(plain))
    }
}
