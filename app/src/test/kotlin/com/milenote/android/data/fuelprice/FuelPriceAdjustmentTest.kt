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

    // ---- 生效日口径（oil-price 数据集的 next.date）----

    @Test
    fun `effective date shows as the announcement day 24h`() {
        // 2026-11-27 00:00 生效 = 公告口径 11 月 26 日 24 时（2026 年第 23 轮调价窗口）
        assertEquals(
            "11 月 26 日 24 时",
            effectiveAdjustmentLabel(date("2026-11-27"), date("2026-11-15")),
        )
        // 生效日就在明天 → 公告日是今天
        assertEquals(
            "11 月 15 日 24 时",
            effectiveAdjustmentLabel(date("2026-11-16"), date("2026-11-15")),
        )
    }

    @Test
    fun `effective dates in the past or too far ahead are hidden`() {
        assertNull(effectiveAdjustmentLabel(null, date("2026-11-15")))
        // 上一轮（11-13 生效）已过，不能当成「下次」
        assertNull(effectiveAdjustmentLabel(date("2026-11-13"), date("2026-11-15")))
        assertNull(effectiveAdjustmentLabel(date("2026-10-29"), date("2026-11-15")))
        // 超过 31 天的远期日期不展示（数据源异常 / 跨年脏值）
        assertNull(effectiveAdjustmentLabel(date("2027-03-01"), date("2026-11-15")))
    }
}
