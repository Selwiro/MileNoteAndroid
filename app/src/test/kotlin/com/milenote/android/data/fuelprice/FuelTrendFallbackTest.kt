package com.milenote.android.data.fuelprice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 调价方向的兜底解析：oilpulse 这类只有价格的源胜出时，用 fanzbus `forecast.json` 的
 * `direction` 补回「预计下调 / 上调」（2026-11-16 反馈：油价卡只剩「下次调价」，预计涨跌消失）。
 */
class FuelTrendFallbackTest {

    private val repo = FuelPriceRepository()

    @Test
    fun `forecast direction maps to the card wording`() {
        assertEquals("下调", repo.parseForecastDirection("""{"direction":"下调","as_of":"2026-11-13"}"""))
        assertEquals("上调", repo.parseForecastDirection("""{"direction":"预计上调"}"""))
        assertEquals("搁浅", repo.parseForecastDirection("""{"direction":"搁浅"}"""))
    }

    @Test
    fun `missing or unreadable direction returns null instead of guessing`() {
        assertNull(repo.parseForecastDirection(null))
        assertNull(repo.parseForecastDirection(""))
        assertNull(repo.parseForecastDirection("not json"))
        assertNull(repo.parseForecastDirection("""{"note":"休市"}"""))
        assertNull(repo.parseForecastDirection("""{"direction":null}"""))
        assertNull(repo.parseForecastDirection("""{"direction":"待公布"}"""))
    }
}
