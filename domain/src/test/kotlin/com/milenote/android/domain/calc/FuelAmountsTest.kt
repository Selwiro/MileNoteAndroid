package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Test

/** 油费金额字段自动补全（实付 / 应付 / 优惠 / 油价 / 加油量）。 */
class FuelAmountsTest {

    @Test
    fun `amount and liters fill price payable and zero discount`() {
        val filled = fillFuelAmounts(
            Record(category = "油费", amount = 300.0, liters = 40.0),
        )
        assertEquals(7.5, filled.unitPrice!!, 1e-9)
        assertEquals(300.0, filled.payable!!, 1e-9)
        assertEquals(0.0, filled.discount!!, 1e-9)
        assertEquals(300.0, filled.amount, 1e-9)
    }

    @Test
    fun `liters price and amount fill payable and discount`() {
        val filled = fillFuelAmounts(
            Record(category = "油费", amount = 290.0, liters = 40.0, unitPrice = 7.5),
        )
        assertEquals(300.0, filled.payable!!, 1e-9)
        assertEquals(10.0, filled.discount!!, 1e-9)
    }

    @Test
    fun `payable and discount fill amount`() {
        val filled = fillFuelAmounts(
            Record(category = "油费", amount = 0.0, payable = 300.0, discount = 20.0, liters = 40.0),
        )
        assertEquals(280.0, filled.amount, 1e-9)
        assertEquals(7.0, filled.unitPrice!!, 1e-9)
    }

    @Test
    fun `non fuel record untouched`() {
        val parking = Record(category = "停车费", amount = 10.0, liters = 40.0)
        assertEquals(parking, fillFuelAmounts(parking))
    }
}
