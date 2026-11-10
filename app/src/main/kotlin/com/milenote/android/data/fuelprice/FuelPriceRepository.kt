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

/** 32 个省市：稳定 slug（用作缓存键）+ 中文名（匹配数据源里的省名）。 */
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
 * 1. `oil-price-json`：每日更新的 31 省零售价 + 当前调价窗口方向（东方财富零售价 + 原油模型预测，
 *    上游 GitHub Actions 每天发布静态 JSON，经 jsDelivr 读取；全部 HTTPS）；
 * 2. `oilpulse-json`：另一份每日快照（31 省价格，无调价方向，作价格兜底）；
 * 3. `vvhan-json` / `oioweb-json` / `tenapi-json`：免费 HTTPS 公共 API（格式各异，用通用 JSON 解析）；
 * 4. 参考价由 [referenceInfo] 提供（只有无本地缓存且全部失败时才会用到）。
 *
 * 不再抓 qiyoujiage.com：该站只有 HTTP（release 默认禁明文），且调价方向曾明显滞后/错误。
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

    /**
     * 每日聚合数据集（fanzbus/oil-price）：省级零售价 + **非统一价省的城市级价格** + 独立的调价方向预测。
     *
     * - `data/prices.json`：31 省 92/95/98/0# + 下次调价标签；
     * - `data/cities.json`：云南/陕西/四川/辽宁/新疆/青海/西藏/贵州/内蒙古/黑龙江 10 个非统一价省的城市级价格
     *   （124 城，城市名与本站 [FuelRegions.shortCityName] 对齐）；
     * - `data/forecast.json`：独立预测方向（Yahoo Brent/WTI + 汇率模型，每天更新）。
     * 三个文件都是公开静态 JSON（jsDelivr 读取，不带任何参数）。
     */
    private class FanzbusJsonSource(client: OkHttpClient) : HttpSource(client) {
        override val id: String = "fanzbus-json"

        override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
            val prices = fetchJson(PRICES_URL) ?: return@withContext null
            val cities = runCatching { fetchJson(CITIES_URL) }.getOrNull()
            val forecast = runCatching { fetchJson(FORECAST_URL) }.getOrNull()
            parseFanzbusJson(
                regionName = DataLabels.region(location.displayName),
                provinceName = location.province.name,
                cityName = location.cityName?.let(FuelRegions::shortCityName),
                pricesBody = prices,
                citiesBody = cities,
                forecastBody = forecast,
            )
        }

        private fun fetchJson(url: String): String? =
            runCatching { getText(url) }.getOrNull()
    }

    /** 静态 JSON 数据集：同一份数据配多个 CDN URL（jsDelivr 优先，GitHub raw 兜底）。 */
    private abstract class StaticJsonSource(
        client: OkHttpClient,
        override val id: String,
        private val urls: List<String>,
    ) : HttpSource(client) {
        override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
            val regionName = DataLabels.region(location.displayName)
            val body = urls.firstNotNullOfOrNull { url ->
                runCatching { getText(url) }.getOrNull()
            } ?: return@withContext null
            parse(location.province.name, regionName, body)
        }

        protected abstract fun parse(provinceName: String, regionName: String, body: String): FuelPriceInfo?
    }

    /**
     * 每日聚合数据（东方财富国内零售价 + 原油模型预测方向），31 省 92/95/89/0#。
     * 数据每天由上游 GitHub Actions 更新；`next.direction` 是当前调价窗口的方向（如「预计下调」）。
     */
    private class OilPriceJsonSource(client: OkHttpClient) : StaticJsonSource(
        client,
        id = "oil-price-json",
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/wangzhaolong616-byte/oil-price@main/docs/prices.json",
            "https://raw.githubusercontent.com/wangzhaolong616-byte/oil-price/main/docs/prices.json",
        ),
    ) {
        override fun parse(provinceName: String, regionName: String, body: String): FuelPriceInfo? =
            parseOilPriceJson(regionName, provinceName, body)
    }

    /** 另一份每日快照（31 省，字段 t0/t89/t92/t95/t98）；只有价格，没有调价方向。 */
    private class OilPulseJsonSource(client: OkHttpClient) : StaticJsonSource(
        client,
        id = "oilpulse-json",
        urls = listOf(
            "https://cdn.jsdelivr.net/gh/ZhangHowie/oilpulse-cn@main/data/latest.json",
            "https://raw.githubusercontent.com/ZhangHowie/oilpulse-cn/main/data/latest.json",
        ),
    ) {
        override fun parse(provinceName: String, regionName: String, body: String): FuelPriceInfo? =
            parseOilPulseJson(regionName, provinceName, body)
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

        /** 默认回退顺序：两个每日更新的静态 JSON 数据集 → 三个免费 HTTPS JSON API。 */
        fun defaultSources(): List<FuelPriceSource> {
            val client = defaultClient()
            return listOf(
                FanzbusJsonSource(client),
                OilPriceJsonSource(client),
                OilPulseJsonSource(client),
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

        private val TREND_UP_RE = Regex("上调|上涨|涨幅")
        private val TREND_DOWN_RE = Regex("下调|下跌|跌幅")

        private const val FANZBUS_BASE = "https://cdn.jsdelivr.net/gh/fanzbus/oil-price@main/data"
        private const val PRICES_URL = "$FANZBUS_BASE/prices.json"
        private const val CITIES_URL = "$FANZBUS_BASE/cities.json"
        private const val FORECAST_URL = "$FANZBUS_BASE/forecast.json"

        /**
         * 解析 fanzbus 的三份 JSON：
         * - 省级价格从 `prices.provinces` 里按省名取；城市级从 `cities.provinces[省]` 里按城市名取，
         *   城市页缺失/异常时退回省级价（非统一价省才拆城市，其余省本身统一价）；
         * - 调价方向从 `forecast.direction` 取（独立预测，比价格站点自带的「预计上调」更及时）。
         */
        internal fun parseFanzbusJson(
            regionName: String,
            provinceName: String,
            cityName: String?,
            pricesBody: String,
            citiesBody: String?,
            forecastBody: String?,
        ): FuelPriceInfo? {
            val pricesRoot = runCatching { Json.parseToJsonElement(pricesBody) as? JsonObject }.getOrNull()
                ?: return null
            val provinceRow = (pricesRoot["provinces"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["province"] as? JsonPrimitive)?.content == provinceName }
                ?: return null

            fun number(obj: JsonObject?, key: String): Double? =
                (obj?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it in 2.0..20.0 }

            var price92 = number(provinceRow, "p92")
            var price95 = number(provinceRow, "p95")
            var price98 = number(provinceRow, "p98")
            var price0 = number(provinceRow, "d0")

            // 城市级：只对非统一价省生效，匹配不到城市就继续用省级价。
            if (!cityName.isNullOrBlank() && citiesBody != null) {
                val cityRoot = runCatching { Json.parseToJsonElement(citiesBody) as? JsonObject }.getOrNull()
                val cityList = (cityRoot?.get("provinces") as? JsonObject)
                    ?.let { (it[provinceName] as? JsonArray) }
                val cityRow = cityList
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { row ->
                        (row["city"] as? JsonPrimitive)?.content == cityName &&
                            (row["status"] as? JsonPrimitive)?.content != "pending"
                    }
                if (cityRow != null) {
                    price92 = number(cityRow, "p92") ?: price92
                    price95 = number(cityRow, "p95") ?: price95
                    price98 = number(cityRow, "p98") ?: price98
                    price0 = number(cityRow, "d0") ?: price0
                }
            }

            if (price92 == null && price95 == null && price98 == null && price0 == null) return null

            val forecastRoot = runCatching { Json.parseToJsonElement(forecastBody.orEmpty()) as? JsonObject }.getOrNull()
            val direction = (forecastRoot?.get("direction") as? JsonPrimitive)?.content
            val trend = when {
                direction == null -> null
                direction.contains("下") -> "下调"
                direction.contains("上") -> "上调"
                else -> "搁浅"
            }
            val adjustmentLabel = normalizeAdjustmentLabel(
                (pricesRoot["next_adjustment_label"] as? JsonPrimitive)?.content,
            )
            return FuelPriceInfo(
                regionName = regionName,
                price92 = price92,
                price95 = price95,
                price98 = price98,
                price0 = price0,
                nextAdjustmentText = adjustmentLabel,
                trend = trend,
                forecast = (forecastRoot?.get("note") as? JsonPrimitive)?.content,
            )
        }

        /** "11月12日24时" → 「11 月 12 日 24 时」；其它格式原样返回（去掉多余空格）。 */
        internal fun normalizeAdjustmentLabel(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*24\\s*时").find(raw)
            if (match != null) {
                val month = match.groupValues[1]
                val day = match.groupValues[2]
                return "$month 月 $day 日 24 时"
            }
            return raw.replace(" ", "")
        }

        /** 解析「全国油价趋势看板」prices.json：provinces_data[省][标号] 是按 dates 对齐的价格数组。 */
        internal fun parseOilPriceJson(regionName: String, provinceName: String, body: String): FuelPriceInfo? {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val data = root["provinces_data"] as? JsonObject ?: return null
            val entry = data[provinceName] as? JsonObject ?: return null

            fun latest(grade: String): Double? =
                (entry[grade] as? JsonArray)
                    ?.mapNotNull { element -> (element as? JsonPrimitive)?.content?.toDoubleOrNull() }
                    ?.lastOrNull()
                    ?.takeIf { it in 2.0..20.0 }

            val price92 = latest("92")
            val price95 = latest("95")
            val price98 = latest("98")
            val price0 = latest("0")
            if (price92 == null && price95 == null && price98 == null && price0 == null) return null

            val next = root["next"] as? JsonObject
            val direction = next?.get("direction")?.let { (it as? JsonPrimitive)?.content }
            val trend = when {
                direction == null -> null
                direction.contains("下") -> "下调"
                direction.contains("上") -> "上调"
                else -> "搁浅"
            }
            val adjustment = next?.get("date")?.let { (it as? JsonPrimitive)?.content }?.let(::isoAdjustmentLabel)
            return FuelPriceInfo(
                regionName = regionName,
                price92 = price92,
                price95 = price95,
                price98 = price98,
                price0 = price0,
                nextAdjustmentText = adjustment,
                trend = trend,
                forecast = next?.get("reason")?.let { (it as? JsonPrimitive)?.content },
            )
        }

        /** 解析 oilpulse-cn 的 latest.json：provinces[省] = { t0, t89, t92, t95, t98 }。 */
        internal fun parseOilPulseJson(regionName: String, provinceName: String, body: String): FuelPriceInfo? {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val provinces = root["provinces"] as? JsonObject ?: return null
            val entry = provinces[provinceName] as? JsonObject ?: return null

            fun price(key: String): Double? =
                (entry[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it in 2.0..20.0 }

            val info = FuelPriceInfo(
                regionName = regionName,
                price92 = price("t92"),
                price95 = price("t95"),
                price98 = price("t98"),
                price0 = price("t0"),
            )
            return info.takeIf { it.hasPrice }
        }

        /**
         * "2026-11-13" → 「11 月 13 日」。
         *
         * 上游 `next.date` 是**生效日期**（13 日 0 时生效 = 12 日 24 时），所以这里不再追加「24 时」，
         * 避免显示成「13 日 24 时」而晚一天。离今天太远/非法返回 null。
         */
        private fun isoAdjustmentLabel(isoDate: String): String? {
            val target = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return null
            val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            if (target.isBefore(today.minusDays(1)) || target.isAfter(today.plusDays(31))) return null
            return "${target.monthValue} 月 ${target.dayOfMonth} 日"
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
