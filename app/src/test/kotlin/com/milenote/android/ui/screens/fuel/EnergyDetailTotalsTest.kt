package com.milenote.android.ui.screens.fuel

import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 油耗/能耗明细页的汇总口径（B41）。
 *
 * 合计必须和费用明细页一致，走 `costOf`：被标成「不计入成本」的油费要从合计里排除，
 * 不能像旧实现那样把每条 `amount` 直接相加（同一批记录两页给出两个数）。
 */
class EnergyDetailTotalsTest {

    private val ledger = blankLedger()

    private fun fuel(
        id: String,
        amount: Double,
        liters: Double?,
        counted: Boolean?,
    ): Record = Record(
        id = id,
        date = "2026-11-01",
        category = "油费",
        amount = amount,
        liters = liters,
        fuelCounted = counted,
    )

    @Test
    fun `total follows costOf so both pages agree`() {
        val records = listOf(
            fuel("a", 300.0, 40.0, counted = true),
            // 钱迹油费在小熊口径下不计入成本：明细里仍列出金额，但合计不算它
            fuel("b", 120.0, 0.0, counted = false),
            Record(
                id = "c",
                date = "2026-11-02",
                category = "油费",
                amount = 60.0,
                kwh = 20.0,
                fuelCounted = true,
            ),
        )
        val totals = energyDetailTotals(records, ledger, electricOnly = false)

        // 与费用明细同口径：300 + 60（120 被排除）
        assertEquals(360.0, totals.total, 1e-9)
        assertEquals(records.sumOf { costOf(it, ledger) }, totals.total, 1e-9)
        // 直接相加 amount 会得到 480：两页对不上的根源
        assertEquals(480.0, records.sumOf { it.amount }, 1e-9)

        // 插混两行各自走同一口径
        assertEquals(40.0, totals.fuelAmount, 1e-9)
        assertEquals(300.0, totals.fuelCost, 1e-9)
        assertEquals(20.0, totals.chargeAmount, 1e-9)
        assertEquals(60.0, totals.chargeCost, 1e-9)

        // 单列能量累计：燃油账本读升数（含 0 与 null）
        assertEquals(40.0, totals.energyAmount, 1e-9)
    }

    @Test
    fun `electric ledger accumulates kwh instead of liters`() {
        val records = listOf(
            Record(id = "e1", date = "2026-11-01", category = "油费", amount = 45.0, kwh = 30.0),
            Record(id = "e2", date = "2026-11-02", category = "油费", amount = 30.0, kwh = 20.0),
        )
        val totals = energyDetailTotals(records, ledger, electricOnly = true)
        assertEquals(50.0, totals.energyAmount, 1e-9)
        assertEquals(75.0, totals.total, 1e-9)
    }
}
