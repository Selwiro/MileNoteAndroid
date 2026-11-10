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

    // ---------------- qiyoujiage HTML ----------------

    @Test
    fun `parses qiyoujiage html prices`() {
        val html = """
            <dl><dt>92#汽油</dt><dd>7.45 元/升</dd></dl>
            <dl><dt>95#汽油</dt><dd>7.93 元/升</dd></dl>
            <dl><dt>98#汽油</dt><dd>8.66 元/升</dd></dl>
            <dl><dt>0#柴油</dt><dd>7.11 元/升</dd></dl>
            <span>预计上调 120 元/吨</span>
        """.trimIndent()
        val info = FuelPriceRepository.parseQiyoujiage("北京", html)!!
        assertEquals(7.45, info.price92!!, 1e-9)
        assertEquals(7.93, info.price95!!, 1e-9)
        assertEquals(8.66, info.price98!!, 1e-9)
        assertEquals(7.11, info.price0!!, 1e-9)
        assertEquals("上调", info.trend)
        assertFalse(info.isFallback)
    }

    @Test
    fun `qiyoujiage without prices returns null`() {
        assertNull(FuelPriceRepository.parseQiyoujiage("北京", "<html><body>维护中</body></html>"))
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
}
