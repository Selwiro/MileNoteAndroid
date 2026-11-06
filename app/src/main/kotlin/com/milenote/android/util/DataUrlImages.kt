package com.milenote.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 车辆图片既可能是 base64 dataURL（与 Web 备份兼容），也可能是 http(s) URL。
 * 这里只负责 dataURL 的解码与缓存；URL 由 Coil 加载。
 */
object DataUrlImages {

    private val cache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun isDataUrl(value: String?): Boolean =
        value != null && value.startsWith("data:image/") && value.contains(";base64,")

    /** 解码为 Compose ImageBitmap；失败返回 null（调用方显示默认插画）。 */
    fun decode(value: String?): ImageBitmap? {
        if (!isDataUrl(value)) return null
        val key = value!!.take(96) + ":" + value.length
        cache.get(key)?.let { return it.asImageBitmap() }
        return try {
            val base64 = value.substringAfter(";base64,")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 720)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            cache.put(key, bitmap)
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            null
        }
    }
}
