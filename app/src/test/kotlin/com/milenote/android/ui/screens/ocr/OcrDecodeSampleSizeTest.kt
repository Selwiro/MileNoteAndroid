package com.milenote.android.ui.screens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 26–27 相册解码的采样倍率（B44）。
 *
 * 以前直接全尺寸 `decodeStream`：4000×3000 解码后 48 MB，低端机容易 OOM。
 * 现在按「最长边 ≤ MAX_DECODE_SIDE（2400）+ 单张基底位图 ≤ 8 MB」算 2 的幂采样倍率。
 */
class OcrDecodeSampleSizeTest {

    @Test
    fun `typical phone photo is sampled four times`() {
        // 4000×3000（1200 万像素）：sample=2 仍有 12 MB，超过 8 MB 预算 → 4（1000×750 / 3 MB）
        assertEquals(4, decodeSampleSize(4000, 3000))
        // 竖拍同样按最长边算
        assertEquals(4, decodeSampleSize(3000, 4000))
    }

    @Test
    fun `smaller photos sample less`() {
        // 2400×1800：最长边刚好贴上限，但 17 MB 超预算 → 2（1200×900 / 4.3 MB）
        assertEquals(2, decodeSampleSize(2400, 1800))
        // 已经在预算内：不采样
        assertEquals(1, decodeSampleSize(1200, 900))
        assertEquals(1, decodeSampleSize(200, 150))
        assertEquals(1, decodeSampleSize(1600, 1200)) // 1.6M 像素 ≈ 7.7 MB，正好在 8 MB 内
        // 2400×1200 = 11.5 MB 超预算 → 采样一档
        assertEquals(2, decodeSampleSize(2400, 1200))
    }

    @Test
    fun `broken dimensions fall back to no sampling`() {
        assertEquals(1, decodeSampleSize(0, 0))
        assertEquals(1, decodeSampleSize(-5, 3000))
    }

    @Test
    fun `result is always a power of two that satisfies both limits`() {
        val sizes = listOf(6000 to 4000, 4032 to 3024, 8000 to 6000, 12000 to 9000, 3000 to 2000)
        for ((width, height) in sizes) {
            val sample = decodeSampleSize(width, height)
            assertTrue("sample 必须是 2 的幂：$sample", sample > 0 && sample and (sample - 1) == 0)
            val sampledWidth = (width + sample - 1) / sample
            val sampledHeight = (height + sample - 1) / sample
            assertTrue(
                "解码后最长边应 ≤ 2400（实际 ${sampledWidth}x$sampledHeight, sample=$sample）",
                maxOf(sampledWidth, sampledHeight) <= 2400,
            )
            assertTrue(
                "解码后体积应 ≤ 8 MB（实际 ${sampledWidth}x$sampledHeight, sample=$sample）",
                sampledWidth.toLong() * sampledHeight * 4L <= 8L * 1024 * 1024,
            )
            // 再小一档就必然违反约束（说明没有过度采样）
            val smaller = (sample / 2).coerceAtLeast(1)
            val smallerWidth = (width + smaller - 1) / smaller
            val smallerHeight = (height + smaller - 1) / smaller
            assertTrue(
                "sample=$sample 已经是满足约束的最小 2 的幂（$smaller 也应可行）",
                smaller == sample ||
                    maxOf(smallerWidth, smallerHeight) > 2400 ||
                    smallerWidth.toLong() * smallerHeight * 4L > 8L * 1024 * 1024,
            )
        }
    }
}
