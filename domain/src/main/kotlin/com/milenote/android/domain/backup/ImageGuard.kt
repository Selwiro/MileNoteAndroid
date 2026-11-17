package com.milenote.android.domain.backup

import java.util.Base64

/**
 * 车辆图片的尺寸闸门（1.0 审查 #25）。
 *
 * 备份校验以前只看 data URL 的字符数，而 PNG 是压缩格式：一张几十 KB 的纯色
 * 20000×20000 图片解出来是 1.6 GB 像素。App 内解码按「最短边」采样，窄长图更是按原像素解。
 *
 * 这里直接从 PNG / JPEG 头部读宽高（不需要解码整张图），给出双限制：
 * 单边 ≤ [MAX_DIMENSION]，总像素 ≤ [MAX_PIXELS]。
 */
object ImageGuard {

    /** 单边最大像素。 */
    const val MAX_DIMENSION: Int = 4096

    /** 总像素上限（约 4 MP，足够 1080p 与 2K 截图）。 */
    const val MAX_PIXELS: Long = 4_000_000L

    /** data URL 的字符数上限（与备份校验口径一致）。 */
    const val MAX_DATA_URL_CHARS: Int = 1_500_000

    /** 从 data URL 读宽高；不是 PNG / JPEG 或头部不可解析时返回 null（无从判断就不拦）。 */
    fun dimensions(dataUrl: String): Pair<Int, Int>? = runCatching {
        val base64 = dataUrl.substringAfter(";base64,", "")
        if (base64.isEmpty()) return null
        val bytes = Base64.getMimeDecoder().decode(base64)
        pngDimensions(bytes) ?: jpegDimensions(bytes)
    }.getOrNull()

    /**
     * 这张图是否超过尺寸预算。
     *
     * @return 超限时返回**可展示的原因**；正常或无法判断时返回 null。
     */
    fun problem(image: String?): String? {
        if (image.isNullOrBlank()) return null
        if (!image.startsWith("data:image/") || !image.contains(";base64,")) return null
        if (image.length > MAX_DATA_URL_CHARS) {
            return tr("图片过大，请重新上传压缩后的图片", "Image too large — upload a compressed image")
        }
        val (width, height) = dimensions(image) ?: return null
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
            return tr(
                "图片尺寸过大（%d×%d，最大 %d×%d 或 %.0f 万像素），请压缩后再上传",
                "The image is too large (%d×%d; the limit is %d×%d or %.0f megapixels) — compress it first",
                width,
                height,
                MAX_DIMENSION,
                MAX_DIMENSION,
                MAX_PIXELS / 1_000_000.0,
            )
        }
        return null
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        for (i in signature.indices) {
            if (bytes[i] != signature[i]) return null
        }
        // IHDR 的 width 在偏移 16..19，height 在 20..23（大端）。
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        return width to height
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var index = 2
        while (index + 9 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            // SOF0..SOF15（除去 DHT/JPG/DAC）携带尺寸。
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = readShort(bytes, index + 5)
                val width = readShort(bytes, index + 7)
                return width to height
            }
            val length = readShort(bytes, index + 2)
            if (length <= 0) return null
            index += 2 + length
        }
        return null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun tr(zh: String, en: String, vararg args: Any?): String =
        com.milenote.android.domain.i18n.tr(zh, en, *args)
}
