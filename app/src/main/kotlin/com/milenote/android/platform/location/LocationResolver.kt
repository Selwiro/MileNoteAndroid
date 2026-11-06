package com.milenote.android.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.milenote.android.data.fuelprice.FuelRegion
import com.milenote.android.data.fuelprice.FuelRegions
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 定位结果：省份 + 可选的地级市（用于市级别油价页面）。 */
data class ResolvedFuelLocation(
    val province: FuelRegion,
    val cityName: String?,
)

/** 定位 → 省市。无权限 / 无定位 / 反查失败时返回 null。 */
object LocationResolver {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun resolve(context: Context): ResolvedFuelLocation? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val location = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        } ?: return@withContext null

        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.CHINA)
        val addresses = runCatching {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
        }.getOrNull().orEmpty()
        val address = addresses.firstOrNull() ?: return@withContext null
        val province = FuelRegions.match(address.adminArea, address.subAdminArea, address.locality)
            ?: return@withContext null
        // 优先用地级行政区（subAdminArea，如“红河哈尼族彝族自治州”）；
        // 否则用 locality（如县级市“蒙自市”会落到州页面）。
        val cityCandidate = address.subAdminArea?.takeIf { it.isNotBlank() }
            ?: address.locality?.takeIf { it.isNotBlank() }
        // 直辖市 city == province，不再走市级子页。
        val cityName = cityCandidate?.takeIf {
            province.slug !in FuelRegions.municipalities && it != province.name
        }
        ResolvedFuelLocation(province = province, cityName = cityName)
    }
}
