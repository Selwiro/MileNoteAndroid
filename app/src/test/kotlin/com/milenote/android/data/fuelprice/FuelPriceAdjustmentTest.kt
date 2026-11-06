package com.milenote.android.data.fuelprice

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 下次调价文案换算（纯 JVM）。
 *
 * 站点文案「11 月 12 日 24 时」= 12 日 24:00 生效，展示必须保持 12 日，
 * 不能因为「24 时等于次日 0 时」顺延成 13 日。
 */
class FuelPriceAdjustmentTest {

    private fun date(text: String): LocalDate = LocalDate.parse(text)

    @Test
    fun `site says 12th 24h - keeps 12th instead of 13th`() {
        assertEquals(
            "11 月 12 日 24 时",
            nextAdjustmentLabel(month = 11, day = 12, today = date("2026-11-06")),
        )
    }

    @Test
    fun `adjustment day itself still shows today 24h`() {
        assertEquals(
            "11 月 12 日 24 时",
            nextAdjustmentLabel(month = 11, day = 12, today = date("2026-11-12")),
        )
    }

    @Test
    fun `day after adjustment - stale page returns null`() {
        assertNull(nextAdjustmentLabel(month = 11, day = 12, today = date("2026-11-13")))
    }

    @Test
    fun `month end 24h stays on last day of month`() {
        assertEquals(
            "11 月 30 日 24 时",
            nextAdjustmentLabel(month = 11, day = 30, today = date("2026-11-28")),
        )
    }

    @Test
    fun `late december - january window rolls into next year`() {
        assertEquals(
            "1 月 8 日 24 时",
            nextAdjustmentLabel(month = 1, day = 8, today = date("2026-12-26")),
        )
    }

    @Test
    fun `january with stale december page returns null`() {
        assertNull(nextAdjustmentLabel(month = 12, day = 30, today = date("2027-01-01")))
    }

    @Test
    fun `invalid month returns null`() {
        assertNull(nextAdjustmentLabel(month = 13, day = 1, today = date("2026-11-06")))
    }

    @Test
    fun `non existing calendar day returns null`() {
        assertNull(nextAdjustmentLabel(month = 2, day = 30, today = date("2026-11-06")))
        assertNull(nextAdjustmentLabel(month = 2, day = 29, today = date("2026-11-06")))
    }
}
