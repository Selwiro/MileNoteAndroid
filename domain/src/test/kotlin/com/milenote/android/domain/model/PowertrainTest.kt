package com.milenote.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动力类型归一与缺省语义：缺省/非法值一律按燃油处理，纯电/插混由显式值声明。 */
class PowertrainTest {

    @Test
    fun `normalize powertrain treats missing and illegal values as fuel`() {
        assertNull(normalizePowertrain(null))
        assertNull(normalizePowertrain(""))
        assertNull(normalizePowertrain("bev"))
        assertNull(normalizePowertrain("hybrid"))
        assertNull(normalizePowertrain("hev"))
        assertEquals(POWERTRAIN_FUEL, normalizePowertrain("fuel"))
        assertEquals(POWERTRAIN_FUEL, normalizePowertrain(" FUEL "))
        assertEquals(POWERTRAIN_EV, normalizePowertrain("ev"))
        assertEquals(POWERTRAIN_EV, normalizePowertrain(" EV "))
        assertEquals(POWERTRAIN_PHEV, normalizePowertrain("phev"))
        assertEquals(POWERTRAIN_PHEV, normalizePowertrain(" PHEV "))
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
        assertEquals(EnergyProfile.FUEL_ONLY, vehicle.energyProfile)
        assertTrue(vehicle.hasFuel)
        assertFalse(vehicle.hasCharging)
    }

    @Test
    fun `ev blank vehicle clears legacy fuel cost basis`() {
        val vehicle = blankVehicle(POWERTRAIN_EV)
        assertEquals(POWERTRAIN_EV, vehicle.powertrain)
        assertTrue(vehicle.isEv)
        // 纯电不能带 bear/qianji 口径，否则钱迹电费会被旧逻辑判 0
        assertNull(vehicle.fuelCostSource)
        assertTrue(blankLedger(POWERTRAIN_EV).isEv)
        assertEquals(EnergyProfile.ELECTRIC_ONLY, vehicle.energyProfile)
        assertFalse(vehicle.hasFuel)
        assertTrue(vehicle.hasCharging)

        // 非法动力类型按燃油建车
        val weird = blankVehicle("hybrid")
        assertNull(weird.powertrain)
        assertFalse(weird.isEv)
        assertEquals("bear", weird.fuelCostSource)
    }

    @Test
    fun `phev vehicle has both fuel and charging`() {
        val vehicle = blankVehicle(POWERTRAIN_PHEV)
        assertEquals(POWERTRAIN_PHEV, vehicle.powertrain)
        assertEquals(POWERTRAIN_PHEV, vehicle.powertrainKey)
        assertEquals(EnergyProfile.DUAL, vehicle.energyProfile)
        assertTrue(vehicle.hasFuel)
        assertTrue(vehicle.hasCharging)
        assertFalse(vehicle.isEv)
        // 插混也会跑钱迹来源的加油记录，不能沿用 bear 口径（否则被 costOf 判 0）
        assertNull(vehicle.fuelCostSource)
        assertEquals(EnergyProfile.DUAL, blankLedger(POWERTRAIN_PHEV).energyProfile)
    }

    @Test
    fun `normalize garage keeps phev and maps unknown powertrain to fuel`() {
        val phev = blankLedger(POWERTRAIN_PHEV).copy(id = "a")
        val garage = com.milenote.android.domain.backup.GarageOps.normalizeGarage(
            Garage(version = 2, activeId = "a", theme = "blue", cars = listOf(phev)),
        )
        assertEquals(POWERTRAIN_PHEV, garage.cars[0].vehicle.powertrain)

        val weird = blankLedger().copy(id = "b", vehicle = Vehicle(name = "怪车", powertrain = "hybrid"))
        val garage2 = com.milenote.android.domain.backup.GarageOps.normalizeGarage(
            Garage(version = 2, activeId = "b", theme = "blue", cars = listOf(weird)),
        )
        assertNull(garage2.cars[0].vehicle.powertrain)
        assertFalse(garage2.cars[0].isEv)
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

    @Test
    fun `legacy migration normalizes phev`() {
        val legacy = blankLedger().copy(
            vehicle = Vehicle(name = "插混", powertrain = "phev"),
            records = emptyList(),
        )
        val garage = com.milenote.android.domain.backup.GarageOps.migrateLegacy(legacy)
        assertEquals(POWERTRAIN_PHEV, garage.cars[0].vehicle.powertrain)
    }
}
