package com.milenote.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * dataURL 解码的两处保护（1.0 审查 #25/#26）：
 * 采样倍率按最长边算；缓存键必须区分不同图片。
 *
 * 这里只测纯计算部分——真正的 Bitmap 解码需要 Android 运行时，走模拟器验收。
 */
class DataUrlImagesTest {

    @Test
    fun `sample size follows the longest side`() {
        // 窄长图：最短边很小，以前倍率是 1 → 按原像素解码。
        assertEquals(27, DataUrlImages.sampleSize(100, 20_000, target = 720))
        assertEquals(1, DataUrlImages.sampleSize(100, 400, target = 720))
        // 普通横图：按最长边缩到目标尺寸。
        assertEquals(5, DataUrlImages.sampleSize(4000, 3000, target = 720))
    }

    @Test
    fun `cache key tells two same sized images apart`() {
        val prefix = "data:image/png;base64," + "A".repeat(96 - "data:image/png;base64,".length)
        val first = prefix + "A".repeat(200) + "TAIL-ONE"
        val second = prefix + "A".repeat(200) + "TAIL-TWO"
        assertEquals(first.length, second.length)

        assertNotEquals(DataUrlImages.cacheKey(first), DataUrlImages.cacheKey(second))
    }

    @Test
    fun `cache key is stable for the same image`() {
        val value = "data:image/png;base64," + "B".repeat(300)

        assertEquals(DataUrlImages.cacheKey(value), DataUrlImages.cacheKey(value))
    }
}
