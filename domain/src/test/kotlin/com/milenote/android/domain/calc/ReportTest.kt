package com.milenote.android.domain.calc

import com.milenote.android.domain.assertClose
import com.milenote.android.domain.d
import com.milenote.android.domain.evLedger
import com.milenote.android.domain.evRecord
import com.milenote.android.domain.fuelRecord
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.phevLedger
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * 报告聚合（[report]）与周期模型（[ReportPeriod]）。
 *
 * 重点：自然月 / 自然年边界、进行中的同期对比、累计的全量口径、里程与能耗的「数据不足」边界。
 */
class ReportTest {

    private val today = d("2026-11-13")

    private fun month(year: Int, month: Int) = ReportPeriod(ReportKind.MONTH, year, month)
    private fun year(value: Int) = ReportPeriod(ReportKind.YEAR, value, 1)
    private val all = ReportPeriod(ReportKind.ALL)

    private fun plain(date: String, category: String, amount: Double, odometer: Double? = null) = Record(
        id = "$date-$category-$amount",
        date = date,
        category = category,
        amount = amount,
        odometer = odometer,
    )

    private fun ledgerWith(
        records: List<Record>,
        startKm: Double? = null,
        currentKm: Double = 0.0,
    ): Ledger = testLedger(records).copy(
        vehicle = blankLedger().vehicle.copy(startKm = startKm, currentKm = currentKm),
    )

    // ---------------------------------------------------------------- 周期

    @Test
    fun `month range uses real month length`() {
        assertEquals(DateRange("2026-01-01", "2026-01-31"), month(2026, 1).range())
        assertEquals(DateRange("2024-02-01", "2024-02-29"), month(2024, 2).range())
        assertEquals(DateRange("2023-02-01", "2023-02-28"), month(2023, 2).range())
        assertEquals(DateRange("2026-11-01", "2026-11-30"), month(2026, 11).range())
        assertEquals(DateRange("2026-01-01", "2026-12-31"), year(2026).range())
        assertNull(all.range())
    }

    @Test
    fun `previous crosses year boundary and all has none`() {
        assertEquals(ReportPeriod(ReportKind.MONTH, 2025, 12), month(2026, 1).previous())
        assertEquals(ReportPeriod(ReportKind.MONTH, 2026, 11), month(2026, 12).previous())
        assertEquals(ReportPeriod(ReportKind.YEAR, 2025, 1), year(2026).previous())
        assertNull(all.previous())
        assertEquals(ReportPeriod(ReportKind.MONTH, 2025, 11), month(2026, 11).yearAgo())
        assertNull(all.yearAgo())
    }

    @Test
    fun `current future and days`() {
        assertTrue(month(2026, 11).isCurrent(today))
        assertFalse(month(2026, 10).isCurrent(today))
        assertTrue(year(2026).isCurrent(today))
        assertTrue(month(2026, 12).isFuture(today))
        assertFalse(month(2026, 11).isFuture(today))
        assertTrue(year(2027).isFuture(today))
        assertFalse(all.isFuture(today))
        assertTrue(all.isCurrent(today))

        val running = report(ledgerWith(emptyList()), month(2026, 11), today)
        assertEquals(13, running.days)
        assertEquals(DateRange("2026-11-01", "2026-11-13"), running.range)
        assertTrue(running.inProgress)

        val complete = report(ledgerWith(emptyList()), month(2026, 10), today)
        assertEquals(31, complete.days)
        assertEquals(DateRange("2026-10-01", "2026-10-31"), complete.range)
        assertFalse(complete.inProgress)
    }

    // ---------------------------------------------------------------- 对比

    @Test
    fun `in progress month compares with previous month same span`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-11-03", "保险", 100.0),
                plain("2026-11-20", "保险", 900.0), // 未来日期：不进本期
                plain("2026-10-05", "保险", 40.0),
                plain("2026-10-20", "保险", 500.0), // 超出「上月同期」
            ),
        )
        val data = report(ledger, month(2026, 11), today)
        assertEquals(100.0, data.total, 1e-9)
        val delta = data.totalDelta!!
        assertEquals(40.0, delta.previous, 1e-9)
        assertEquals(60.0, delta.diff, 1e-9)
        assertEquals(150.0, delta.percent!!, 1e-9)
    }

    @Test
    fun `complete month compares with full previous month`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-10-03", "保险", 100.0),
                plain("2026-09-20", "保险", 200.0),
            ),
        )
        val data = report(ledger, month(2026, 10), today)
        assertEquals(200.0, data.totalDelta!!.previous, 1e-9)
    }

    @Test
    fun `year ago row is hidden when that month had no records`() {
        val ledger = ledgerWith(listOf(plain("2026-10-03", "保险", 100.0)))
        assertNull(report(ledger, month(2026, 10), today).yearAgoDelta)

        val withHistory = ledgerWith(
            listOf(
                plain("2026-10-03", "保险", 100.0),
                plain("2025-10-03", "保险", 80.0),
            ),
        )
        val data = report(withHistory, month(2026, 10), today)
        assertEquals(80.0, data.yearAgoDelta!!.previous, 1e-9)
    }

    @Test
    fun `first period of the ledger is marked as having no earlier records`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-10-05", "保险", 100.0),
                plain("2026-10-20", "保险", 50.0),
            ),
        )
        val first = report(ledger, month(2026, 10), today)
        assertFalse(first.hasEarlierRecords)
        assertEquals(0.0, first.totalDelta!!.previous, 1e-9)

        val later = report(ledger, month(2026, 11), today)
        assertTrue(later.hasEarlierRecords)
        // 累计没有「上期」，标记恒为 false。
        assertFalse(report(ledger, all, today).hasEarlierRecords)
    }

    @Test
    fun `zero previous total gives diff without percent`() {
        val ledger = ledgerWith(listOf(plain("2026-10-03", "保险", 100.0)))
        val delta = report(ledger, month(2026, 10), today).totalDelta!!
        assertEquals(100.0, delta.diff, 1e-9)
        assertNull(delta.percent)
    }

    // ---------------------------------------------------------------- 空态与构成

    @Test
    fun `empty month stays empty but keeps twelve trend points`() {
        val data = report(ledgerWith(emptyList()), month(2026, 9), today)
        assertFalse(data.hasData)
        assertEquals(0.0, data.total, 1e-9)
        assertTrue(data.categories.isEmpty())
        assertEquals(12, data.monthlyTrend.size)
        assertTrue(data.yearlyTrend.isEmpty())
        assertEquals(12, data.monthlyTrend.map { it.year * 12 + it.month }.distinct().size)
        assertEquals(YearMonth.of(2026, 9), YearMonth.of(data.monthlyTrend.last().year, data.monthlyTrend.last().month))
    }

    @Test
    fun `categories exclude zero sort desc and sum to total`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-10-01", "油费", 100.0),
                plain("2026-10-02", "保险", 300.0),
                plain("2026-10-03", "停车费", 50.0),
            ),
        )
        val data = report(ledger, month(2026, 10), today)
        assertEquals(listOf("保险", "油费", "停车费"), data.categories.map { it.name })
        assertEquals(450.0, data.categories.sumOf { it.value }, 1e-9)
        assertEquals(data.total, data.categories.sumOf { it.value }, 1e-9)
        assertEquals(450.0, data.total, 1e-9)
        assertEquals(3, data.recordCount)
    }

    @Test
    fun `uncounted fuel stays out of total but counts as fuel`() {
        val ledger = ledgerWith(
            listOf(
                fuelRecord("2026-10-03", 1000.0, 40.0, amount = 300.0, fuelCounted = false),
                plain("2026-10-04", "保险", 100.0),
            ),
        )
        val data = report(ledger, month(2026, 10), today)
        assertEquals(100.0, data.total, 1e-9)
        assertEquals(1, data.fuel!!.count)
        assertEquals(40.0, data.fuel!!.amount, 1e-9)
        assertEquals(0.0, data.fuel!!.cost, 1e-9)
    }

    // ---------------------------------------------------------------- 里程

    @Test
    fun `mileage needs two readings or a baseline`() {
        val noReading = report(ledgerWith(listOf(plain("2026-10-01", "保险", 100.0))), month(2026, 10), today)
        assertNull(noReading.distance)
        assertNull(noReading.perKm)
        assertEquals(0, noReading.odometerReadings)

        val single = report(
            ledgerWith(listOf(plain("2026-10-01", "保险", 100.0, odometer = 1000.0))),
            month(2026, 10),
            today,
        )
        assertNull(single.distance)

        val two = report(
            ledgerWith(
                listOf(
                    plain("2026-10-01", "保险", 100.0, odometer = 1000.0),
                    plain("2026-10-20", "保险", 100.0, odometer = 1300.0),
                ),
            ),
            month(2026, 10),
            today,
        )
        assertEquals(300.0, two.distance!!, 1e-9)
        assertEquals(200.0 / 300.0, two.perKm!!, 1e-9)
    }

    @Test
    fun `first month mileage uses earliest reading as baseline`() {
        val data = report(
            ledgerWith(
                listOf(
                    fuelRecord("2026-11-02", 1000.0, 40.0),
                    fuelRecord("2026-11-12", 1300.0, 45.0),
                ),
            ),
            month(2026, 11),
            today,
        )
        assertEquals(300.0, data.distance!!, 1e-9)
    }

    // ---------------------------------------------------------------- 能耗

    @Test
    fun `fuel interval gives rate and span count`() {
        val data = report(
            ledgerWith(
                listOf(
                    fuelRecord("2026-11-02", 1000.0, 40.0),
                    fuelRecord("2026-11-12", 1300.0, 45.0),
                ),
            ),
            month(2026, 11),
            today,
        )
        assertEquals(1, data.fuel!!.intervals)
        assertEquals(15.0, data.fuel!!.rate!!, 1e-9)
        assertNull(data.fuel!!.estimated)
    }

    @Test
    fun `estimated rate fills in when no full tank span`() {
        val data = report(
            ledgerWith(
                listOf(
                    fuelRecord("2026-11-02", 1000.0, 30.0, full = false),
                    fuelRecord("2026-11-12", 1300.0, 45.0, full = false),
                ),
            ),
            month(2026, 11),
            today,
        )
        assertEquals(0, data.fuel!!.intervals)
        assertNull(data.fuel!!.rate)
        assertEquals(15.0, data.fuel!!.estimated!!, 1e-9)
    }

    @Test
    fun `phev keeps two energy flows apart`() {
        val data = report(
            phevLedger(
                listOf(
                    fuelRecord("2026-11-02", 1000.0, 40.0),
                    fuelRecord("2026-11-12", 1300.0, 45.0),
                    evRecord("2026-11-05", 1100.0, 20.0),
                    evRecord("2026-11-13", 1250.0, 18.0),
                ),
            ),
            month(2026, 11),
            today,
        )
        assertEquals(85.0, data.fuel!!.amount, 1e-9)
        assertEquals(2, data.fuel!!.count)
        assertEquals(38.0, data.electric!!.amount, 1e-9)
        assertEquals(2, data.electric!!.count)
        // 两条流的单价分别按各自口径算，不混算。
        assertNotNull(data.fuel!!.unitPrice)
        assertNotNull(data.electric!!.unitPrice)
    }

    @Test
    fun `fuel flow is null on ev ledger and electric null on fuel ledger`() {
        val ev = report(
            evLedger(listOf(evRecord("2026-11-05", 1000.0, 20.0), evRecord("2026-11-13", 1200.0, 18.0))),
            month(2026, 11),
            today,
        )
        assertNull(ev.fuel)
        assertNotNull(ev.electric)

        val fuel = report(
            ledgerWith(listOf(fuelRecord("2026-11-05", 1000.0, 20.0), fuelRecord("2026-11-13", 1200.0, 18.0))),
            month(2026, 11),
            today,
        )
        assertNotNull(fuel.fuel)
        assertNull(fuel.electric)
    }

    // ---------------------------------------------------------------- 之最

    @Test
    fun `priciest fuel falls back to amount over volume and station needs two visits`() {
        val ledger = ledgerWith(
            listOf(
                fuelRecord("2026-10-02", 1000.0, 40.0, amount = 320.0).copy(unitPrice = 8.0),
                fuelRecord("2026-10-09", 1200.0, 40.0, amount = 280.0).copy(unitPrice = null, location = "A 站"),
                fuelRecord("2026-10-16", 1400.0, 40.0, amount = 280.0).copy(unitPrice = null, location = "A 站"),
                plain("2026-10-20", "保险", 900.0),
            ),
        )
        val data = report(ledger, month(2026, 10), today)
        assertEquals(900.0, data.highlights.topExpense!!.amount, 1e-9)
        assertEquals(8.0, data.highlights.priciestEnergy!!.unitPrice!!, 1e-9)
        assertEquals("A 站", data.highlights.topStation!!.station)
        assertEquals(2, data.highlights.topStation!!.count)
    }

    @Test
    fun `top station hidden when every place visited once`() {
        val ledger = ledgerWith(
            listOf(
                fuelRecord("2026-10-02", 1000.0, 40.0).copy(location = "A 站"),
                fuelRecord("2026-10-09", 1200.0, 40.0).copy(location = "B 站"),
            ),
        )
        assertNull(report(ledger, month(2026, 10), today).highlights.topStation)
    }

    // ---------------------------------------------------------------- 年报

    @Test
    fun `year report averages over elapsed months`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-01-05", "保险", 1100.0),
                plain("2026-11-05", "保险", 1100.0),
            ),
        )
        val data = report(ledger, year(2026), today)
        assertTrue(data.inProgress)
        assertEquals(12, data.monthlyTrend.size)
        assertEquals(2, data.monthsWithData)
        assertEquals(2200.0 / 11, data.monthlyAvg!!, 1e-9)
        assertNull(data.spanMonths)
    }

    // ---------------------------------------------------------------- 累计

    @Test
    fun `all view spans first record to today with full ledger totals`() {
        val ledger = ledgerWith(
            listOf(
                plain("2023-04-12", "保险", 500.0, odometer = 20000.0),
                plain("2026-11-01", "停车费", 50.0, odometer = 40000.0),
            ),
            startKm = 10000.0,
            currentKm = 40000.0,
        )
        val data = report(ledger, all, today)
        assertEquals(DateRange("", "2026-11-13"), data.range)
        assertEquals("2023-04-12", data.firstDate)
        assertEquals(550.0, data.total, 1e-9)
        assertNull(data.totalDelta)
        assertNull(data.yearAgoDelta)
        assertFalse(data.inProgress)
        // 累计里程走全量口径：当前里程 − 起始里程（不是首末读数差 20000）。
        assertEquals(stats(ledger).distance, data.distance!!, 1e-9)
        assertEquals(30000.0, data.distance!!, 1e-9)
        assertTrue(data.hasData)
    }

    @Test
    fun `empty ledger all view has no data and no first date`() {
        val data = report(ledgerWith(emptyList()), all, today)
        assertFalse(data.hasData)
        assertNull(data.firstDate)
        assertEquals(0.0, data.total, 1e-9)
        assertEquals(12, data.monthlyTrend.size)
        assertTrue(data.yearlyTrend.isEmpty())
    }

    @Test
    fun `all view trends by year after twelve months and by month before`() {
        val longSpan = ledgerWith(
            listOf(
                plain("2023-01-05", "保险", 100.0),
                plain("2024-02-05", "保险", 200.0),
                plain("2026-11-05", "保险", 300.0),
            ),
        )
        val yearly = report(longSpan, all, today)
        assertEquals(listOf(2023, 2024, 2025, 2026), yearly.yearlyTrend.map { it.year })
        assertEquals(100.0, yearly.yearlyTrend[0].total, 1e-9)
        assertEquals(0.0, yearly.yearlyTrend[2].total, 1e-9)
        assertEquals(300.0, yearly.yearlyTrend[3].total, 1e-9)
        assertTrue(yearly.monthlyTrend.isEmpty())

        val shortSpan = ledgerWith(
            listOf(
                plain("2026-06-05", "保险", 100.0),
                plain("2026-11-05", "保险", 300.0),
            ),
        )
        val monthly = report(shortSpan, all, today)
        assertEquals(12, monthly.monthlyTrend.size)
        assertTrue(monthly.yearlyTrend.isEmpty())
    }

    @Test
    fun `all view span months and monthly average include empty months`() {
        val ledger = ledgerWith(
            listOf(
                plain("2025-11-01", "保险", 130.0),
                plain("2026-11-01", "保险", 0.0),
            ),
        )
        val data = report(ledger, all, today)
        assertEquals(13, data.spanMonths)
        assertEquals(10.0, data.monthlyAvg!!, 1e-9)
        assertEquals(2, data.monthsWithData)
    }

    @Test
    fun `all view highlights look at whole ledger`() {
        val ledger = ledgerWith(
            listOf(
                fuelRecord("2023-04-12", 1000.0, 40.0, amount = 320.0).copy(unitPrice = 8.0, location = "A 站"),
                fuelRecord("2024-05-12", 3000.0, 40.0, amount = 280.0).copy(unitPrice = null, location = "A 站"),
                plain("2026-11-01", "保险", 1200.0),
            ),
        )
        val data = report(ledger, all, today)
        assertEquals(1200.0, data.highlights.topExpense!!.amount, 1e-9)
        assertEquals(8.0, data.highlights.priciestEnergy!!.unitPrice!!, 1e-9)
        assertEquals(2, data.highlights.topStation!!.count)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `month report has no first date and no span`() {
        val data = report(ledgerWith(emptyList()), month(2026, 11), today)
        assertNull(data.firstDate)
        assertNull(data.spanMonths)
        assertNull(data.monthlyAvg)
        assertClose(0.0, data.daily)
    }

    @Test
    fun `current month trend ignores records dated after today`() {
        val ledger = ledgerWith(
            listOf(
                plain("2026-10-03", "保险", 50.0),  // 上个月：趋势窗口里要保留
                plain("2026-11-03", "保险", 100.0),
                plain("2026-11-20", "保险", 900.0), // 预录的未来日期：不进本月合计，也不进趋势柱
            ),
        )
        val data = report(ledger, month(2026, 11), today)
        assertEquals(100.0, data.total, 1e-9)
        val currentBar = data.monthlyTrend.last { it.year == 2026 && it.month == 11 }
        assertEquals(100.0, currentBar.total, 1e-9)
        // 不能因为「卡未来日期」把窗口里的历史月份也滤掉。
        val previousBar = data.monthlyTrend.first { it.year == 2026 && it.month == 10 }
        assertEquals(50.0, previousBar.total, 1e-9)
    }

    @Test
    fun `month without energy records has no energy flow`() {
        val ledger = ledgerWith(listOf(plain("2026-10-03", "保险", 100.0)))
        val data = report(ledger, month(2026, 10), today)
        assertNull(data.fuel)
        assertNull(data.electric)
    }

    @Test
    fun `previous energy line exposes sample count for the delta row`() {
        val ledger = ledgerWith(
            listOf(
                fuelRecord("2026-10-02", 1000.0, 40.0),
                fuelRecord("2026-10-10", 1300.0, 45.0),
                fuelRecord("2026-11-02", 1600.0, 42.0),
                fuelRecord("2026-11-09", 1900.0, 40.0),
            ),
        )
        val data = report(ledger, month(2026, 11), today)
        // 11 月有两段（10-10→11-02、11-02→11-09）；10 月只有一段（10-02→10-10）。
        assertEquals(2, data.fuel!!.intervals)
        assertEquals(1, data.fuel!!.previous!!.intervals)
        assertNotNull(data.fuel!!.previous!!.rate)
    }

    @Test
    fun `future month keeps full range but has no data`() {
        val data = report(ledgerWith(emptyList()), month(2026, 12), today)
        assertEquals(DateRange("2026-12-01", "2026-12-31"), data.range)
        assertFalse(data.hasData)
        assertFalse(data.inProgress)
    }

}
