package com.milenote.android.domain.backup

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车辆图片尺寸闸门（1.0 审查 #25）。
 *
 * 重点不是「能不能解码」，而是「不用解码整张图也能判断它是不是图片炸弹」。
 */
class ImageGuardTest {

    /** 生成一张真实结构的最小 PNG 头（只有 IHDR，足够读宽高）。 */
    private fun pngDataUrl(width: Int, height: Int): String {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        fun putInt(value: Int, offset: Int) {
            ihdr[offset] = (value ushr 24).toByte()
            ihdr[offset + 1] = (value ushr 16).toByte()
            ihdr[offset + 2] = (value ushr 8).toByte()
            ihdr[offset + 3] = value.toByte()
        }
        putInt(width, 0)
        putInt(height, 4)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // RGBA
        output.write(byteArrayOf(0, 0, 0, 13))
        output.write("IHDR".toByteArray())
        output.write(ihdr)
        output.write(byteArrayOf(0, 0, 0, 0))
        val base64 = Base64.getEncoder().encodeToString(output.toByteArray())
        return "data:image/png;base64,$base64"
    }

    @Test
    fun `reads dimensions without decoding pixels`() {
        val dimensions = ImageGuard.dimensions(pngDataUrl(1080, 1440))

        assertEquals(1080 to 1440, dimensions)
    }

    @Test
    fun `accepts a normal portrait image`() {
        assertNull(ImageGuard.problem(pngDataUrl(1080, 1440)))
    }

    @Test
    fun `rejects a tiny file with a huge canvas`() {
        // 纯色大图压缩后只有几 KB，但解出来是千万像素。
        val problem = ImageGuard.problem(pngDataUrl(20_000, 20_000))

        assertNotNull(problem)
        assertTrue(problem!!.contains("图片尺寸过大"))
    }

    @Test
    fun `rejects an extreme aspect ratio`() {
        val problem = ImageGuard.problem(pngDataUrl(100, 20_000))

        assertNotNull(problem)
    }

    @Test
    fun `leaves http urls alone`() {
        assertNull(ImageGuard.problem("https://example.com/car.png"))
        assertNull(ImageGuard.problem(null))
        assertNull(ImageGuard.problem(""))
    }

    @Test
    fun `backup validation drops an oversized image instead of rejecting the ledger`() {
        // 数据优先：账本照常进得来，超尺寸图片被丢掉，首页回落默认插画。
        val ledger = GarageOps.normalizeGarage(null).cars.first().let { car ->
            car.copy(vehicle = car.vehicle.copy(image = pngDataUrl(20_000, 20_000)))
        }

        val sanitized = GarageOps.validateLedger(ledger)

        assertNull(sanitized.vehicle.image)
    }

    @Test
    fun `backup validation keeps a normal image`() {
        val image = pngDataUrl(1080, 1440)
        val ledger = GarageOps.normalizeGarage(null).cars.first().let { car ->
            car.copy(vehicle = car.vehicle.copy(image = image))
        }

        assertEquals(image, GarageOps.validateLedger(ledger).vehicle.image)
    }
}
