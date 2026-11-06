package com.milenote.android.domain.calc

import com.milenote.android.domain.model.blankLedger

import com.milenote.android.domain.fuelRecord

import com.milenote.android.domain.d

import com.milenote.android.domain.assertClose
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
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
        assertEquals(7.5, result.fuelRate!!, 1e-9)
        assertNull(stats(blankLedger()).perKm)
        assertNull(stats(blankLedger()).fuelRate)
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
        assertEquals(8.0, jan.fuelRate!!, 1e-9)
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
}
