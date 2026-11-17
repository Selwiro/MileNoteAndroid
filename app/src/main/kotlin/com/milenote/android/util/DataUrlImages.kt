package com.milenote.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.milenote.android.domain.backup.ImageGuard

/**
 * 车辆图片既可能是 base64 dataURL（与 Web 备份兼容），也可能是 http(s) URL。
 * 这里只负责 dataURL 的解码与缓存；URL 由 Coil 加载。
 */
object DataUrlImages {

    /** 首页缩略图的解码目标尺寸（最长边）。 */
    private const val TARGET_LONG_SIDE = 720

    private val cache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun isDataUrl(value: String?): Boolean =
        value != null && value.startsWith("data:image/") && value.contains(";base64,")

    /** 解码为 Compose ImageBitmap；失败返回 null（调用方显示默认插画）。 */
    fun decode(value: String?): ImageBitmap? {
        if (!isDataUrl(value)) return null
        // 1.0 审查 #25：字符数先看一眼，避免为一张超大 dataURL 反复算 key。
        if (value!!.length > ImageGuard.MAX_DATA_URL_CHARS * 2) return null
        val key = cacheKey(value)
        cache.get(key)?.let { return it.asImageBitmap() }
        return try {
            val base64 = value.substringAfter(";base64,")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // 尺寸炸弹：小体积的纯色 PNG 也能解出上亿像素。宁可回落默认插画，也不在主线程 OOM。
            if (bounds.outWidth > ImageGuard.MAX_DIMENSION || bounds.outHeight > ImageGuard.MAX_DIMENSION) return null
            if (bounds.outWidth.toLong() * bounds.outHeight > ImageGuard.MAX_PIXELS) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_LONG_SIDE)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            cache.put(key, bitmap)
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 采样倍率按**最长边**算（1.0 审查 #25）。
     *
     * 以前按最短边：100×20000 这种窄长图最短边很小 → 倍率 1 → 按 200 万像素原尺寸解码；
     * 首页是主线程调用，直接卡住甚至 OOM。按最长边算才是「缩到目标显示尺寸」的语义。
     */
    internal fun sampleSize(width: Int, height: Int, target: Int = TARGET_LONG_SIDE): Int {
        val longest = maxOf(width, height)
        if (longest <= target) return 1
        return maxOf(1, longest / target)
    }

    /**
     * 缓存键必须区分两张**不同**的图片（1.0 审查 #26）。
     *
     * 以前是「前 96 字符 + 总长度」：两张同尺寸 PNG 的 base64 前缀相同、长度相同，
     * 就会拿到同一个键，A 车的图片显示成 B 车的。
     */
    internal fun cacheKey(value: String): String =
        "${value.length}:${value.hashCode()}:${value.takeLast(64)}"
}
