package com.milenote.android.data.fuelprice

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 油价多源解析、回退顺序与缓存周期策略（全部纯 JVM，不联网）。 */
class FuelPriceParsingTest {

    // ---------------- fanzbus 数据集（省级 + 城市级 + 预测方向） ----------------

    private val fanzbusPrices = """
        {
          "fetched_at": "2026-11-10T07:00:00+08:00",
          "next_adjustment_label": "11月12日24时",
          "provinces": [
            {"province": "北京", "p92": 8.89, "p95": 9.46, "p98": 10.96, "d0": 8.65},
            {"province": "云南", "p92": 9.25, "p95": 9.92, "p98": 10.60, "d0": 8.90}
          ]
        }
    """.trimIndent()

    private val fanzbusCities = """
        {
          "status": "ok",
          "non_unified_provinces": ["云南"],
          "provinces": {
            "云南": [
              {"city": "红河", "pinyin": "honghe", "p92": 9.19, "p95": 9.85, "p98": 10.53, "d0": 8.82, "status": "ok"},
              {"city": "昆明", "pinyin": "kunming", "p92": 9.31, "p95": 9.98, "p98": 10.66, "d0": 8.95, "status": "ok"}
            ]
          }
        }
    """.trimIndent()

    private val fanzbusForecast = """{"status":"estimated","as_of":"2026-11-10","direction":"下调","note":"规则估算，非官方预测"}"""

    @Test
    fun `fanzbus province price and downward forecast`() {
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "北京", provinceName = "北京", cityName = null,
            pricesBody = fanzbusPrices, citiesBody = fanzbusCities, forecastBody = fanzbusForecast,
            today = LocalDate.parse("2026-11-10"),
        )!!
        assertEquals(8.89, info.price92!!, 1e-9)
        assertEquals(9.46, info.price95!!, 1e-9)
        assertEquals(10.96, info.price98!!, 1e-9)
        assertEquals(8.65, info.price0!!, 1e-9)
        assertEquals("下调", info.trend)
        assertEquals("11 月 12 日 24 时", info.nextAdjustmentText)
    }

    @Test
    fun `fanzbus uses city level price for non-unified province`() {
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "云南红河", provinceName = "云南", cityName = "红河",
            pricesBody = fanzbusPrices, citiesBody = fanzbusCities, forecastBody = fanzbusForecast,
            today = LocalDate.parse("2026-11-10"),
        )!!
        assertEquals(9.19, info.price92!!, 1e-9)
        assertEquals(9.85, info.price95!!, 1e-9)
        assertEquals(8.82, info.price0!!, 1e-9)
        assertEquals("下调", info.trend)
    }

    @Test
    fun `fanzbus falls back to province when city is missing`() {
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "云南某市", provinceName = "云南", cityName = "不存在",
            pricesBody = fanzbusPrices, citiesBody = fanzbusCities, forecastBody = fanzbusForecast,
            today = LocalDate.parse("2026-11-10"),
        )!!
        assertEquals(9.25, info.price92!!, 1e-9)
    }

    @Test
    fun `fanzbus tolerates missing forecast and bad label`() {
        val prices = """{"next_adjustment_label":"待公布","provinces":[{"province":"北京","p92":8.89}]}"""
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "北京", provinceName = "北京", cityName = null,
            pricesBody = prices, citiesBody = null, forecastBody = null,
            today = LocalDate.parse("2026-11-10"),
        )!!
        assertNull(info.trend)
        assertEquals("待公布", info.nextAdjustmentText)
    }

    @Test
    fun `normalizes adjustment labels`() {
        val before = LocalDate.parse("2026-11-10")
        assertEquals("11 月 12 日 24 时", FuelPriceRepository.normalizeAdjustmentLabel("11月12日24时", before))
        assertEquals("11 月 12 日 24 时", FuelPriceRepository.normalizeAdjustmentLabel("11月12日24时调整", before))
        assertNull(FuelPriceRepository.normalizeAdjustmentLabel(null, before))
    }

    // ---------------- 每日静态 JSON 数据集 ----------------

    @Test
    fun `parses oil-price dataset prices and downward trend`() {
        val body = """
            {
              "next": {"date":"2026-11-13","direction":"预计下调","reason":"本轮原油均价低于上一轮"},
              "provinces_data": {
                "北京": {
                  "92": [7.85, 7.82, 8.89],
                  "95": [8.35, 8.31, 9.46],
                  "98": [9.85, 9.80, 10.96],
                  "0":  [7.55, 7.50, 8.65]
                }
              }
            }
        """.trimIndent()
        val info = FuelPriceRepository.parseOilPriceJson("北京", "北京", body, LocalDate.parse("2026-11-13"))!!
        assertEquals(8.89, info.price92!!, 1e-9)
        assertEquals(9.46, info.price95!!, 1e-9)
        assertEquals(10.96, info.price98!!, 1e-9)
        assertEquals(8.65, info.price0!!, 1e-9)
        assertEquals("下调", info.trend)
        assertEquals("11 月 13 日", info.nextAdjustmentText)
    }

    @Test
    fun `oil-price dataset for other province returns null`() {
        val body = """{"next":{},"provinces_data":{"上海":{"92":[8.0]}}}"""
        assertNull(FuelPriceRepository.parseOilPriceJson("北京", "北京", body))
    }

    @Test
    fun `parses oilpulse dataset`() {
        val body = """
            {"updated_at":"2026-11-10T07:12:28+08:00",
             "provinces":{"北京":{"province":"北京","t0":"8.65","t89":"8.26","t92":"8.89","t95":"9.46","t98":"10.96"}}}
        """.trimIndent()
        val info = FuelPriceRepository.parseOilPulseJson("北京", "北京", body, LocalDate.parse("2026-11-10"))!!
        assertEquals(8.89, info.price92!!, 1e-9)
        assertEquals(10.96, info.price98!!, 1e-9)
        assertEquals(8.65, info.price0!!, 1e-9)
        assertNull(info.trend)
    }

    @Test
    fun `static datasets reject garbage`() {
        assertNull(FuelPriceRepository.parseOilPriceJson("北京", "北京", "<html>502</html>"))
        assertNull(FuelPriceRepository.parseOilPulseJson("北京", "北京", "{}"))
    }

    // ---------------- 通用 JSON 解析（三种常见 schema） ----------------

    @Test
    fun `parses flat data object json`() {
        val body = """
            {"success":true,"data":{"province":"北京","p92":"7.45","p95":"7.93","p98":"8.66","p0":"7.11"}}
        """.trimIndent()
        val info = FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, body)!!
        assertEquals(7.45, info.price92!!, 1e-9)
        assertEquals(7.93, info.price95!!, 1e-9)
    }

    @Test
    fun `parses list of provinces json`() {
        val body = """
            {"code":200,"data":[
              {"province":"上海","p92":"7.50","p95":"7.98"},
              {"province":"北京","p92":7.45,"p95":7.93,"p98":8.66,"p0":7.11}
            ]}
        """.trimIndent()
        val info = FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, body)!!
        assertEquals(7.45, info.price92!!, 1e-9)
        assertEquals(8.66, info.price98!!, 1e-9)
    }

    @Test
    fun `parses province keyed nested json`() {
        val body = """
            {"code":200,"data":{"北京":{"p92":7.45,"p95":7.93,"p98":8.66,"p0":7.11}}}
        """.trimIndent()
        val info = FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, body)!!
        assertEquals(7.45, info.price92!!, 1e-9)
        assertEquals(7.11, info.price0!!, 1e-9)
    }

    @Test
    fun `json for other province is rejected`() {
        val body = """{"success":true,"data":{"province":"上海","p92":"7.50"}}"""
        assertNull(FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, body))
    }

    @Test
    fun `invalid json returns null instead of throwing`() {
        assertNull(FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, "<html>502</html>"))
        assertNull(FuelPriceRepository.parseJsonFuelPrice("北京", "北京", null, ""))
    }

    // ---------------- 数据新鲜度（上游停更 / 调价未滚动） ----------------

    /** fanzbus 文件的元信息齐全：用于验证「价格没变但数据已过期」的判定。 */
    private fun fanzbusMeta(
        fetchedAt: String,
        nextAdjustment: String?,
        rolled: Boolean?,
    ): String = buildString {
        append("""{"fetched_at":"$fetchedAt",""")
        if (nextAdjustment != null) append(""""next_adjustment":"$nextAdjustment",""")
        if (rolled != null) append(""""next_adjustment_rolled":$rolled,""")
        append(""""next_adjustment_label":"11月12日24时",""")
        append(""""provinces":[{"province":"北京","p92":8.89}]}""")
    }

    @Test
    fun `fanzbus data whose adjustment took effect but not rolled is stale`() {
        // 2026-11-13 当天：12 日 24 时已生效，文件仍写 rolled=false → 里面还是调价前的价。
        val body = fanzbusMeta("2026-11-13T00:30:00+08:00", "2026-11-13T00:00:00+08:00", false)
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "北京", provinceName = "北京", cityName = null,
            pricesBody = body, citiesBody = null, forecastBody = null,
            today = LocalDate.parse("2026-11-13"),
        )!!
        assertTrue(info.dataStale)
        assertEquals("2026-11-13", info.dataDate)
        assertNull(info.nextAdjustmentText)
        assertNull(info.trend)
    }

    @Test
    fun `fanzbus data rolled into the new cycle is fresh`() {
        val body = fanzbusMeta("2026-11-13T06:10:00+08:00", "2026-11-27T00:00:00+08:00", false)
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "北京", provinceName = "北京", cityName = null,
            pricesBody = body, citiesBody = null, forecastBody = null,
            today = LocalDate.parse("2026-11-13"),
        )!!
        assertFalse(info.dataStale)
    }

    @Test
    fun `fanzbus data older than one day is stale even before the next adjustment`() {
        val body = fanzbusMeta("2026-10-30T21:27:02+08:00", "2026-11-27T00:00:00+08:00", null)
        val info = FuelPriceRepository.parseFanzbusJson(
            regionName = "北京", provinceName = "北京", cityName = null,
            pricesBody = body, citiesBody = null, forecastBody = null,
            today = LocalDate.parse("2026-11-13"),
        )!!
        assertTrue(info.dataStale)
        assertEquals("2026-10-30", info.dataDate)
    }

    @Test
    fun `oil-price dataset from before the latest adjustment is stale`() {
        val body = """
            {
              "meta": {"date_to": "2026-10-30"},
              "next": {"date": "2026-11-13", "direction": "预计下调", "last_adjust": "2026-10-30"},
              "provinces_data": {"北京": {"92": [8.89], "95": [9.46]}}
            }
        """.trimIndent()
        val info = FuelPriceRepository.parseOilPriceJson(
            "北京", "北京", body, LocalDate.parse("2026-11-13"),
        )!!
        assertTrue(info.dataStale)
    }

    @Test
    fun `oil-price dataset covering the latest adjustment is fresh`() {
        val body = """
            {
              "meta": {"date_to": "2026-11-13"},
              "next": {"date": "2026-11-27", "direction": "可能搁浅", "last_adjust": "2026-11-13"},
              "provinces_data": {"北京": {"92": [8.89, 8.37], "95": [9.46, 8.91]}}
            }
        """.trimIndent()
        val info = FuelPriceRepository.parseOilPriceJson(
            "北京", "北京", body, LocalDate.parse("2026-11-13"),
        )!!
        assertFalse(info.dataStale)
        assertEquals(8.37, info.price92!!, 1e-9)
        assertEquals("搁浅", info.trend)
        assertEquals("11 月 27 日", info.nextAdjustmentText)
    }

    @Test
    fun `stale data is skipped in favor of the next fresh source`() = runBlocking {
        val stale = object : FuelPriceSource {
            override val id = "stale"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? =
                FuelPriceInfo("北京", price92 = 8.89, dataDate = "2026-10-30", dataStale = true)
        }
        val fresh = object : FuelPriceSource {
            override val id = "fresh"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? =
                FuelPriceInfo("北京", price92 = 8.37, dataDate = "2026-11-13")
        }
        val info = FuelPriceRepository(listOf(stale, fresh)).fetch(FuelLocation())!!
        assertEquals("fresh", info.source)
        assertEquals(8.37, info.price92!!, 1e-9)
        assertFalse(info.dataStale)
    }

    @Test
    fun `stale source is used as fallback only when every source is stale`() = runBlocking {
        val stale = object : FuelPriceSource {
            override val id = "stale"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? =
                FuelPriceInfo("北京", price92 = 8.89, dataDate = "2026-10-30", dataStale = true)
        }
        val info = FuelPriceRepository(listOf(stale)).fetch(FuelLocation())!!
        assertEquals("stale", info.source)
        assertTrue(info.dataStale)
    }

    @Test
    fun `stale-by-age allows one day of publish lag`() {
        val today = LocalDate.parse("2026-11-13")
        assertFalse(FuelPriceRepository.isStaleByAge(LocalDate.parse("2026-11-12"), today))
        assertTrue(FuelPriceRepository.isStaleByAge(LocalDate.parse("2026-11-11"), today))
        assertFalse(FuelPriceRepository.isStaleByAge(null, today))
    }

    // ---------------- 多源回退顺序 ----------------

    @Test
    fun `repository falls back to next source when one fails`() = runBlocking {
        val failing = object : FuelPriceSource {
            override val id = "bad"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = error("network down")
        }
        val empty = object : FuelPriceSource {
            override val id = "empty"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = null
        }
        val good = object : FuelPriceSource {
            override val id = "good"
            override suspend fun fetch(location: FuelLocation): FuelPriceInfo? =
                FuelPriceInfo("北京", price92 = 7.45)
        }
        val repo = FuelPriceRepository(listOf(failing, empty, good))
        val info = repo.fetch(FuelLocation())!!
        assertEquals("good", info.source)
        assertEquals(7.45, info.price92!!, 1e-9)
    }

    @Test
    fun `repository returns null when all sources fail`() = runBlocking {
        val repo = FuelPriceRepository(
            listOf(
                object : FuelPriceSource {
                    override val id = "bad"
                    override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = error("down")
                },
            ),
        )
        assertNull(repo.fetch(FuelLocation()))
        assertTrue(repo.referenceInfo(FuelLocation()).isFallback)
    }

    // ---------------- 缓存与刷新周期 ----------------

    @Test
    fun `cache is fresh within the same day and stale after midnight`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun millis(y: Int, m: Int, d: Int, h: Int, min: Int) =
            java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
        val fetched = millis(2026, 11, 10, 9, 0)
        assertFalse(FuelPriceCache.isStale(fetched, millis(2026, 11, 10, 23, 59), zone))
        assertTrue(FuelPriceCache.isStale(fetched, millis(2026, 11, 11, 0, 1), zone))
        assertTrue(FuelPriceCache.isStale(0L, millis(2026, 11, 10, 12, 0), zone))
    }

    @Test
    fun `cache entry round trips through json`() {
        val cached = CachedFuelPrice(
            info = FuelPriceInfo("北京", price92 = 7.45, nextAdjustmentText = "11 月 12 日 24 时", trend = "上调"),
            fetchedAtMillis = 1_700_000_000_000L,
            version = FuelPriceCache.CACHE_VERSION,
        )
        val decoded = FuelPriceCache.decode(FuelPriceCache.encode(cached))
        assertNotNull(decoded)
        assertEquals(7.45, decoded!!.info.price92!!, 1e-9)
        assertEquals("上调", decoded.info.trend)
        assertEquals(1_700_000_000_000L, decoded.fetchedAtMillis)
    }

    @Test
    fun `cache decode tolerates garbage`() {
        assertNull(FuelPriceCache.decode("not json"))
    }

    @Test
    fun `legacy cache without version is rejected so upgrades refetch`() {
        // 0.36.0 及更早版本没有 version 字段（旧数据源 qiyoujiage）：升级后必须视为失效。
        val legacy = """{"info":{"regionName":"北京","price92":8.89},"fetchedAtMillis":1794279667005}"""
        assertNull(FuelPriceCache.decode(legacy))
    }
}
