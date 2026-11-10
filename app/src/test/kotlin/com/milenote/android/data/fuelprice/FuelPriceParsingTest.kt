package com.milenote.android.data.fuelprice

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
        val info = FuelPriceRepository.parseOilPriceJson("北京", "北京", body)!!
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
        val info = FuelPriceRepository.parseOilPulseJson("北京", "北京", body)!!
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
