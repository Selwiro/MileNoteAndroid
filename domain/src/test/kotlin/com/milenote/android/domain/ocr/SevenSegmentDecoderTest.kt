package com.milenote.android.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 七段数码管解码器单测。
 *
 * 测试图全部用纯 Kotlin 现场渲染（矩形段 + 段间隙 + 字距可调），不依赖 Android Bitmap /
 * 任何图片资源，保证 `./dev-gradle.sh :domain:test` 在任意机器上可跑。
 */
class SevenSegmentDecoderTest {

    private val digits = mapOf(
        '0' to "abcdef",
        '1' to "bc",
        '2' to "abdeg",
        '3' to "abcdg",
        '4' to "bcfg",
        '5' to "acdfg",
        '6' to "acdefg",
        '7' to "abc",
        '8' to "abcdefg",
        '9' to "abcdfg",
    )

    // ---------------------------------------------------------------- 渲染器 ----

    /**
     * 把数字串画成一行二值掩膜。
     *
     * 段用矩形（真实 LED/LCD 数码管就是矩形段），[segmentGap] 是段与段之间的小缝隙，
     * [charGap] 是字距；[charGap] 调小后小数点会同时贴住左右两个数字（测试合并段切分）。
     */
    private class SevenSegmentRenderer(
        private val digits: Map<Char, String>,
        private val cellWidth: Int = 78,
        private val cellHeight: Int = 132,
        private val thickness: Int = 14,
        private val segmentGap: Int = 1,
        private val charGap: Int = 26,
        private val margin: Int = 16,
    ) {
        private val rowHeight = cellHeight + margin * 2

        private fun advance(text: String): Int {
            val count = text.count { it != '.' }
            return if (count == 0) 0 else count * (cellWidth + charGap) - charGap
        }

        fun renderRow(text: String): BinaryRow {
            val width = margin * 2 + maxOf(advance(text), 1)
            val mask = IntArray(width * rowHeight)
            drawRow(mask, width, text)
            return BinaryRow(mask, width, rowHeight)
        }

        fun renderRows(texts: List<String>): List<BinaryRow> {
            val width = margin * 2 + maxOf(texts.maxOf { advance(it) }, 1)
            return texts.map { text ->
                val mask = IntArray(width * rowHeight)
                drawRow(mask, width, text)
                BinaryRow(mask, width, rowHeight)
            }
        }

        private fun drawRow(mask: IntArray, width: Int, text: String) {
            var x = margin
            val y = margin
            for (ch in text) {
                if (ch == '.') {
                    fillCircle(
                        mask = mask,
                        stride = width,
                        centerX = x - charGap * 0.62f,
                        centerY = y + cellHeight - thickness * 1.6f,
                        radius = thickness * 0.62f,
                    )
                } else {
                    drawDigit(mask, width, x, y, ch)
                    x += cellWidth + charGap
                }
            }
        }

        private fun drawDigit(mask: IntArray, stride: Int, x: Int, y: Int, ch: Char) {
            val lit = digits[ch] ?: return
            val t = thickness
            val g = segmentGap
            val w = cellWidth
            val h = cellHeight
            val mid = y + h / 2
            if ('a' in lit) fillRect(mask, stride, x + t + g, y + g, x + w - t - g, y + t - g)
            if ('b' in lit) fillRect(mask, stride, x + w - t + g, y + t + g, x + w - g, mid - g)
            if ('c' in lit) fillRect(mask, stride, x + w - t + g, mid + g, x + w - g, y + h - t - g)
            if ('d' in lit) fillRect(mask, stride, x + t + g, y + h - t + g, x + w - t - g, y + h - g)
            if ('e' in lit) fillRect(mask, stride, x + g, mid + g, x + t - g, y + h - t - g)
            if ('f' in lit) fillRect(mask, stride, x + g, y + t + g, x + t - g, mid - g)
            if ('g' in lit) fillRect(mask, stride, x + t + g, mid - t / 2 + g, x + w - t - g, mid - t / 2 + t - g)
        }

        private fun fillRect(mask: IntArray, stride: Int, left: Int, top: Int, right: Int, bottom: Int) {
            val rows = mask.size / stride
            val l = left.coerceIn(0, stride)
            val r = right.coerceIn(0, stride)
            val t = top.coerceIn(0, rows)
            val b = bottom.coerceIn(0, rows)
            if (r <= l || b <= t) return
            for (yy in t until b) {
                val base = yy * stride
                for (xx in l until r) mask[base + xx] = 1
            }
        }

        private fun fillCircle(mask: IntArray, stride: Int, centerX: Float, centerY: Float, radius: Float) {
            val rows = mask.size / stride
            val left = (centerX - radius).toInt().coerceIn(0, stride - 1)
            val right = (centerX + radius).toInt().coerceIn(0, stride - 1)
            val top = (centerY - radius).toInt().coerceIn(0, rows - 1)
            val bottom = (centerY + radius).toInt().coerceIn(0, rows - 1)
            val r2 = radius * radius
            for (yy in top..bottom) {
                for (xx in left..right) {
                    val dx = xx - centerX
                    val dy = yy - centerY
                    if (dx * dx + dy * dy <= r2) mask[yy * stride + xx] = 1
                }
            }
        }
    }

    private val display = SevenSegmentRenderer(digits)

    private fun decodeRow(text: String, renderer: SevenSegmentRenderer = display): String? {
        val row = renderer.renderRow(text)
        return SevenSegmentDecoder.decodeLine(row.mask, row.width, row.height)
    }

    // ------------------------------------------------------------ decodeLine ----

    @Test
    fun `decodes amount line 228 point 78`() {
        assertEquals("228.78", decodeRow("228.78"))
    }

    @Test
    fun `decodes liters and unit price lines`() {
        assertEquals("31.47", decodeRow("31.47"))
        assertEquals("7.27", decodeRow("7.27"))
    }

    @Test
    fun `decodes short value with one decimal place`() {
        assertEquals("12.5", decodeRow("12.5"))
    }

    @Test
    fun `decodes every digit`() {
        for (digit in '0'..'9') {
            assertEquals(digit.toString(), decodeRow(digit.toString()))
        }
    }

    @Test
    fun `decodes narrow digit one inside a number`() {
        assertEquals("1.11", decodeRow("1.11"))
        assertEquals("41.17", decodeRow("41.17"))
    }

    @Test
    fun `decodes dot glued to both neighbours`() {
        // charGap 调小到小数点同时贴住左右两数字：列分段会把「数字.数字」桥接成一段，
        // 必须靠行内列高矮把小数点再切出来。
        val tight = SevenSegmentRenderer(digits, charGap = 12)
        assertEquals("228.78", decodeRow("228.78", tight))
        assertEquals("7.27", decodeRow("7.27", tight))
    }

    @Test
    fun `decodes thicker segments and wider spacing`() {
        val chunky = SevenSegmentRenderer(
            digits,
            cellWidth = 92,
            cellHeight = 150,
            thickness = 20,
            segmentGap = 2,
            charGap = 34,
        )
        assertEquals("31.47", decodeRow("31.47", chunky))
        assertEquals("228.78", decodeRow("228.78", chunky))
    }

    @Test
    fun `decodes multiple rows top to bottom`() {
        val rows = display.renderRows(listOf("228.78", "31.47", "7.27"))
        assertEquals(listOf("228.78", "31.47", "7.27"), SevenSegmentDecoder.decodeRows(rows))
    }

    @Test
    fun `decodeRows keeps list length for undecodable rows`() {
        val rows = display.renderRows(listOf("228.78", "31.47", "7.27"))
        val blank = BinaryRow(IntArray(rows[0].mask.size), rows[0].width, rows[0].height)
        val decoded = SevenSegmentDecoder.decodeRows(listOf(rows[0], blank, rows[2]))
        assertEquals(3, decoded.size)
        assertEquals("228.78", decoded[0])
        assertNull(decoded[1])
        assertEquals("7.27", decoded[2])
    }

    // ------------------------------------------------------------ 防御性输入 ----

    @Test
    fun `empty and degenerate inputs return null`() {
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(0), 0, 0))
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(100), 10, 10))
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(10), 10, 10)) // 长度不符
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(10), 0, 10))
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(10), 10, 0))
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(10), -5, -2))
        assertNull(SevenSegmentDecoder.decodeLine(IntArray(0), Int.MAX_VALUE, 2))
        // 只有一个墨点：不足以成行。
        val single = IntArray(400)
        single[0] = 1
        assertNull(SevenSegmentDecoder.decodeLine(single, 20, 20))
        // 退化的「只有一个小数点」行：解码器不得抛异常（结果可能是 null，也可能被当成整块噪声，
        // 最终由 assembleReadings 的范围 / 一致性校验拦住）。
        val dotOnly = display.renderRow(".")
        SevenSegmentDecoder.decodeLine(dotOnly.mask, dotOnly.width, dotOnly.height)
    }

    @Test
    fun `random noise never throws and never returns junk characters`() {
        var seed = 20261109L
        val width = 160
        val height = 60
        val mask = IntArray(width * height)
        repeat(900) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val index = ((seed ushr 16).toInt() and Int.MAX_VALUE) % mask.size
            mask[index] = 1
        }
        val decoded = SevenSegmentDecoder.decodeLine(mask, width, height)
        if (decoded != null) {
            assertTrue("噪声不该产出杂字符：$decoded", decoded.all { it in '0'..'9' || it == '.' })
        }
    }

    // ------------------------------------------------------ assembleReadings ----

    @Test
    fun `assembles readings in top to bottom order`() {
        val readings = SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "7.27"))
        assertNotNull(readings)
        assertEquals(228.78, readings!!.amount!!, 1e-9)
        assertEquals(31.47, readings.liters!!, 1e-9)
        assertEquals(7.27, readings.unitPrice!!, 1e-9)
    }

    @Test
    fun `assembles readings when rows are shuffled`() {
        // 31.47 × 7.27 ≈ 228.79，靠一致性把乱序的行纠正回角色。
        for (order in listOf(
            listOf("31.47", "7.27", "228.78"),
            listOf("7.27", "228.78", "31.47"),
            listOf("31.47", "228.78", "7.27"),
        )) {
            val readings = SevenSegmentDecoder.assembleReadings(order)
            assertNotNull("顺序 $order 应可纠正", readings)
            assertEquals(228.78, readings!!.amount!!, 1e-9)
            assertEquals(31.47, readings.liters!!, 1e-9)
            assertEquals(7.27, readings.unitPrice!!, 1e-9)
        }
    }

    @Test
    fun `assembleReadings rejects inconsistency between amount liters and price`() {
        // 228.78 / 31.47 = 7.27，单价换成 6.27 后乘式对不上。
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "6.27")))
    }

    @Test
    fun `assembleReadings requires three decoded lines`() {
        assertNull(SevenSegmentDecoder.assembleReadings(emptyList()))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78")))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47")))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", null, null)))
    }

    @Test
    fun `assembleReadings skips undecodable lines`() {
        val readings = SevenSegmentDecoder.assembleReadings(listOf("228.78", null, "31.47", "7.27"))
        assertNotNull(readings)
        assertEquals(228.78, readings!!.amount!!, 1e-9)
        assertEquals(31.47, readings.liters!!, 1e-9)
        assertEquals(7.27, readings.unitPrice!!, 1e-9)
    }

    @Test
    fun `assembleReadings rejects out of range or malformed values`() {
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "-7.27")))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "700.27")))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "0.0")))
        assertNull(SevenSegmentDecoder.assembleReadings(listOf("228.78", "31.47", "7.2.7")))
    }
}
