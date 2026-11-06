package com.milenote.android.data.fuelprice

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一个地区的今日油价与调价信息。 */
data class FuelPriceInfo(
    val regionName: String,
    val price92: Double?,
    val price95: Double?,
    val price98: Double?,
    val price0: Double?,
    val nextAdjustmentText: String?,
    val trend: String?,
    val forecast: String?,
    val isFallback: Boolean = false,
)

/** 32 个省市：站点 slug + 中文名（与 qiyoujiage.com 导航一致）。 */
data class FuelRegion(val slug: String, val name: String)

/** 记住的油价地区：省份 + 可选的市（如 云南 + 红河哈尼族彝族自治州）。 */
data class FuelLocation(
    val province: FuelRegion = FuelRegions.beijing,
    val cityName: String? = null,
) {
    val displayName: String get() = cityName?.takeIf { it.isNotBlank() }?.let(FuelRegions::shortCityName) ?: province.name
}

object FuelRegions {
    val all: List<FuelRegion> = listOf(
        FuelRegion("beijing", "北京"),
        FuelRegion("tianjin", "天津"),
        FuelRegion("hebei", "河北"),
        FuelRegion("shanxi", "山西"),
        FuelRegion("neimenggu", "内蒙古"),
        FuelRegion("liaoning", "辽宁"),
        FuelRegion("jilin", "吉林"),
        FuelRegion("heilongjiang", "黑龙江"),
        FuelRegion("shanghai", "上海"),
        FuelRegion("jiangsu", "江苏"),
        FuelRegion("zhejiang", "浙江"),
        FuelRegion("anhui", "安徽"),
        FuelRegion("fujian", "福建"),
        FuelRegion("jiangxi", "江西"),
        FuelRegion("shandong", "山东"),
        FuelRegion("henan", "河南"),
        FuelRegion("hubei", "湖北"),
        FuelRegion("hunan", "湖南"),
        FuelRegion("guangdong", "广东"),
        FuelRegion("shenzhen", "深圳"),
        FuelRegion("guangxi", "广西"),
        FuelRegion("hainan", "海南"),
        FuelRegion("chongqing", "重庆"),
        FuelRegion("sichuan", "四川"),
        FuelRegion("guizhou", "贵州"),
        FuelRegion("yunnan", "云南"),
        FuelRegion("xizang", "西藏"),
        FuelRegion("shanxi-3", "陕西"),
        FuelRegion("gansu", "甘肃"),
        FuelRegion("qinghai", "青海"),
        FuelRegion("ningxia", "宁夏"),
        FuelRegion("xinjiang", "新疆"),
    )

    val beijing: FuelRegion get() = all.first()

    /** 直辖市没有地级市子页，直接用省级页面。 */
    val municipalities: Set<String> = setOf("beijing", "tianjin", "shanghai", "chongqing")

    fun bySlug(slug: String?): FuelRegion? = all.firstOrNull { it.slug == slug }

    /** “红河哈尼族彝族自治州” → “红河”；“延边朝鲜族自治州” → “延边”。 */
    fun shortCityName(raw: String): String {
        val trimmed = raw.replace(" ", "").trim()
        var base = if (trimmed.contains("自治州")) trimmed.substringBefore("自治州") else trimmed
        while (true) {
            val match = Regex("([\\u4e00-\\u9fa5]{1,4}族)$").find(base) ?: break
            base = base.removeSuffix(match.value)
        }
        return base.removeSuffix("地区").removeSuffix("盟").removeSuffix("市").ifBlank { trimmed }
    }

    /** 从 Geocoder 的省/市文本里匹配地区；匹配不到返回 null。 */
    fun match(vararg names: String?): FuelRegion? {
        val haystack = names.filterNotNull().joinToString(" ")
        if (haystack.isBlank()) return null
        return all.firstOrNull { haystack.contains(it.name) }
            ?: all.firstOrNull { it.name.length > 1 && haystack.contains(it.name.take(2)) }
    }
}

/**
 * 抓取 qiyoujiage.com 的今日油价页面并解析。
 *
 * 省级页面（`/yunnan.shtml`）里包含地级市链接（`/yunnan/honghe.shtml`）；
 * 先取省级页面，按定位到的城市名匹配城市链接，再抓城市页面，得到该市油价；
 * 匹配不到城市或城市页面失败时回退省级页面。
 */
class FuelPriceRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(location: FuelLocation): FuelPriceInfo = withContext(Dispatchers.IO) {
        try {
            val provinceHtml = fetchHtml(location.province.slug)
            val citySlug = location.cityName
                ?.takeIf { location.province.slug !in FuelRegions.municipalities }
                ?.let { findCitySlug(provinceHtml, location.province.slug, it) }
            val html = if (citySlug != null) {
                runCatching { fetchHtml("${location.province.slug}/$citySlug") }.getOrDefault(provinceHtml)
            } else {
                provinceHtml
            }
            parse(location.displayName, html)
        } catch (t: Throwable) {
            offlineFallback(location)
        }
    }

    private fun fetchHtml(path: String): String {
        val request = Request.Builder()
            .url("http://www.qiyoujiage.com/$path.shtml")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("empty body")
            return decode(bytes)
        }
    }

    /** 从省级页面里找城市链接：href 形如 `yunnan/honghe.shtml`，文本如“红河”。 */
    private fun findCitySlug(html: String, provinceSlug: String, cityName: String): String? {
        val normalizedCity = normalizeCity(cityName)
        if (normalizedCity.isBlank()) return null
        var best: Pair<Int, String>? = null
        ANCHOR_RE.findAll(html).forEach { match ->
            val href = match.groupValues[1]
            val label = stripTags(match.groupValues[2]).trim()
            val path = href.substringAfter("qiyoujiage.com/", href).trimStart('/')
            val clean = path.substringBefore('?').removeSuffix(".shtml")
            val segments = clean.split('/').filter { it.isNotBlank() }
            if (segments.size != 2 || segments[0] != provinceSlug) return@forEach
            if (label.isBlank()) return@forEach
            val normalizedLabel = normalizeCity(label)
            if (normalizedLabel.isBlank()) return@forEach
            val score = when {
                normalizedLabel == normalizedCity -> 3
                normalizedCity.contains(normalizedLabel) -> 2
                normalizedLabel.contains(normalizedCity) -> 1
                else -> 0
            }
            if (score > 0 && (best == null || score > best!!.first)) {
                best = score to segments[1]
            }
        }
        return best?.second
    }

    private fun normalizeCity(name: String): String =
        name.replace(" ", "")
            .replace("今日油价", "")
            .replace("汽油价格", "")
            .replace("柴油价格", "")
            .replace("油价", "")
            .replace("加油站查询", "")
            .removeSuffix("市")
            .removeSuffix("地区")
            .removeSuffix("自治州")
            .removeSuffix("哈尼族彝族自治州")
            .removeSuffix("傣族景颇族自治州")
            .removeSuffix("壮族苗族自治州")
            .removeSuffix("彝族回族自治州")
            .removeSuffix("白族自治州")
            .removeSuffix("藏族自治州")
            .removeSuffix("傈僳族自治州")
            .removeSuffix("蒙古族藏族自治州")
            .removeSuffix("回族土族自治州")
            .removeSuffix("柯尔克孜自治州")
            .removeSuffix("蒙古自治州")
            .removeSuffix("哈萨克自治州")
            .take(4)

    private fun parse(regionName: String, html: String): FuelPriceInfo {
        val prices = mutableMapOf<String, Double>()
        DL_RE.findAll(html).forEach { dl ->
            val dt = DT_RE.find(dl.groupValues[1])?.groupValues?.get(1)?.let(::stripTags) ?: return@forEach
            val dd = DD_RE.find(dl.groupValues[1])?.groupValues?.get(1)?.let(::stripTags) ?: return@forEach
            val oil = OIL_NO_RE.find(dt)?.groupValues?.get(1) ?: return@forEach
            val price = PRICE_RE.find(dd)?.value?.toDoubleOrNull() ?: return@forEach
            prices[oil] = price
        }
        if (prices.isEmpty()) error("no prices")
        val forecast = parseForecast(html)
        val trend = when {
            forecast == null -> null
            TREND_UP_RE.containsMatchIn(forecast) -> "上调"
            TREND_DOWN_RE.containsMatchIn(forecast) -> "下调"
            else -> "搁浅"
        }
        return FuelPriceInfo(
            regionName = regionName,
            price92 = prices["92"],
            price95 = prices["95"],
            price98 = prices["98"],
            price0 = prices["0"],
            nextAdjustmentText = parseNextAdjustment(html),
            trend = trend,
            forecast = forecast,
        )
    }

    private fun parseNextAdjustment(html: String): String? {
        val match = ADJUST_RE.find(html) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        return nextAdjustmentLabel(month, day, LocalDate.now(ZoneId.of("Asia/Shanghai")))
    }

    private fun parseForecast(html: String): String? {
        SPAN_RE.findAll(html).forEach { span ->
            val text = stripTags(span.groupValues[1]).trim()
            if (TREND_UP_RE.containsMatchIn(text) || TREND_DOWN_RE.containsMatchIn(text)) return text
        }
        return null
    }

    private fun stripTags(text: String): String =
        TAG_RE.replace(BR_RE.replace(text, " "), " ").replace("&nbsp;", " ").trim()

    private fun decode(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { bytes.toString(charset("GB18030")) }.getOrDefault(utf8)
    }

    private fun offlineFallback(location: FuelLocation): FuelPriceInfo = FuelPriceInfo(
        regionName = "${location.displayName}（参考）",
        price92 = 7.45,
        price95 = 7.93,
        price98 = 8.66,
        price0 = 7.11,
        nextAdjustmentText = null,
        trend = null,
        forecast = "实时油价获取失败，当前显示参考价（需联网后刷新）",
        isFallback = true,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private val ANCHOR_RE = Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val DL_RE = Regex("<dl[^>]*>(.*?)</dl>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val DT_RE = Regex("<dt[^>]*>(.*?)</dt>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val DD_RE = Regex("<dd[^>]*>(.*?)</dd>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TAG_RE = Regex("<[^>]+>")
        private val BR_RE = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val OIL_NO_RE = Regex("(\\d{1,2})\\s*#")
        private val PRICE_RE = Regex("\\d+(?:\\.\\d+)?")
        private val ADJUST_RE = Regex("下次?油价\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*24\\s*时(?:前后)?调整")
        private val SPAN_RE = Regex("<span[^>]*>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TREND_UP_RE = Regex("上调|上涨|涨幅")
        private val TREND_DOWN_RE = Regex("下调|下跌|跌幅")
    }
}
