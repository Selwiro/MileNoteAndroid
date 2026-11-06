@file:Suppress("DEPRECATION")

package com.milenote.android.ui.screens.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 车辆图片本地压缩（设计规范 §6.7）：
 *
 * Photo Picker 选中的图片 → 等比缩放到 **≤ 1000 × 700** → `WEBP(82)` → base64 dataURL。
 *
 * 与 Web 版 `compressVehicleImage` 的语义一致：
 * - 图片不出设备、不上传，直接写进账本 JSON（`Vehicle.image` 为 `data:image/webp;base64,...`）；
 * - 限制 dataURL 长度 ≤ 1.5M 字符（约等于 Web 的 10 MB 原图 / 压缩后体积上限）；
 * - 失败一律抛出带中文文案的异常，由页面用 [com.milenote.android.ui.components.MessageBar] 提示。
 */
internal object VehicleImageCodec {

    /** 目标最大宽（px）。 */
    const val MaxWidth = 1000

    /** 目标最大高（px）。 */
    const val MaxHeight = 700

    /** WebP 压缩质量（与 Web 图片压缩的 0.82 对齐）。 */
    const val Quality = 82

    /** dataURL 长度上限（字符），超过视为图片过大。 */
    const val MaxDataUrlLength = 1_500_000

    /** 解码前的像素上限，防止超大图占用过多内存。 */
    private const val MaxPixels = 80_000_000L

    /**
     * 读取 [uri] 指向的图片并编码为 WebP dataURL。
     *
     * @throws IllegalArgumentException 图片无法读取 / 格式不支持 / 压缩后仍超过上限。
     */
    fun toDataUrl(resolver: ContentResolver, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 时 decodeStream 按设计返回 null，不能拿返回值判断读取失败。
        val stream = resolver.openInputStream(uri) ?: throw IllegalArgumentException("无法读取所选图片")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IllegalArgumentException("不支持该图片格式，请选择 PNG / JPG / WebP")
        }
        if (sourceWidth.toLong() * sourceHeight > MaxPixels) {
            throw IllegalArgumentException("图片像素过大，请换一张尺寸更小的图片")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sourceWidth, sourceHeight)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("图片解码失败，请换一张试试")
        val scaled = scaleWithin(decoded)
        if (scaled !== decoded) decoded.recycle()

        val bytes = ByteArrayOutputStream().use { output ->
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            if (!scaled.compress(format, Quality, output)) {
                scaled.recycle()
                throw IllegalStateException("图片压缩失败，请换一张图片")
            }
            output.toByteArray()
        }
        scaled.recycle()

        val dataUrl = "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (dataUrl.length > MaxDataUrlLength) {
            throw IllegalArgumentException("图片过大，请换一张分辨率更低的图片")
        }
        return dataUrl
    }

    /** 按 2 的幂降采样，保证解码后的位图不超过目标的 2 倍。 */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MaxWidth && height / (sample * 2) >= MaxHeight) {
            sample *= 2
        }
        return sample
    }

    /** 等比缩放到 [MaxWidth] × [MaxHeight] 内；本来就够小则原样返回。 */
    private fun scaleWithin(bitmap: Bitmap): Bitmap {
        val scale = min(
            1f,
            min(MaxWidth.toFloat() / bitmap.width, MaxHeight.toFloat() / bitmap.height),
        )
        if (scale >= 1f) return bitmap
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
