package com.milenote.android.domain.calc

import com.milenote.android.domain.fuelRecord
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：「满箱法排除基准油量，累计中途未加满油量」。 */
class FuelIntervalsTest {

    @Test
    fun `full tank excludes anchor and accumulates partials`() {
        val intervals = fuelIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 45.0),
                fuelRecord("2026-01-05", 1200.0, 15.0, full = false),
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(1, intervals.size)
        assertEquals(7.5, intervals[0].rate, 1e-9)
        assertEquals(600.0, intervals[0].distance, 1e-9)
        assertEquals("满箱法", intervals[0].source)
    }

    /** 对应 Web：「漏记重置基准，负里程和不足两次加满不计算」。 */
    @Test
    fun `missed resets anchor and negative distance is skipped`() {
        assertEquals(0, fuelIntervals(listOf(fuelRecord("2026-01-01", 1000.0, 40.0))).size)

        val intervals = fuelIntervals(
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
            fuelIntervals(
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
        val intervals = fuelIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-05", 1500.0, 20.0, full = true, missed = true),
                fuelRecord("2026-01-10", 2000.0, 30.0),
            ),
        )
        assertEquals(1, intervals.size)
        assertEquals(30.0, intervals[0].liters, 1e-9)
        assertEquals(6.0, intervals[0].rate, 1e-9)
    }

    /** 手填油耗不参与满箱法；没有里程或升数的记录会清空基准。 */
    @Test
    fun `manual records and missing odometer reset anchor`() {
        // 手填油耗记录被满箱法过滤，不会中断基准
        val manual = fuelRecord("2026-01-02", 1100.0, 10.0).copy(kind = "manual")
        val withManual = fuelIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                manual,
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(1, withManual.size)
        assertEquals(5.0, withManual[0].rate, 1e-9)

        // 缺里程/升数的记录会清空基准，本段不产出
        val broken = fuelIntervals(
            listOf(
                fuelRecord("2026-01-01", 1000.0, 40.0),
                fuelRecord("2026-01-05", null, 20.0),
                fuelRecord("2026-01-10", 1600.0, 30.0),
            ),
        )
        assertEquals(0, broken.size)

        assertNull(fuelIntervals(emptyList()).firstOrNull())
    }

    /** stats 中手填油耗按里程加权并入综合油耗。 */
    @Test
    fun `manual intervals are weighted into fuel rate`() {
        val manual = FuelInterval(date = "2026-01-02", distance = 300.0, liters = 24.0, rate = 8.0, source = "手动")
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
        assertEquals(7.5, stats.fuelRate!!, 1e-9)
        assertEquals(manual.source, "手动")
    }
}
