package com.milenote.android.data.fuelprice

import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.tr
import java.time.LocalDate
import java.time.OffsetDateTime
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
    /**
     * 上游数据自身的日期（上海时区 `yyyy-MM-dd`）；null = 数据源没提供。
     *
     * 和「抓取时间」不是一回事：抓取成功不等于数据已经更新（上游可能还在发上一轮的价格）。
     */
    val dataDate: String? = null,
    /**
     * 这份数据是否还停在**上一个调价周期**。
     *
     * 判定依据来自数据源自报的元信息（调价生效时间、数据日期），不猜价格；见 [FuelPriceRepository]。
     * 界面据此提示「数据未更新」，避免把旧价当成今日价展示。
     */
    val dataStale: Boolean = false,
    /** 价格已经是**城市级**（命中了城市页，或按城市差价推算过），可以直接当作该市今日价。 */
    val cityLevel: Boolean = false,
    /**
     * 城市价是由「省价 + 城市固定差价」推算的（不是城市页直接给的值）。
     *
     * 只有省价源可用时才会发生：见 [FuelPriceRepository.withCityPrice]。
     */
    val cityEstimated: Boolean = false,
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

/**
 * 一个城市相对**省均价**的固定上浮（元/升），按标号分开存。
 *
 * 城市之间差价的来源是省内运杂费（长期固定），发改委每次调价对同一省所有城市加减同一幅度，
 * 所以这个差价可以跨调价周期复用。
 */
data class PriceSurcharge(
    val p92: Double? = null,
    val p95: Double? = null,
    val p98: Double? = null,
    val d0: Double? = null,
) {
    val isEmpty: Boolean get() = p92 == null && p95 == null && p98 == null && d0 == null
}

/**
 * 最近一次拿到的「城市价 - 省价」差价表（内存），给 [FuelPriceRepository.withCityPrice] 用。
 *
 * 只有 fanzbus 数据集带城市级价格，而它经常停更；停更时它的**差价**仍然有效，
 * 于是省价源（oil-price / oilpulse / 公共 API）拿到的新价可以按差价推回城市价。
 */
class CityPriceSnapshot {

    @Volatile
    private var surcharges: Map<String, Map<String, PriceSurcharge>> = emptyMap()

    fun store(value: Map<String, Map<String, PriceSurcharge>>) {
        if (value.isNotEmpty()) surcharges = value
    }

    fun surchargeFor(provinceName: String, cityName: String): PriceSurcharge? =
        surcharges[provinceName]?.get(cityName)
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
    sources: List<FuelPriceSource>? = null,
    private val client: OkHttpClient = defaultClient(),
    private val citySnapshot: CityPriceSnapshot = CityPriceSnapshot(),
) {
    private val sources: List<FuelPriceSource> = sources ?: defaultSources(client, citySnapshot)

    /**
     * 抓取一次：按顺序回退，返回**第一个仍处于当前调价周期**的数据源结果。
     *
     * 为什么不能只看「有没有价格」：上游数据集可能停更（例如 fanzbus 的 GitHub Action 挂了，
     * 数据停在半个月前），此时它照样能返回价格与「预计上调」，会把上一轮的价格当成今日价。
     * 所以过期数据不直接采用，继续问下一个源；**全部源都过期**时才拿第一条过期数据兜底
     * （带 [FuelPriceInfo.dataStale] 交给界面标注），全部失败返回 null（不要用参考价冒充实时价）。
     */
    suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
        var staleFallback: FuelPriceInfo? = null
        for (source in sources) {
            val info = runCatching {
                withTimeout(SOURCE_TIMEOUT_MS) { source.fetch(location) }
            }.getOrNull()
            if (info == null || !info.hasPrice) continue
            val labeled = info.copy(source = source.id)
            if (!info.dataStale) return@withContext withCityPrice(labeled, location)
            if (staleFallback == null) staleFallback = labeled
        }
        staleFallback?.let { withCityPrice(it, location) }
    }

    /**
     * 只有省价时把城市价补回来：`城市价 = 省价 + 该市固定上浮`。
     *
     * 背景（2026-11-13）：云南这类非统一价省份，城市之间差 0.15–0.28 元/升。
     * 唯一带城市级价格的数据源 fanzbus 停更后，回退源只有省均价——红河 8.67 会被显示成
     * 云南均价 8.52。差价来自 [CityPriceSnapshot]（同源、同一轮调价的快照），
     * 与省价一起相加即可还原城市价；推出来的值标 [FuelPriceInfo.cityEstimated]，界面会注明。
     */
    internal fun withCityPrice(info: FuelPriceInfo, location: FuelLocation): FuelPriceInfo {
        if (info.cityLevel) return info
        val city = location.cityName?.takeIf { it.isNotBlank() }?.let(FuelRegions::shortCityName)
            ?: return info
        val surcharge = citySnapshot.surchargeFor(location.province.name, city)
            ?.takeIf { !it.isEmpty } ?: return info
        fun add(base: Double?, delta: Double?): Double? =
            if (base == null || delta == null) base else roundPrice(base + delta)
        return info.copy(
            price92 = add(info.price92, surcharge.p92),
            price95 = add(info.price95, surcharge.p95),
            price98 = add(info.price98, surcharge.p98),
            price0 = add(info.price0, surcharge.d0),
            cityLevel = true,
            cityEstimated = true,
        )
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
    private class FanzbusJsonSource(
        client: OkHttpClient,
        private val citySnapshot: CityPriceSnapshot,
    ) : HttpSource(client) {
        override val id: String = "fanzbus-json"

        override suspend fun fetch(location: FuelLocation): FuelPriceInfo? = withContext(Dispatchers.IO) {
            val prices = fetchJson(PRICES_URL) ?: return@withContext null
            val cities = runCatching { fetchJson(CITIES_URL) }.getOrNull()
            val forecast = runCatching { fetchJson(FORECAST_URL) }.getOrNull()
            // 差价先落下来：即便这份数据本身过期（不会作为今日价返回），它的城市差价仍然可用。
            citySnapshot.store(parseCitySurcharges(prices, cities))
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

        /** 油价是发改委按上海时间 24 时调价，所有「日期」判断都统一用这个时区。 */
        internal val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

        /**
         * 数据日期比今天早**两天**以上就算停更（不是「非今天即过期」：油价只在调价日变化，
         * 上游每天更新但价格可能连续多天不变；留一天余量覆盖跨时区/延迟发布）。
         */
        internal fun isStaleByAge(dataDate: LocalDate?, today: LocalDate): Boolean =
            dataDate != null && dataDate.isBefore(today.minusDays(1))

        /**
         * 数据是否还停在上一轮调价周期。
         *
         * [nextAdjustmentDate] 是**这次调价何时生效**（fanzbus 的 `next_adjustment` 已是生效时刻）：
         * 生效日已到、而文件还标着「未滚动」（`next_adjustment_rolled=false`），说明里面还是调价前的价。
         */
        internal fun isStaleFuelData(
            dataDate: LocalDate?,
            nextAdjustmentDate: LocalDate?,
            nextAdjustmentRolled: Boolean,
            today: LocalDate,
        ): Boolean {
            if (isStaleByAge(dataDate, today)) return true
            return nextAdjustmentDate != null &&
                !nextAdjustmentRolled &&
                !nextAdjustmentDate.isAfter(today)
        }

        /** 数据源里的日期字段：`2026-11-13` 或 `2026-11-13T06:20:22+08:00` 都能取到日期。 */
        internal fun parseIsoDate(raw: String?): LocalDate? {
            if (raw.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(raw.trim()).toLocalDate() }.getOrNull()
                ?: runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()
        }

        /** 价格最小单位是 0.01 元/升，加完差价后按分四舍五入，避免 8.670000000000002。 */
        internal fun roundPrice(value: Double): Double = Math.round(value * 100.0) / 100.0

        /**
         * `cities.json` 是否已经跟上 `prices.json` 最近一次调价。
         *
         * 上游的 Action 分文件产出：省价滚进新一轮、城市页还停在上一轮时，城市价是**旧价**，
         * 既不能当今日价用，也不能拿它算差价。`last_change_at` 缺失时退回 `fetched_at`；
         * 两个日期都读不到时按「可用」处理（与旧行为一致，避免元信息缺失导致城市价整体失效）。
         */
        internal fun cityDataInCurrentCycle(pricesRoot: JsonObject, citiesRoot: JsonObject?): Boolean {
            if (citiesRoot == null) return false
            val lastChange = parseIsoDate(citiesRoot.text("last_change_at"))
                ?: parseIsoDate(pricesRoot.text("last_change_at"))
                ?: parseIsoDate(pricesRoot.text("fetched_at"))
            val cityDate = parseIsoDate(citiesRoot.text("fetched_at"))
            if (lastChange == null || cityDate == null) return true
            return !cityDate.isBefore(lastChange)
        }

        /** 城市价相对省价的差价上限：实测省内运杂费差最多 0.39（西藏阿里），超过就是数据串了。 */
        private const val MAX_CITY_SURCHARGE = 0.6

        /**
         * 从 fanzbus 的 `prices.json` + `cities.json` 算出「城市价 - 省价」的固定上浮。
         *
         * 差价可以跨调价周期复用（同一次调价对全省所有城市加减同一幅度），
         * 所以即使城市价快照已经过期，省价源拿到的新价 + 这个差价就能还原城市价。
         * 两份文件必须来自同一轮调价（见 [cityDataInCurrentCycle]），否则算出来的差价是
         * 「旧城市价 - 新省价」，会把价格推高一整轮调价幅度。
         */
        internal fun parseCitySurcharges(
            pricesBody: String?,
            citiesBody: String?,
        ): Map<String, Map<String, PriceSurcharge>> {
            val pricesRoot = runCatching { Json.parseToJsonElement(pricesBody.orEmpty()) as? JsonObject }
                .getOrNull() ?: return emptyMap()
            val citiesRoot = runCatching { Json.parseToJsonElement(citiesBody.orEmpty()) as? JsonObject }
                .getOrNull() ?: return emptyMap()
            if (!cityDataInCurrentCycle(pricesRoot, citiesRoot)) return emptyMap()

            fun number(obj: JsonObject?, key: String): Double? =
                (obj?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it in 2.0..20.0 }

            fun surcharge(city: JsonObject, base: JsonObject): PriceSurcharge {
                fun delta(key: String): Double? {
                    val provincePrice = number(base, key) ?: return null
                    val cityPrice = number(city, key) ?: return null
                    val value = roundPrice(cityPrice - provincePrice)
                    return value.takeIf { kotlin.math.abs(it) <= MAX_CITY_SURCHARGE }
                }
                return PriceSurcharge(
                    p92 = delta("p92"),
                    p95 = delta("p95"),
                    p98 = delta("p98"),
                    d0 = delta("d0"),
                )
            }

            val provinceBase = (pricesRoot["provinces"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { row ->
                    val name = (row["province"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    name to row
                }
                ?.toMap()
                .orEmpty()
            if (provinceBase.isEmpty()) return emptyMap()

            val result = mutableMapOf<String, Map<String, PriceSurcharge>>()
            (citiesRoot["provinces"] as? JsonObject)?.forEach { (provinceName, list) ->
                val base = provinceBase[provinceName] ?: return@forEach
                val rows = (list as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@forEach
                val byCity = rows
                    .filter { (it["status"] as? JsonPrimitive)?.content != "pending" }
                    .mapNotNull { row ->
                        val city = (row["city"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val value = surcharge(row, base)
                        if (value.isEmpty) null else city to value
                    }
                    .toMap()
                if (byCity.isNotEmpty()) result[provinceName] = byCity
            }
            return result
        }

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        /** 默认回退顺序：两个每日更新的静态 JSON 数据集 → 三个免费 HTTPS JSON API。 */
        fun defaultSources(
            client: OkHttpClient = defaultClient(),
            citySnapshot: CityPriceSnapshot = CityPriceSnapshot(),
        ): List<FuelPriceSource> {
            return listOf(
                FanzbusJsonSource(client, citySnapshot),
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
         *
         * 同时按文件自报的 `fetched_at` / `next_adjustment`(+`_rolled`) 判断数据是否还停在上一轮：
         * 上游停更时这里返回 [FuelPriceInfo.dataStale]，由仓库继续回退到下一个源。
         */
        internal fun parseFanzbusJson(
            regionName: String,
            provinceName: String,
            cityName: String?,
            pricesBody: String,
            citiesBody: String?,
            forecastBody: String?,
            today: LocalDate = LocalDate.now(SHANGHAI),
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
            var cityLevel = false

            // 城市级：只对非统一价省生效，匹配不到城市就继续用省级价。
            // 城市页可能落后于省价（Action 只更新了一部分文件）：跨轮调价的城市价不能当今日价用。
            if (!cityName.isNullOrBlank() && citiesBody != null) {
                val cityRoot = runCatching { Json.parseToJsonElement(citiesBody) as? JsonObject }.getOrNull()
                val cityRow = if (cityDataInCurrentCycle(pricesRoot, cityRoot)) {
                    ((cityRoot?.get("provinces") as? JsonObject)?.let { it[provinceName] as? JsonArray })
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { row ->
                            (row["city"] as? JsonPrimitive)?.content == cityName &&
                                (row["status"] as? JsonPrimitive)?.content != "pending"
                        }
                } else {
                    null
                }
                if (cityRow != null) {
                    number(cityRow, "p92")?.let { price92 = it; cityLevel = true }
                    number(cityRow, "p95")?.let { price95 = it; cityLevel = true }
                    number(cityRow, "p98")?.let { price98 = it; cityLevel = true }
                    number(cityRow, "d0")?.let { price0 = it; cityLevel = true }
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
            // 数据年龄：上游文件自己写的抓取时间（不是 App 的抓取时间）。
            val dataDate = parseIsoDate(pricesRoot.text("fetched_at"))
            val stale = isStaleFuelData(
                dataDate = dataDate,
                nextAdjustmentDate = parseIsoDate(pricesRoot.text("next_adjustment")),
                nextAdjustmentRolled = pricesRoot.text("next_adjustment_rolled")?.toBooleanStrictOrNull() == true,
                today = today,
            )
            val adjustmentLabel = if (stale) {
                null
            } else {
                normalizeAdjustmentLabel(pricesRoot.text("next_adjustment_label"), today)
            }
            return FuelPriceInfo(
                regionName = regionName,
                price92 = price92,
                price95 = price95,
                price98 = price98,
                price0 = price0,
                nextAdjustmentText = adjustmentLabel,
                trend = if (stale) null else trend,
                forecast = (forecastRoot?.get("note") as? JsonPrimitive)?.content,
                dataDate = dataDate?.toString(),
                dataStale = stale,
                cityLevel = cityLevel,
            )
        }

        /** "11月12日24时" → 「11 月 12 日 24 时」；其它格式原样返回（去掉多余空格）。 */
        internal fun normalizeAdjustmentLabel(
            raw: String?,
            today: LocalDate = LocalDate.now(SHANGHAI),
        ): String? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*24\\s*时").find(raw)
            if (match != null) {
                val month = match.groupValues[1].toIntOrNull() ?: return null
                val day = match.groupValues[2].toIntOrNull() ?: return null
                // 原文「11 月 12 日 24 时」= 13 日 0 时生效：生效时刻已过就不再是「下次调价」，
                // 继续展示会把上一轮的日期当成下一轮的（13 日还写 12 日 24 时）。
                return nextAdjustmentLabel(month, day, today)
            }
            return raw.replace(" ", "")
        }

        /** 解析「全国油价趋势看板」prices.json：provinces_data[省][标号] 是按 dates 对齐的价格数组。 */
        internal fun parseOilPriceJson(
            regionName: String,
            provinceName: String,
            body: String,
            today: LocalDate = LocalDate.now(SHANGHAI),
        ): FuelPriceInfo? {
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
            // `meta.date_to` = 这份数据更新到哪一天；`next.last_adjust` = 最近一次调价生效日。
            // 数据早于最近一次调价 → 价格还是调价前的；数据日期太旧 → 上游停更。
            val dataDate = parseIsoDate((root["meta"] as? JsonObject)?.text("date_to"))
            val lastAdjust = parseIsoDate(next?.text("last_adjust"))
            val stale = isStaleByAge(dataDate, today) ||
                (dataDate != null && lastAdjust != null && dataDate.isBefore(lastAdjust))
            val adjustment = if (stale) {
                null
            } else {
                next?.text("date")?.let { isoAdjustmentLabel(it, today) }
            }
            return FuelPriceInfo(
                regionName = regionName,
                price92 = price92,
                price95 = price95,
                price98 = price98,
                price0 = price0,
                nextAdjustmentText = adjustment,
                trend = if (stale) null else trend,
                forecast = next?.text("reason"),
                dataDate = dataDate?.toString(),
                dataStale = stale,
            )
        }

        /** 解析 oilpulse-cn 的 latest.json：provinces[省] = { t0, t89, t92, t95, t98 }。 */
        internal fun parseOilPulseJson(
            regionName: String,
            provinceName: String,
            body: String,
            today: LocalDate = LocalDate.now(SHANGHAI),
        ): FuelPriceInfo? {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val provinces = root["provinces"] as? JsonObject ?: return null
            val entry = provinces[provinceName] as? JsonObject ?: return null

            fun price(key: String): Double? =
                (entry[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it in 2.0..20.0 }

            val dataDate = parseIsoDate(root.text("updated_at"))
            val info = FuelPriceInfo(
                regionName = regionName,
                price92 = price("t92"),
                price95 = price("t95"),
                price98 = price("t98"),
                price0 = price("t0"),
                dataDate = dataDate?.toString(),
                dataStale = isStaleByAge(dataDate, today),
            )
            return info.takeIf { it.hasPrice }
        }

        /**
         * "2026-11-13" → 「11 月 13 日」。
         *
         * 上游 `next.date` 是**生效日期**（13 日 0 时生效 = 12 日 24 时），所以这里不再追加「24 时」，
         * 避免显示成「13 日 24 时」而晚一天。离今天太远/非法返回 null。
         */
        private fun isoAdjustmentLabel(isoDate: String, today: LocalDate): String? {
            val target = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return null
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
