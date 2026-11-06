package com.milenote.android.domain.calc

import com.milenote.android.domain.d

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：月度用车趋势跨月分摊、缺失数据不伪造。 */
class MonthlyUsageTest {

    @Test
    fun `prorates distance across months`() {
        val ledger = testLedger(
            records = listOf(
                Record(id = "a", date = "2026-09-01", odometer = 10000.0, category = "油费", amount = 200.0),
                Record(id = "b", date = "2026-10-01", odometer = 13000.0, category = "维修保养", amount = 400.0),
                Record(id = "c", date = "2026-10-01", odometer = 13000.0, category = "停车费", amount = 20.0),
                Record(id = "d", date = "2026-11-01", odometer = 16100.0, category = "油费", amount = 100.0),
            ),
        )
        val rows = monthlyUsage(ledger, d("2026-11-01"))
        assertEquals(6, rows.size)
        assertEquals(3000.0, rows.first { it.key == "2026-09" }.distance!!, 1e-9)
        assertEquals(3100.0, rows.first { it.key == "2026-10" }.distance!!, 1e-9)
        assertEquals(420.0, rows.first { it.key == "2026-10" }.cost, 1e-9)
        assertNull(rows.last().distance)
    }

    @Test
    fun `no fabrication and baseline handling`() {
        val ledger = testLedger().copy(
            vehicle = testLedger().vehicle.copy(
                startKm = 1000.0,
                currentKm = 99999.0,
                companionDate = "2026-09-01",
            ),
            records = listOf(
                Record(id = "a", date = "2026-10-01", odometer = 1000.0, category = "维修保养", amount = 0.0),
                Record(id = "b", date = "2026-12-01", odometer = 99999.0, category = "油费", amount = 300.0),
            ),
        )
        val rows = monthlyUsage(ledger, d("2026-11-01"))
        assertEquals(0.0, rows.first { it.key == "2026-09" }.distance!!, 1e-9)
        assertNull(rows.first { it.key == "2026-10" }.distance)
        assertEquals(0.0, rows.last().cost, 1e-9)
        assertEquals(true, monthlyUsage(testLedger(), d("2026-11-01")).all { it.distance == null })
    }

    @Test
    fun `month labels and keys`() {
        val rows = monthlyUsage(testLedger(), d("2026-01-15"))
        assertEquals(listOf("2025-08", "2025-09", "2025-10", "2025-11", "2025-12", "2026-01"), rows.map { it.key })
        assertEquals("8 月", rows.first().label)
        assertEquals("1 月", rows.last().label)
    }
}
