package com.milenote.android.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val location = lastKnownLocation(context, manager) ?: return@withContext null

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

    /**
     * 取最近一次定位读数。
     *
     * 1.0 审查 #27：权限检查必须和调用点在**同一个函数**里，Lint 的 `MissingPermission`
     * 才认得出（以前检查在 `hasPermission()` 辅助函数里，检查器看不到 → `lintRelease` 报错）。
     * 另外每个 provider 的读取都各自兜住 `SecurityException`：运行中撤销定位授权不能崩。
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context, manager: LocationManager): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
    }
}
