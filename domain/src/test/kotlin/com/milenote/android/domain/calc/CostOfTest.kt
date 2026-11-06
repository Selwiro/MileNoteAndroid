package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Test

/** 对应 Web core.test：「成本来源选择不重复累计不同来源的油费」+ fuelCounted 三态。 */
class CostOfTest {

    @Test
    fun `legacy fuel cost source avoids double counting`() {
        val ledger = testLedger(
            records = listOf(
                Record(id = "b", date = "2026-01-01", category = "油费", amount = 255.0, source = "小熊油耗"),
                Record(id = "q", date = "2026-01-01", category = "油费", amount = 300.0, source = "钱迹"),
                Record(id = "p", date = "2026-01-01", category = "停车费", amount = 20.0, source = "钱迹"),
            ),
        )
        // blank 车辆 fuelCostSource = bear
        assertEquals(275.0, stats(ledger).total, 1e-9)
        val qianjiBasis = ledger.copy(vehicle = ledger.vehicle.copy(fuelCostSource = "qianji"))
        assertEquals(320.0, stats(qianjiBasis).total, 1e-9)
    }

    @Test
    fun `fuel counted boolean overrides legacy basis`() {
        val base = testLedger(
            records = listOf(
                Record(
                    id = "q",
                    date = "2026-01-01",
                    category = "油费",
                    amount = 300.0,
                    source = "钱迹",
                    fuelCounted = true,
                ),
                Record(
                    id = "b",
                    date = "2026-01-01",
                    category = "油费",
                    amount = 255.0,
                    source = "小熊油耗",
                    fuelCounted = false,
                ),
            ),
        )
        // fuelCounted 优先：钱迹 true 计入，小熊 false 不计
        assertEquals(300.0, stats(base).total, 1e-9)
    }

    @Test
    fun `non fuel records always count their amount`() {
        val ledger = testLedger(
            records = listOf(
                Record(id = "a", date = "2026-01-01", category = "维修", amount = 0.0),
                Record(id = "b", date = "2026-01-01", category = "保险", amount = 12.5),
            ),
        )
        assertEquals(0.0, costOf(ledger.records[0], ledger), 1e-9)
        assertEquals(12.5, costOf(ledger.records[1], ledger), 1e-9)
        assertEquals(12.5, stats(ledger).total, 1e-9)
    }

    @Test
    fun `bear basis counts fuel with liters and qianji basis counts imported keys`() {
        val bear = testLedger(records = listOf(
            Record(id = "b", date = "2026-01-01", category = "油费", amount = 100.0, source = "小熊油耗", liters = 10.0),
        ))
        assertEquals(100.0, costOf(bear.records[0], bear), 1e-9)

        val qianjiViaKeys = testLedger(records = listOf(
            Record(
                id = "q",
                date = "2026-01-01",
                category = "油费",
                amount = 200.0,
                source = "钱迹",
                importedKeys = listOf("钱迹:k1"),
            ),
        )).copy(vehicle = blankLedgerVehicle("qianji"))
        assertEquals(200.0, costOf(qianjiViaKeys.records[0], qianjiViaKeys), 1e-9)
    }

    @Test
    fun `current km takes max of vehicle and records`() {
        val ledger = testLedger(
            records = listOf(Record(id = "a", date = "2026-01-01", category = "停车费", amount = 1.0, odometer = 9000.0)),
        )
        ledger.vehicle.let {
            assertEquals(9000.0, currentKm(ledger.copy(vehicle = it.copy(currentKm = 5000.0, startKm = 1000.0))), 1e-9)
            assertEquals(5000.0, currentKm(ledger.copy(vehicle = it.copy(currentKm = 5000.0, startKm = 1000.0), records = emptyList())), 1e-9)
        }
    }

    private fun blankLedgerVehicle(fuelCostSource: String) =
        com.milenote.android.domain.model.blankVehicle().copy(fuelCostSource = fuelCostSource)
}
