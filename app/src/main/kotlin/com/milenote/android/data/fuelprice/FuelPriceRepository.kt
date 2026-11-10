package com.milenote.android.data.fuelprice

import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一个地区的今日油价与调价信息。 */
@Serializable
data class FuelPriceInfo(
    val regionName: String,
    val price92: Double? = null,
    val price95: Double? = null,
    val price98: Double? = null,
    val price0: Double? = null,
    val nextAdjustmentText: String? = null,
    val trend: String? = null,
    val forecast: String? = null,
    val isFallback: Boolean = false,
    /** 实际命中的数据源 id（调试/展示用）；参考价为 null。 */
    val source: String? = null,
) {
    val hasPrice: Boolean get() = price92 != null || price95 != null || price98 != null || price0 != null
}

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
 * 单个油价数据源。实现必须**快速失败**（异常/超时向上抛或返回 null），
 * 由 [FuelPriceRepository] 负责顺序回退。
 */
interface FuelPriceSource {
    val id: String

    suspend fun fetch(location: FuelLocation): FuelPriceInfo?
}

/**
 * 多数据源油价仓库：按顺序尝试，命中即返回；全部失败返回 null（由调用方决定显示缓存还是参考价）。
 *
 * 数据源（增加顺序即回退顺序）：
 * 1. `qiyoujiage-html`：qiyoujiage.com 省级/城市 HTML（最稳、有「下次调价」，但站点只有 HTTP，
 *    release 靠 network-security-config 单独放行该域名）；
 * 2. `vvhan-json` / `oioweb-json` / `tenapi-json`：免费 HTTPS 公共 API（格式各异，用通用 JSON 解析）；
 * 3. 参考价由 [referenceInfo] 提供（只有无本地缓存且全部失败时才会用到）。
 *
 * 缓存与刷新周期不在这里：见 [FuelPriceCache]（同一天内只自动刷新一次，手动刷新不受限）。
 */
class FuelPriceRepository(
    private val sources: List<FuelPriceSource> = defaultSources(),
    private val client: OkHttpClient = defaultClient(),
) {
    /** 抓取一次；全部数据源失败返回 null（不要用参考价冒充实时价）。 */
    suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
        for (source in sources) {
            val info = runCatching {
                withTimeout(SOURCE_TIMEOUT_MS) { source.fetch(location) }
            }.getOrNull()
            if (info != null && info.hasPrice) return@withContext info.copy(source = source.id)
        }
        null
    }

    /** 无缓存且全部失败时的内置参考价（明确标记 isFallback）。 */
    fun referenceInfo(location: FuelLocation): FuelPriceInfo = FuelPriceInfo(
        regionName = tr("%s（参考）", "%s (reference)", DataLabels.region(location.displayName)),
        price92 = 7.45,
        price95 = 7.93,
        price98 = 8.66,
        price0 = 7.11,
        nextAdjustmentText = null,
        trend = null,
        forecast = tr(
            "实时油价获取失败，当前显示参考价（联网后会自动重试）",
            "Live fuel prices unavailable — showing reference prices (will retry when online)",
        ),
        isFallback = true,
    )

    private abstract class HttpSource(protected val client: OkHttpClient) : FuelPriceSource {
        protected fun getText(url: String): String {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: error("empty body")
                return decode(bytes)
            }
        }

        private fun decode(bytes: ByteArray): String {
            val utf8 = bytes.toString(Charsets.UTF_8)
            if (!utf8.contains('\uFFFD')) return utf8
            return runCatching { bytes.toString(charset("GB18030")) }.getOrDefault(utf8)
        }
    }

    /** qiyoujiage.com：省级页面（`/yunnan.shtml`）含地级市链接（`/yunnan/honghe.shtml`）。 */
    private class QiyoujiageHtmlSource(client: OkHttpClient) : HttpSource(client) {
        override val id: String = "qiyoujiage-html"

        override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
            val provinceHtml = runCatching { getText(baseUrl(location.province.slug)) }.getOrNull()
                ?: return@withContext null
            val citySlug = location.cityName
                ?.takeIf { location.province.slug !in FuelRegions.municipalities }
                ?.let { findCitySlug(provinceHtml, location.province.slug, it) }
            val html = if (citySlug != null) {
                runCatching { getText(baseUrl("${location.province.slug}/$citySlug")) }.getOrDefault(provinceHtml)
            } else {
                provinceHtml
            }
            parseQiyoujiage(DataLabels.region(location.displayName), html)
        }

        private fun baseUrl(path: String): String =
            "http://www.qiyoujiage.com/$path.shtml"

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
    }

    /** 通用 JSON 数据源：把响应丢给 [parseJsonFuelPrice]，字段名按常见写法匹配。 */
    private class JsonApiSource(
        client: OkHttpClient,
        override val id: String,
        private val urlBuilder: (String) -> String,
    ) : HttpSource(client) {
        override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
            val province = location.province.name
            val body = runCatching { getText(urlBuilder(province)) }.getOrNull() ?: return@withContext null
            parseJsonFuelPrice(
                regionName = DataLabels.region(location.displayName),
                provinceName = province,
                cityName = location.cityName?.let(FuelRegions::shortCityName),
                body = body,
            )
        }
    }

    companion object {
        /** 单数据源整体超时：站点慢就快速回退到下一个。 */
        const val SOURCE_TIMEOUT_MS = 9_000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        /** 默认回退顺序：HTML 站点（含调价信息）→ 三个免费 HTTPS JSON API。 */
        fun defaultSources(): List<FuelPriceSource> {
            val client = defaultClient()
            return listOf(
                QiyoujiageHtmlSource(client),
                JsonApiSource(client, "vvhan-json") { province ->
                    "https://api.vvhan.com/api/oil?province=${urlEncode(province)}"
                },
                JsonApiSource(client, "oioweb-json") { province ->
                    "https://api.oioweb.cn/api/common/oilPrice?province=${urlEncode(province)}"
                },
                JsonApiSource(client, "tenapi-json") { province ->
                    "https://tenapi.cn/v2/oilprice?province=${urlEncode(province)}"
                },
            )
        }

        private fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

        private val ANCHOR_RE = Regex(
            "<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
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

        internal fun stripTags(text: String): String =
            TAG_RE.replace(BR_RE.replace(text, " "), " ").replace("&nbsp;", " ").trim()

        /** 解析 qiyoujiage 页面；解析不到价格返回 null（不要用异常打断回退链）。 */
        internal fun parseQiyoujiage(regionName: String, html: String): FuelPriceInfo? {
            val prices = mutableMapOf<String, Double>()
            DL_RE.findAll(html).forEach { dl ->
                val dt = DT_RE.find(dl.groupValues[1])?.groupValues?.get(1)?.let(::stripTags) ?: return@forEach
                val dd = DD_RE.find(dl.groupValues[1])?.groupValues?.get(1)?.let(::stripTags) ?: return@forEach
                val oil = OIL_NO_RE.find(dt)?.groupValues?.get(1) ?: return@forEach
                val price = PRICE_RE.find(dd)?.value?.toDoubleOrNull() ?: return@forEach
                prices[oil] = price
            }
            if (prices.isEmpty()) return null
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

        /**
         * 通用 JSON 解析：不绑定某个 API 的 schema。
         *
         * 找到「包含省份名」的对象（字段值或键名均可），把它的整棵子树摊平成 `路径 -> 字符串`，
         * 再按常见字段名（p92 / price92 / 92# / 92号汽油 …）取 2–20 之间的数字。
         * 这样 vvhan / oioweb / tenapi 这类格式不同的免费 API 能共用一套解析；解析不到就是 null。
         */
        internal fun parseJsonFuelPrice(
            regionName: String,
            provinceName: String,
            cityName: String?,
            body: String,
        ): FuelPriceInfo? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
            val candidates = mutableListOf<Map<String, String>>()
            fun flatten(element: JsonElement, path: String, out: MutableMap<String, String>, depth: Int) {
                if (depth > 8) return
                when (element) {
                    is JsonObject -> element.entries.forEach { (key, value) ->
                        val next = if (path.isEmpty()) key else "$path.$key"
                        when (value) {
                            is JsonPrimitive -> out[next] = value.content
                            else -> flatten(value, next, out, depth + 1)
                        }
                    }
                    is JsonArray -> element.forEachIndexed { index, value ->
                        flatten(value, "$path[$index]", out, depth + 1)
                    }
                    else -> Unit
                }
            }

            fun collect(element: JsonElement, depth: Int) {
                if (depth > 6) return
                when (element) {
                    is JsonObject -> {
                        val flat = mutableMapOf<String, String>()
                        flatten(element, "", flat, 0)
                        val blob = flat.keys.joinToString(" ") + " " + flat.values.joinToString(" ")
                        if (blob.contains(provinceName) || (cityName != null && cityName.length >= 2 && blob.contains(cityName))) {
                            candidates.add(flat)
                        }
                        element.values.forEach { collect(it, depth + 1) }
                    }
                    is JsonArray -> element.forEach { collect(it, depth + 1) }
                    else -> Unit
                }
            }

            collect(root, 0)
            // 越具体的对象（字段越少）越优先：避免用根对象把「第一个省」的价格当成目标省。
            for (flat in candidates.distinct().sortedBy { it.size }) {
                val price92 = findPrice(flat, "92")
                val price95 = findPrice(flat, "95")
                val price98 = findPrice(flat, "98")
                val price0 = findPrice(flat, "0")
                if (price92 == null && price95 == null && price98 == null && price0 == null) continue
                return FuelPriceInfo(
                    regionName = regionName,
                    price92 = price92,
                    price95 = price95,
                    price98 = price98,
                    price0 = price0,
                    nextAdjustmentText = findText(flat, listOf("nextadjust", "next_adjust", "tiaozheng", "adjust")),
                    trend = findTrend(flat),
                )
            }
            return null
        }

        private fun findPrice(flat: Map<String, String>, grade: String): Double? {
            val gradePattern = Regex("(^|[^0-9])${Regex.escape(grade)}(#|号)?($|[^0-9])")
            val prefer = Regex(
                "(?i)(p|price|q|gas|youjia|oil)?_?${Regex.escape(grade)}(#|号)?$",
            )
            // 先按「键名末尾就是该标号」精确匹配，避免 92 命中 192、95 命中 1995 之类。
            flat.entries
                .firstOrNull { (key, value) ->
                    prefer.containsMatchIn(key) && value.toDoubleOrNull()?.let { it in 2.0..20.0 } == true
                }
                ?.let { return it.value.toDoubleOrNull() }
            return flat.entries.firstOrNull { (key, value) ->
                gradePattern.containsMatchIn(key) && value.toDoubleOrNull()?.let { it in 2.0..20.0 } == true
            }?.value?.toDoubleOrNull()
        }

        private fun findText(flat: Map<String, String>, keywords: List<String>): String? =
            flat.entries.firstOrNull { (key, value) ->
                keywords.any { key.lowercase().contains(it) } && value.length in 2..40
            }?.value

        private fun findTrend(flat: Map<String, String>): String? {
            val values = flat.values
            return when {
                values.any { TREND_UP_RE.containsMatchIn(it) } -> "上调"
                values.any { TREND_DOWN_RE.containsMatchIn(it) } -> "下调"
                values.any { it.contains("搁浅") } -> "搁浅"
                else -> null
            }
        }
    }
}
