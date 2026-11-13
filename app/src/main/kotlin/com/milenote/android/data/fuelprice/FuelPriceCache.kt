package com.milenote.android.data.fuelprice

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次成功抓取的快照（含抓取时间），持久化到本机。
 *
 * [version] 是缓存结构/数据源版本：更换油价数据源或改变字段口径时 +1，
 * 旧版本的缓存会被当作不存在（升级后立即重新抓取，避免展示旧源的过期文案/方向）。
 */
@Serializable
data class CachedFuelPrice(
    val info: FuelPriceInfo,
    val fetchedAtMillis: Long,
    val version: Int = 0, // 0 = 旧版本缓存（无版本字段），load 时会被丢弃
)

/**
 * 上次成功获取的油价缓存。
 *
 * 为什么需要：油价接口不稳定（站点限流、网络差都会失败）。一次成功后把结果落盘，
 * 之后**先显示缓存、且同一天内不再自动请求**，失败也不会把价格清空——
 * 直到下一次自动刷新周期（跨天）或用户手动刷新。
 *
 * 存在独立的 SharedPreferences（与「记住的定位」同一个文件），不写日志、不上传。
 */
class FuelPriceCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(location: FuelLocation): CachedFuelPrice? {
        val raw = prefs.getString(key(location), null) ?: return null
        return decode(raw)
    }

    fun save(location: FuelLocation, info: FuelPriceInfo, fetchedAtMillis: Long) {
        runCatching {
            prefs.edit().putString(key(location), encode(CachedFuelPrice(info, fetchedAtMillis, CACHE_VERSION))).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "milenote-fuel-price-cache"

        /** 数据源/文案口径版本：更换油价数据源或字段口径时 +1（旧缓存会被丢弃并重抓）。 */
        const val CACHE_VERSION = 4

        private val json = Json { ignoreUnknownKeys = true }

        /** 同一地区（省 + 市）共用一个缓存键。 */
        fun key(location: FuelLocation): String =
            "price_${location.province.slug}_${location.cityName.orEmpty().hashCode()}"

        /**
         * 是否到了自动刷新周期：按上海时区**跨天**才算过期（同一天内一直显示缓存）。
         * 手动刷新不受此限制。
         */
        fun isStale(
            fetchedAtMillis: Long,
            nowMillis: Long,
            zone: ZoneId = ZoneId.of("Asia/Shanghai"),
        ): Boolean {
            if (fetchedAtMillis <= 0L) return true
            val fetchedDate = Instant.ofEpochMilli(fetchedAtMillis).atZone(zone).toLocalDate()
            val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            return fetchedDate != nowDate
        }

        internal fun encode(cached: CachedFuelPrice): String = json.encodeToString(CachedFuelPrice.serializer(), cached)

        internal fun decode(raw: String): CachedFuelPrice? =
            runCatching { json.decodeFromString(CachedFuelPrice.serializer(), raw) }.getOrNull()
                ?.takeIf { it.version == CACHE_VERSION }
    }
}
