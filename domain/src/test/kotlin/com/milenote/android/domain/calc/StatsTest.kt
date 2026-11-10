package com.milenote.android.domain.calc

import com.milenote.android.domain.model.blankLedger

import com.milenote.android.domain.evLedger
import com.milenote.android.domain.evRecord
import com.milenote.android.domain.fuelRecord
import com.milenote.android.domain.phevLedger

import com.milenote.android.domain.d

import com.milenote.android.domain.assertClose
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：油耗里程加权、成本与无里程场景，以及统计口径边界。 */
class StatsTest {

    @Test
    fun `weighted fuel rate and per km with empty ledger null`() {
        val ledger = blankLedger().copy(
            vehicle = blankLedger().vehicle.copy(startKm = 1000.0, currentKm = 2000.0),
            records = listOf(
                Record(id = "i", date = "2026-01-01", category = "保险", amount = 1000.0),
                Record(
                    id = "m1",
                    date = "2026-01-01",
                    category = "油费",
                    kind = "manual",
                    rate = 6.0,
                    distance = 100.0,
                    amount = 0.0,
                ),
                Record(
                    id = "m2",
                    date = "2026-01-02",
                    category = "油费",
                    kind = "manual",
                    rate = 8.0,
                    distance = 300.0,
                    amount = 0.0,
                ),
            ),
        )
        val result = stats(ledger)
        assertEquals(1000.0, result.total, 1e-9)
        assertEquals(1.0, result.perKm!!, 1e-9)
        assertEquals(7.5, result.energyRate!!, 1e-9)
        assertNull(stats(blankLedger()).perKm)
        assertNull(stats(blankLedger()).energyRate)
        assertEquals(0.0, stats(blankLedger()).distance, 1e-9)
    }

    @Test
    fun `all time distance uses current km minus start km`() {
        val ledger = blankLedger().copy(
            vehicle = blankLedger().vehicle.copy(startKm = 1000.0, currentKm = 2000.0),
            records = listOf(
                Record(id = "a", date = "2026-01-01", category = "停车费", amount = 10.0, odometer = 2500.0),
            ),
        )
        // currentKm = max(2000, 1000, 2500) = 2500；distance = 2500 - 1000
        assertEquals(1500.0, stats(ledger).distance, 1e-9)
    }

    @Test
    fun `all time distance falls back to min odometer when start km missing`() {
        val ledger = blankLedger().copy(
            vehicle = blankLedger().vehicle.copy(startKm = null, currentKm = 5000.0),
            records = listOf(
                Record(id = "a", date = "2026-01-01", category = "停车费", amount = 10.0, odometer = 1200.0),
                Record(id = "b", date = "2026-02-01", category = "停车费", amount = 10.0, odometer = 3000.0),
            ),
        )
        // currentKm = 5000, start = min(5000, 1200, 3000) = 1200
        assertEquals(3800.0, stats(ledger).distance, 1e-9)
    }

    @Test
    fun `period distance is max in range minus latest before range`() {
        val ledger = testLedger(
            records = listOf(
                Record(id = "a", date = "2025-12-20", category = "停车费", amount = 1.0, odometer = 1000.0),
                Record(id = "b", date = "2026-01-10", category = "停车费", amount = 1.0, odometer = 1500.0),
                Record(id = "c", date = "2026-02-10", category = "停车费", amount = 1.0, odometer = 2000.0),
            ),
        )
        val feb = stats(ledger, "2026-02-01", "2026-02-28")
        assertEquals(500.0, feb.distance, 1e-9)
        assertEquals(0.002, feb.perKm!!, 1e-9)

        val jan = stats(ledger, "2026-01-01", "2026-01-31")
        assertEquals(500.0, jan.distance, 1e-9)
    }

    @Test
    fun `fuel rate only counts intervals inside range`() {
        val ledger = testLedger(
            records = listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-10", 1500.0, 40.0),
                fuelRecord("2026-03-01", 1800.0, 30.0),
                fuelRecord("2026-03-10", 2200.0, 40.0),
            ),
        )
        val jan = stats(ledger, "2026-01-01", "2026-01-31")
        assertEquals(1, jan.intervals.size)
        assertEquals(8.0, jan.energyRate!!, 1e-9)
    }

    @Test
    fun `category breakdown normalizes legacy maintenance`() {
        val ledger = testLedger(
            records = listOf(
                Record(id = "a", date = "2026-01-01", category = "维修保养", amount = 380.0),
                Record(id = "b", date = "2026-01-01", category = "停车费", amount = 20.0),
            ),
        )
        val result = stats(ledger)
        assertEquals(400.0, result.total, 1e-9)
        assertEquals(380.0, result.categories.first { it.name == "保养" }.value, 1e-9)
        assertEquals(0.0, result.categories.first { it.name == "油费" }.value, 1e-9)
        assertEquals(9, result.categories.size)
    }

    @Test
    fun `custom categories participate in stats`() {
        val ledger = blankLedger().copy(
            customCategories = listOf("充电费"),
            records = listOf(Record(id = "c", date = "2026-11-01", category = "充电费", amount = 66.0)),
        )
        assertEquals(66.0, stats(ledger).categories.first { it.name == "充电费" }.value, 1e-9)
        assertNotNull(stats(ledger).categories.firstOrNull { it.name == "充电费" })
        assertEquals(10, stats(ledger).categories.size)
    }

    @Test
    fun `ev stats use kwh intervals and ignore fuel manual records`() {
        val ledger = evLedger(
            records = listOf(
                evRecord("2026-01-01", 1000.0, 50.0),
                evRecord("2026-01-10", 1500.0, 40.0),
                // 燃油历史手填油耗不应进入纯电能耗
                Record(
                    id = "legacy",
                    date = "2026-01-11",
                    category = "油费",
                    amount = 0.0,
                    kind = "manual",
                    rate = 7.0,
                    distance = 100.0,
                ),
            ),
        )
        val result = stats(ledger)
        assertEquals(1, result.intervals.size)
        // 40 kWh / 500 km * 100 = 8.0 kWh/100km
        assertEquals(8.0, result.energyRate!!, 1e-9)
        assertEquals("满电法", result.intervals[0].source)
        assertNull(result.estimatedEnergyRate)
    }

    @Test
    fun `ev estimated energy rate when no full charge interval exists`() {
        // 全部未充满 → 满电法不产区间，退回累计充电量估算
        val ledger = evLedger(
            records = listOf(
                evRecord("2026-01-01", 1000.0, 40.0, full = false),
                evRecord("2026-01-05", 1250.0, 20.0, full = false),
                evRecord("2026-01-10", 1500.0, 30.0, full = false),
            ),
        )
        val result = stats(ledger)
        assertEquals(0, result.intervals.size)
        assertNull(result.energyRate)
        // 首条只作基准：(20 + 30) / 500 km * 100 = 10.0
        assertEquals(10.0, result.estimatedEnergyRate!!, 1e-9)
        assertEquals(10.0, estimateEnergyRate(ledger.records, EnergyKind.ELECTRIC)!!, 1e-9)
        assertNull(estimateEnergyRate(ledger.records.take(1), EnergyKind.ELECTRIC))
        assertNull(estimateEnergyRate(emptyList(), EnergyKind.ELECTRIC))
        // 燃油流没有加油记录 → 估算为 null
        assertNull(estimateEnergyRate(ledger.records))
    }

    @Test
    fun `phev stats keep fuel and electric streams separate`() {
        val ledger = phevLedger(
            records = listOf(
                evRecord("2026-01-01", 1000.0, 15.0),
                fuelRecord("2026-01-10", 1450.0, 35.0),
                evRecord("2026-01-20", 1700.0, 13.0, full = false),
                fuelRecord("2026-01-30", 2200.0, 32.0),
                evRecord("2026-02-05", 2400.0, 16.0),
            ),
        )
        val result = stats(ledger)
        val fuel = result.fuelEnergy!!
        val electric = result.electricEnergy!!
        assertEquals(4.266666666666667, fuel.rate!!, 1e-9)
        assertEquals(2.0714285714285716, electric.rate!!, 1e-9)
        assertEquals(1, fuel.intervals.size)
        assertEquals(1, electric.intervals.size)
        // 主口径（页面标题「能耗概览」）是燃油流
        assertEquals(fuel.rate, result.energyRate)
        assertEquals(1, result.intervals.size)
        // 两条流各自的量/里程，不许合并成一个数
        assertEquals(32.0, fuel.amount, 1e-9)
        assertEquals(29.0, electric.amount, 1e-9)
        assertEquals(750.0, fuel.distance, 1e-9)
        assertEquals(1400.0, electric.distance, 1e-9)
        val merged = (32.0 + 29.0) / (750.0 + 1400.0) * 100.0
        assertFalse(kotlin.math.abs((result.energyRate ?: 0.0) - merged) < 1e-6)
    }

    @Test
    fun `phev estimated fallback is computed per stream`() {
        val ledger = phevLedger(
            records = listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-10", 1500.0, 40.0),
                // 两次都未充满 → 电荷流没有满电区间，只能回退累计估算
                evRecord("2026-02-01", 2000.0, 20.0, full = false),
                evRecord("2026-02-10", 2400.0, 24.0, full = false),
            ),
        )
        val result = stats(ledger)
        assertEquals(8.0, result.fuelEnergy!!.rate!!, 1e-9)
        assertNull(result.fuelEnergy!!.estimated)
        assertNull(result.electricEnergy!!.rate)
        // 首条只作基准：24 / 400 km * 100 = 6.0
        assertEquals(6.0, result.electricEnergy!!.estimated!!, 1e-9)
        assertNull(result.estimatedEnergyRate)
    }

    @Test
    fun `fuel ledger has no estimated energy rate`() {
        val ledger = testLedger(
            records = listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0, full = false),
                fuelRecord("2026-01-10", 1500.0, 40.0, full = false),
            ),
        )
        assertNull(stats(ledger).estimatedEnergyRate)
    }
}
