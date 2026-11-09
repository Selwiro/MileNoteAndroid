package com.milenote.android.domain.calc

import com.milenote.android.domain.evLedger
import com.milenote.android.domain.evRecord
import com.milenote.android.domain.fuelRecord
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：「满箱法排除基准油量，累计中途未加满油量」+ 纯电满电法。 */
class EnergyIntervalsTest {

    @Test
    fun `full tank excludes anchor and accumulates partials`() {
        val intervals = energyIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 45.0),
                fuelRecord("2026-01-05", 1200.0, 15.0, full = false),
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(1, intervals.size)
        assertEquals(7.5, intervals[0].rate, 1e-9)
        assertEquals(600.0, intervals[0].distance, 1e-9)
        assertEquals(45.0, intervals[0].amount, 1e-9)
        assertEquals("满箱法", intervals[0].source)
    }

    /** 对应 Web：「漏记重置基准，负里程和不足两次加满不计算」。 */
    @Test
    fun `missed resets anchor and negative distance is skipped`() {
        assertEquals(0, energyIntervals(listOf(fuelRecord("2026-01-01", 1000.0, 40.0))).size)

        val intervals = energyIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-02", 1300.0, 40.0, missed = true),
                fuelRecord("2026-01-03", 1800.0, 40.0),
            ),
        )
        assertEquals(1, intervals.size)
        assertEquals(8.0, intervals[0].rate, 1e-9)

        assertEquals(
            0,
            energyIntervals(
                listOf(
                    fuelRecord("2026-01-01", 1000.0, 40.0),
                    fuelRecord("2026-01-02", 500.0, 40.0),
                ),
            ).size,
        )
    }

    /** 漏记的满箱记录可以成为下一段基准；漏记自身升数不计入区间。 */
    @Test
    fun `missed full tank becomes next anchor`() {
        val intervals = energyIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-05", 1500.0, 20.0, full = true, missed = true),
                fuelRecord("2026-01-10", 2000.0, 30.0),
            ),
        )
        assertEquals(1, intervals.size)
        assertEquals(30.0, intervals[0].amount, 1e-9)
        assertEquals(6.0, intervals[0].rate, 1e-9)
    }

    /** 手填油耗不参与满箱法；没有里程或升数的记录会清空基准。 */
    @Test
    fun `manual records and missing odometer reset anchor`() {
        // 手填油耗记录被满箱法过滤，不会中断基准
        val manual = fuelRecord("2026-01-02", 1100.0, 10.0).copy(kind = "manual")
        val withManual = energyIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                manual,
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(1, withManual.size)
        assertEquals(5.0, withManual[0].rate, 1e-9)

        // 缺里程/升数的记录会清空基准，本段不产出
        val broken = energyIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-05", null, 20.0),
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(0, broken.size)

        assertNull(energyIntervals(emptyList()).firstOrNull())
    }

    /** stats 中手填油耗按里程加权并入综合油耗。 */
    @Test
    fun `manual intervals are weighted into fuel rate`() {
        val manual = EnergyInterval(date = "2026-01-02", distance = 300.0, amount = 24.0, rate = 8.0, source = "手动")
        val ledger = testLedger(
            records = listOf(
                Record(
                    id = "m1",
                    date = "2026-01-01",
                    category = "油费",
                    amount = 0.0,
                    kind = "manual",
                    rate = 6.0,
                    distance = 100.0,
                ),
                Record(
                    id = "m2",
                    date = "2026-01-02",
                    category = "油费",
                    amount = 0.0,
                    kind = "manual",
                    rate = 8.0,
                    distance = 300.0,
                ),
            ),
        )
        val stats = stats(ledger)
        assertEquals(2, stats.intervals.size)
        assertEquals(7.5, stats.energyRate!!, 1e-9)
        assertEquals(manual.source, "手动")
    }

    // ---- 纯电满电法（kWh） ----

    /** 纯电：首充只建基准，未充满的电量累计到下次充满，来源标为「满电法」。 */
    @Test
    fun `ev full charge excludes anchor and accumulates partial charges`() {
        val intervals = energyIntervals(
            listOf(
                evRecord("2026-01-01", 1000.0, 50.0),
                evRecord("2026-01-05", 1200.0, 10.0, full = false),
                evRecord("2026-01-10", 1600.0, 30.0),
            ),
            ev = true,
        )
        assertEquals(1, intervals.size)
        // (10 + 30) kWh / 600 km * 100 = 6.6667 kWh/100km
        assertEquals(40.0, intervals[0].amount, 1e-9)
        assertEquals(600.0, intervals[0].distance, 1e-9)
        assertEquals(6.666666666666667, intervals[0].rate, 1e-9)
        assertEquals("满电法", intervals[0].source)
    }

    /** 纯电：漏记的充满记录重建基准，其 kwh 不计入下一段。 */
    @Test
    fun `ev missed full charge becomes next anchor`() {
        val intervals = energyIntervals(
            listOf(
                evRecord("2026-01-01", 1000.0, 50.0),
                evRecord("2026-01-05", 1500.0, 20.0, full = true, missed = true),
                evRecord("2026-01-10", 2000.0, 30.0),
            ),
            ev = true,
        )
        assertEquals(1, intervals.size)
        assertEquals(30.0, intervals[0].amount, 1e-9)
        assertEquals(6.0, intervals[0].rate, 1e-9)
    }

    /** 纯电：里程倒退不产出区间；缺里程的记录清空基准。 */
    @Test
    fun `ev negative distance and missing odometer are skipped`() {
        assertEquals(
            0,
            energyIntervals(
                listOf(
                    evRecord("2026-01-01", 1000.0, 50.0),
                    evRecord("2026-01-02", 500.0, 50.0),
                ),
                ev = true,
            ).size,
        )
        // 缺里程（但有 kwh）的记录会清空基准，本段不产出
        assertEquals(
            0,
            energyIntervals(
                listOf(
                    evRecord("2026-01-01", 1000.0, 50.0),
                    evRecord("2026-01-05", null, 20.0),
                    evRecord("2026-01-10", 1600.0, 30.0),
                ),
                ev = true,
            ).size,
        )
    }

    /** 升与度不混算：两种单位各自只统计自己的字段，互不串入。 */
    @Test
    fun `ev and fuel intervals never mix units`() {
        val mixed = listOf(
            fuelRecord("2026-01-01", 1000.0, 40.0),
            fuelRecord("2026-01-10", 1500.0, 40.0),
            evRecord("2026-02-01", 2000.0, 20.0),
            evRecord("2026-02-10", 2400.0, 24.0),
        )
        val fuel = energyIntervals(mixed)
        assertEquals(1, fuel.size)
        assertEquals(40.0, fuel[0].amount, 1e-9)
        assertEquals(8.0, fuel[0].rate, 1e-9)

        val ev = energyIntervals(mixed, ev = true)
        assertEquals(1, ev.size)
        assertEquals(24.0, ev[0].amount, 1e-9)
        assertEquals(6.0, ev[0].rate, 1e-9)
        assertEquals("满电法", ev[0].source)
    }

    /** energyRateByRecord：纯电按记录 id 返回 kWh/100km，且不混入燃油记录。 */
    @Test
    fun `ev rate by record maps ids`() {
        val first = evRecord("2026-01-01", 1000.0, 50.0, id = "a")
        val second = evRecord("2026-01-10", 1600.0, 30.0, id = "b")
        val rates = energyRateByRecord(listOf(first, second, fuelRecord("2026-01-20", 2000.0, 40.0)), ev = true)
        assertEquals(setOf("b"), rates.keys)
        assertEquals(5.0, rates.getValue("b"), 1e-9)

        val fuelRates = energyRateByRecord(
            listOf(fuelRecord("2026-01-01", 1000.0, 40.0, id = "x"), fuelRecord("2026-01-10", 1500.0, 40.0, id = "y")),
        )
        assertEquals(setOf("y"), fuelRates.keys)
    }

    /** 纯电账本统计：energyRate 来自满电区间；evLedger 只是测试辅助。 */
    @Test
    fun `ev ledger stats use kwh intervals`() {
        val ledger = evLedger(
            records = listOf(
                evRecord("2026-01-01", 1000.0, 50.0),
                evRecord("2026-01-10", 1500.0, 40.0),
            ),
        )
        val result = stats(ledger)
        assertEquals(1, result.intervals.size)
        assertEquals(40.0, result.intervals[0].amount, 1e-9)
        assertEquals(8.0, result.energyRate!!, 1e-9)
    }
}
