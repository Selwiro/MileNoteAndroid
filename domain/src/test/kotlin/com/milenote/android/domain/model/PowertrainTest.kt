package com.milenote.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动力类型归一与缺省语义：缺省/非法值一律按燃油处理，纯电由 `"ev"` 显式声明。 */
class PowertrainTest {

    @Test
    fun `normalize powertrain treats missing and illegal values as fuel`() {
        assertNull(normalizePowertrain(null))
        assertNull(normalizePowertrain(""))
        assertNull(normalizePowertrain("bev"))
        assertNull(normalizePowertrain("phev"))
        assertNull(normalizePowertrain("hybrid"))
        assertEquals(POWERTRAIN_FUEL, normalizePowertrain("fuel"))
        assertEquals(POWERTRAIN_FUEL, normalizePowertrain(" FUEL "))
        assertEquals(POWERTRAIN_EV, normalizePowertrain("ev"))
        assertEquals(POWERTRAIN_EV, normalizePowertrain(" EV "))
    }

    @Test
    fun `blank vehicle is fuel by default`() {
        val vehicle = blankVehicle()
        assertNull(vehicle.powertrain)
        assertFalse(vehicle.isEv)
        assertEquals(POWERTRAIN_FUEL, vehicle.powertrainKey)
        // 燃油建车保留旧小熊口径
        assertEquals("bear", vehicle.fuelCostSource)
        assertFalse(blankLedger().isEv)
        assertEquals(POWERTRAIN_FUEL, blankLedger().vehicle.powertrainKey)
    }

    @Test
    fun `ev blank vehicle clears legacy fuel cost basis`() {
        val vehicle = blankVehicle(POWERTRAIN_EV)
        assertEquals(POWERTRAIN_EV, vehicle.powertrain)
        assertTrue(vehicle.isEv)
        // 纯电不能带 bear/qianji 口径，否则钱迹电费会被旧逻辑判 0
        assertNull(vehicle.fuelCostSource)
        assertTrue(blankLedger(POWERTRAIN_EV).isEv)

        // 非法动力类型按燃油建车
        val weird = blankVehicle("hybrid")
        assertNull(weird.powertrain)
        assertFalse(weird.isEv)
        assertEquals("bear", weird.fuelCostSource)
    }

    @Test
    fun `normalize garage maps unknown powertrain to fuel`() {
        val car = blankLedger().copy(id = "a", vehicle = Vehicle(name = "怪车", powertrain = "PHEV"))
        val garage = com.milenote.android.domain.backup.GarageOps.normalizeGarage(
            Garage(version = 2, activeId = "a", theme = "blue", cars = listOf(car)),
        )
        assertNull(garage.cars[0].vehicle.powertrain)
        assertFalse(garage.cars[0].isEv)
    }

    @Test
    fun `merge keeps powertrain of the car that has data`() {
        val existing = blankLedger().copy(
            id = "a",
            records = listOf(Record(id = "r", date = "2026-01-01", category = "停车费", amount = 1.0, kind = "expense")),
        )
        val incomingEv = blankLedger(POWERTRAIN_EV).copy(id = "a")
        // 有记录的一侧（燃油）身份优先，避免历史单位串掉
        assertEquals(
            POWERTRAIN_FUEL,
            com.milenote.android.domain.backup.GarageOps.mergeLedger(existing, incomingEv).vehicle.powertrainKey,
        )

        // 空车整体采纳 incoming（含动力类型）
        val empty = blankLedger().copy(id = "a")
        assertEquals(
            POWERTRAIN_EV,
            com.milenote.android.domain.backup.GarageOps.mergeLedger(empty, incomingEv).vehicle.powertrainKey,
        )
    }
}
