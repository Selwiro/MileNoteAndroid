package com.milenote.android.platform.ocr

import android.graphics.Bitmap
import com.milenote.android.domain.ocr.BinaryRow
import com.milenote.android.domain.ocr.PumpReadings
import com.milenote.android.domain.ocr.SevenSegmentDecoder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 加油机屏幕（七段数码管 LED/LCD 屏）读数器：图片 → 二值掩膜 → 行带 → 列块过滤 →
 * [SevenSegmentDecoder]。
 *
 * 为什么单独做：Tesseract 按字形匹配，七段数码管的「8」是 7 个分离矩形段拼出来的，
 * 对 OCR 是结构性不可读（只会吐随机数字）。这里用纯几何解码，零新增依赖。
 *
 * 流程（[read]）：
 * 1. 最长边 >1400px 先等比缩小；
 * 2. 取 ARGB 像素交给纯 Kotlin 的 [decodePixels]（无 Android 依赖，可 JVM 验证/单测）：
 *    a. 统计 R/G/B/亮度直方图，用 **Otsu 自适应阈值**生成候选掩膜：
 *       红 LED（红优势 + R 通道 Otsu 取亮部）、蓝/青 LCD（冷色 + max(G,B) Otsu 取亮部）、
 *       深字（亮度 Otsu 取暗部）、浅字（亮度 Otsu 取亮部）；
 *    b. 行投影切行带，只保留高度接近最高行带的（丢掉标题/边框细带）；
 *    c. 行带内按列切成「列块」，丢掉高度远小于行高的列块（屏上的中文标签、单位文字只有
 *       数字高度的 1/3 左右，混在掩膜里会把整行解码毁掉）——只保留数字列块；同时剔掉
 *       重采样在数字边缘留下的 1–3 列矮毛刺（会被七段解码器误判成小数点），拼成干净的
 *       [BinaryRow]；
 *    d. 逐行解码 + 组装读数（含「金额 = 升数 × 单价」一致性校验）。
 * 3. 若 0° 全部候选都失败、行带结构像加油机屏（≥2 条数字行带）且图确实有倾斜（降采样
 *    投影估计的倾角 ≥1°，还估不出来时按规格兜底），做**小角度旋转重试**：
 *    先按估计倾角纠正，再依次尝试 ±3°、±6°（顺时针为正），每次旋转后重新掩膜 + 行切分。
 *    本来就水平的图（含整屏横向文本的小票）不会触发旋转重试，避免白白变慢。
 *
 * 任何异常 / 退化输入都返回 null，不向上抛：这是 OCR 的兜底路径，不能影响主流程。
 * 只在 IO 线程调用（会分配几 MB 的像素数组）。
 */
object PumpDisplayReader {

    /** 解码前把最长边缩到这个尺寸以内。 */
    private const val MAX_LONG_SIDE = 1400

    /** 太小的图直接放弃（七段数字至少要几十像素高才可能读对）。 */
    private const val MIN_IMAGE_SIDE = 16

    /** 缩放后允许的最大像素数（约 1400×1400），再大就不要了，避免 OOM。 */
    private const val MAX_PIXELS = 2_000_000L

    /** 候选掩膜的墨迹占比：太低是噪声，太高说明这个通道把整张图都当成了墨。 */
    private const val MIN_INK_FRACTION = 0.0005f
    private const val MAX_INK_FRACTION = 0.55f

    /** 行投影切带的阈值：单行墨点数下限（至少两个点，避免单个噪点成带）与带间合并缝。 */
    private const val MIN_ROW_INK_PIXELS = 2
    private const val BAND_ROW_MERGE = 2

    /** 忽略过矮 / 墨量过低的噪带。 */
    private const val MIN_BAND_HEIGHT_RATIO = 0.02f
    private const val MIN_BAND_INK_PIXELS = 24
    private const val MIN_BAND_INK_FRACTION = 0.015f

    /** 一屏最多处理多少个行带；防止噪点把时间吃光。 */
    private const val MAX_BANDS = 12

    /** 只保留高度不小于最高行带 50% 的行带（标题行、边框细带都会被丢掉）。 */
    private const val BAND_HEIGHT_KEEP_RATIO = 0.5f

    /** 列块内「最大墨迹高度」达到行带高度这个比例，才算数字块（中文标签/单位文字只有约 30%）。 */
    private const val RUN_HEIGHT_RATIO = 0.6f

    /** 列块分段的缝隙容差（同一数字横/竖段的直角缝、抗锯齿）。 */
    private const val RUN_GAP_TOLERANCE = 3

    /** 小数点列块的最大宽度（相对行高）：矮而窄、夹在两个数字块之间的小块。 */
    private const val DOT_RUN_WIDTH_RATIO = 0.35f

    /**
     * 行带内「矮列」的高度上限（相对行高）。七段解码器据此判定小数点。
     *
     * 二次重采样（相册放大到 1400 → 旋转重试）会在数字边缘留下 1–3 列、只有底部一个像素的
     * 毛刺列，解码器会把它当成小数点插进数字中间（实测 `228.78` → `22.8.78`）。这里在
     * 构建行掩膜前把这种又矮又窄的毛刺列剔掉，真小数点（通常 ≥8 列）不受影响。
     */
    private const val LOW_COLUMN_RATIO = 0.25f

    /** 连续矮列宽度不超过这个值才算重采样毛刺（真小数点是更宽的圆点）。 */
    private const val MAX_SLIVER_WIDTH = 3

    /** 判断「这一行像数字行」用的列块数量区间（用于决定要不要做旋转重试）。 */
    private const val DISPLAY_RUN_MIN = 3
    private const val DISPLAY_RUN_MAX = 10

    /** 行带高度达到图高这个比例才算「数字行带」，且整屏至少有这么几条才触发旋转重试。 */
    private const val DISPLAY_BAND_HEIGHT_RATIO = 0.05f
    private const val DISPLAY_BAND_MIN_COUNT = 2

    /** 小角度旋转重试的兜底角度（度，顺时针为正）。 */
    private val RETRY_ANGLES = floatArrayOf(-3f, 3f, -6f, 6f)

    /** 一次解析最多额外尝试几次旋转（含按估计倾角纠正的那一次），控制失败路径耗时。 */
    private const val MAX_ROTATION_ATTEMPTS = 4

    /** 倾斜角估计：降采样长边、搜索范围与步长。 */
    private const val SKEW_SAMPLE_LONG_SIDE = 320
    private const val SKEW_RANGE_DEGREES = 8f
    private const val SKEW_STEP_DEGREES = 0.5f

    /** 估计出的倾角小于这个值就不纠偏（横向文本 / 本来就正的图）。 */
    private const val SKEW_MIN_ABS_DEGREES = 1.0f

    /** 红 LED：R 明显高于 G/B，再用 R 通道 Otsu 阈值取亮部。 */
    private const val RED_DOMINANCE = 40
    private const val RED_FIXED_MIN = 110
    private const val RED_FIXED_MARGIN = 70

    /** 蓝/青 LCD：冷色（max(G,B) 明显高于 R）且两个通道都不暗，再用 max(G,B) 的 Otsu 阈值取亮部。 */
    private const val COLD_DOMINANCE = 50
    private const val COLD_FLOOR = 60

    /** Otsu 子集直方图至少需要有这么多像素才可信，否则跳过该候选。 */
    private const val MIN_SUBSET_PIXELS = 48

    /** 像素分类种类。 */
    private const val KIND_RED = 0
    private const val KIND_CYAN = 1
    private const val KIND_DARK = 2
    private const val KIND_LIGHT = 3

    /** 老版固定阈值的红掩膜（兜底候选）。 */
    private const val KIND_RED_FIXED = 4

    /** 倾斜角估计时，「墨迹」= 与亮度中位数相差超过这个值的像素。 */
    private const val INK_DEVIATION = 30

    /** 一次「掩膜 → 解码」尝试的结果：读数 + 像数字行的行带数（旋转重试的决策依据）。 */
    private class AttemptResult(val readings: PumpReadings?, val displayBands: Int)

    /** 一个行带里保留下来的列（逐列 keep 标记 + 保留下来的列块数）。 */
    private class BandRuns(val keep: BooleanArray, val runCount: Int)

    /** 纯像素入口的结果（旋转后的图）。 */
    private class RotatedImage(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * 从加油机屏幕照片读七段读数（金额 / 升数 / 单价）。
     *
     * @return 三项读数；读不出 / 不可信时返回 null（调用方应回退到人工填写）。
     */
    fun read(bitmap: Bitmap): PumpReadings? = runCatching {
        if (bitmap.width <= 0 || bitmap.height <= 0) return@runCatching null
        val longest = max(bitmap.width, bitmap.height)
        var scaled: Bitmap? = null
        try {
            val prepared = if (longest > MAX_LONG_SIDE) {
                val ratio = MAX_LONG_SIDE.toFloat() / longest
                val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also { scaled = it }
            } else {
                bitmap
            }
            val width = prepared.width
            val height = prepared.height
            if (width < MIN_IMAGE_SIDE || height < MIN_IMAGE_SIDE) return@runCatching null
            if (width.toLong() * height.toLong() > MAX_PIXELS) return@runCatching null
            val pixels = IntArray(width * height)
            prepared.getPixels(pixels, 0, width, 0, 0, width, height)
            decodePixels(pixels, width, height)
                ?: PumpDisplayPhotoReader.read(pixels, width, height)
        } finally {
            scaled?.recycle()
        }
    }.getOrNull()

    /**
     * 纯 Kotlin 像素入口（不使用 Android API，便于 JVM 侧验证与单测）。
     *
     * @param pixels ARGB_8888 像素（行优先），长度必须为 [width] * [height]。
     */
    @JvmName("decodePixels")
    @JvmStatic
    internal fun decodePixels(pixels: IntArray, width: Int, height: Int): PumpReadings? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size.toLong() != width.toLong() * height.toLong()) return null
        if (pixels.size > MAX_PIXELS) return null

        val first = attempt(pixels, width, height)
        first.readings?.let { return it }
        if (first.displayBands < DISPLAY_BAND_MIN_COUNT) return null

        // 图片本身已经水平（倾角估计 ≈0）时旋转只会更糟：直接放弃，不白花旋转的耗时。
        // 估不出倾角（ink 太少）但行带结构像屏时，仍按规格兜底试 ±3°/±6°。
        val skew = estimateSkewDegrees(pixels, width, height)
        val worthRetrying = skew == null || abs(skew) >= SKEW_MIN_ABS_DEGREES
        if (!worthRetrying) return null

        // 小角度旋转重试：先按估计倾角纠正，再依次兜底 ±3°/±6°。
        val angles = LinkedHashSet<Float>()
        if (skew != null && abs(skew) >= SKEW_MIN_ABS_DEGREES) angles.add(-skew)
        for (angle in RETRY_ANGLES) {
            if (angles.any { abs(it - angle) < SKEW_STEP_DEGREES * 2f }) continue
            angles.add(angle)
        }
        var attempts = 0
        for (angle in angles) {
            if (attempts >= MAX_ROTATION_ATTEMPTS) break
            attempts++
            val rotated = rotateArgb(pixels, width, height, angle) ?: break
            val result = attempt(rotated.pixels, rotated.width, rotated.height)
            result.readings?.let { return it }
            // 旋转后连数字行带都没有了：方向肯定不对，别再白试。
            if (result.displayBands < DISPLAY_BAND_MIN_COUNT) break
        }
        return null
    }

    /** 调试用：跑一遍候诊断（debug 构建的 OcrDebugActivity 调用，release 不调用）。 */
    @JvmStatic
    internal fun debugTrace(bitmap: Bitmap): String = runCatching {
        val longest = max(bitmap.width, bitmap.height)
        val prepared = if (longest > MAX_LONG_SIDE) {
            val ratio = MAX_LONG_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            val width = prepared.width
            val height = prepared.height
            val pixels = IntArray(width * height)
            prepared.getPixels(pixels, 0, width, 0, 0, width, height)
            val trace = StringBuilder("trace ${width}x$height\n")
            attempt(pixels, width, height, trace)
            val photoTrace = StringBuilder()
            val photo = PumpDisplayPhotoReader.read(pixels, width, height, photoTrace)
            trace.append("photoReadings=$photo\n").append(photoTrace)
            trace.toString()
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }.getOrElse { "trace failed: $it" }

    // ---------------------------------------------------------------- 掩膜 ----

    /** 用全部候选掩膜跑一遍；返回第一个能组装出读数的结果。 */
    private fun attempt(pixels: IntArray, width: Int, height: Int, trace: StringBuilder? = null): AttemptResult {
        // 1) 一次扫描：亮度表 + 直方图（亮度、红优势子集、冷色子集）。
        val lum = ByteArray(pixels.size)
        val lumHistogram = IntArray(256)
        val warmHistogram = IntArray(256)
        val coldHistogram = IntArray(256)
        var warmCount = 0
        var coldCount = 0
        var fixedRedCount = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val value = (r * 299 + g * 587 + b * 114) / 1000
            lum[index] = value.toByte()
            lumHistogram[value]++
            val high = max(g, b)
            if (r - high >= RED_DOMINANCE) {
                warmHistogram[r]++
                warmCount++
            }
            if (r - g >= RED_FIXED_MARGIN && r - b >= RED_FIXED_MARGIN && r >= RED_FIXED_MIN) fixedRedCount++
            if (high - r >= COLD_DOMINANCE && min(g, b) >= COLD_FLOOR) {
                coldHistogram[high]++
                coldCount++
            }
        }
        val lumThreshold = otsu(lumHistogram)
        val redThreshold = if (warmCount >= MIN_SUBSET_PIXELS) otsu(warmHistogram) else -1
        val coldThreshold = if (coldCount >= MIN_SUBSET_PIXELS) otsu(coldHistogram) else -1
        val total = pixels.size.toFloat()

        // 2) 候选掩膜的墨迹占比可以直接从直方图算出来；占比不合理就不必真去生成掩膜。
        //    注意分母都是**全图**像素数（不是子集像素数）。
        fun plausible(fraction: Float): Boolean =
            fraction >= MIN_INK_FRACTION && fraction <= MAX_INK_FRACTION

        val redFraction = (warmCount / total) * histogramFraction(warmHistogram, redThreshold + 1, 255)
        val cyanFraction = (coldCount / total) * histogramFraction(coldHistogram, coldThreshold + 1, 255)
        val darkFraction = histogramFraction(lumHistogram, 0, lumThreshold)
        val lightFraction = 1f - darkFraction

        trace?.appendLine("lumOtsu=$lumThreshold red=$redThreshold($warmCount) cold=$coldThreshold($coldCount) " +
            "fixRed=$fixedRedCount fracs red=$redFraction cyan=$cyanFraction dark=$darkFraction light=$lightFraction")

        // 3) 逐个候选掩膜尝试（红 LED → 蓝/青 LCD → 深字 → 浅字），命中即返回。
        var displayBands = 0
        var adaptiveRedUsable = false
        if (redThreshold >= 0 && plausible(redFraction)) {
            val result = runCandidate(KIND_RED, pixels, lum, redThreshold, coldThreshold, lumThreshold, width, height, trace)
            result.readings?.let { return result }
            displayBands = max(displayBands, result.displayBands)
            adaptiveRedUsable = result.displayBands > 0
        }
        // 兜底：老版固定阈值的红掩膜（个别高亮/过曝屏自适应阈值可能切歪）。
        // 自适应红掩膜已经切出数字行带时就不再花一遍全图扫描。
        if (!adaptiveRedUsable && plausible(fixedRedCount / total)) {
            val result = runCandidate(KIND_RED_FIXED, pixels, lum, redThreshold, coldThreshold, lumThreshold, width, height, trace)
            result.readings?.let { return result }
            displayBands = max(displayBands, result.displayBands)
        }
        if (coldThreshold >= 0 && plausible(cyanFraction)) {
            val result = runCandidate(KIND_CYAN, pixels, lum, redThreshold, coldThreshold, lumThreshold, width, height, trace)
            result.readings?.let { return result }
            displayBands = max(displayBands, result.displayBands)
        }
        if (plausible(darkFraction)) {
            val result = runCandidate(KIND_DARK, pixels, lum, redThreshold, coldThreshold, lumThreshold, width, height, trace)
            result.readings?.let { return result }
            displayBands = max(displayBands, result.displayBands)
        }
        if (plausible(lightFraction)) {
            val result = runCandidate(KIND_LIGHT, pixels, lum, redThreshold, coldThreshold, lumThreshold, width, height, trace)
            result.readings?.let { return result }
            displayBands = max(displayBands, result.displayBands)
        }
        return AttemptResult(null, displayBands)
    }

    private fun runCandidate(
        kind: Int,
        pixels: IntArray,
        lum: ByteArray,
        redThreshold: Int,
        coldThreshold: Int,
        lumThreshold: Int,
        width: Int,
        height: Int,
        trace: StringBuilder? = null,
    ): AttemptResult {
        val mask = buildMask(kind, pixels, lum, redThreshold, coldThreshold, lumThreshold, trace)
            ?: return EMPTY_ATTEMPT
        val result = processMask(mask, width, height, trace)
        trace?.appendLine("  kind=$kind readings=${result.readings} displayBands=${result.displayBands}")
        return result
    }

    private fun buildMask(
        kind: Int,
        pixels: IntArray,
        lum: ByteArray,
        redThreshold: Int,
        coldThreshold: Int,
        lumThreshold: Int,
        trace: StringBuilder? = null,
    ): ByteArray? {
        val mask = ByteArray(pixels.size)
        var ink = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val hit = when (kind) {
                KIND_RED -> {
                    val high = max(g, b)
                    r - high >= RED_DOMINANCE && r > redThreshold
                }
                KIND_RED_FIXED -> {
                    val high = max(g, b)
                    r - g >= RED_FIXED_MARGIN && r - b >= RED_FIXED_MARGIN && r >= RED_FIXED_MIN
                }
                KIND_CYAN -> {
                    val high = max(g, b)
                    high - r >= COLD_DOMINANCE && min(g, b) >= COLD_FLOOR && high > coldThreshold
                }
                KIND_DARK -> (lum[index].toInt() and 0xFF) <= lumThreshold
                else -> (lum[index].toInt() and 0xFF) > lumThreshold
            }
            if (hit) {
                mask[index] = 1
                ink++
            }
        }
        val fraction = ink.toFloat() / pixels.size
        trace?.appendLine("mask kind=$kind ink=$ink fraction=$fraction")
        if (fraction < MIN_INK_FRACTION || fraction > MAX_INK_FRACTION) return null
        return mask
    }

    // ------------------------------------------------------------ 行带 + 列块 ----

    private fun processMask(mask: ByteArray, width: Int, height: Int, trace: StringBuilder? = null): AttemptResult {
        // 1) 行投影。
        val rowInk = IntArray(height)
        for (y in 0 until height) {
            val base = y * width
            var count = 0
            for (x in 0 until width) {
                if (mask[base + x].toInt() != 0) count++
            }
            rowInk[y] = count
        }
        val minRowInk = max(MIN_ROW_INK_PIXELS, width / 400)
        val minBandHeight = max(4, (height * MIN_BAND_HEIGHT_RATIO).toInt())

        // 2) 切行带。
        val rawBands = ArrayList<IntArray>(8)
        var bandStart = -1
        var lastInkRow = -1
        for (y in 0 until height) {
            if (rowInk[y] < minRowInk) continue
            if (bandStart < 0) {
                bandStart = y
            } else if (y - lastInkRow - 1 > BAND_ROW_MERGE) {
                rawBands.add(intArrayOf(bandStart, lastInkRow))
                bandStart = y
            }
            lastInkRow = y
        }
        if (bandStart >= 0) rawBands.add(intArrayOf(bandStart, lastInkRow))
        trace?.appendLine("  rawBands=${rawBands.joinToString { "${it[0]}..${it[1]}" }}")
        if (rawBands.isEmpty()) return EMPTY_ATTEMPT

        // 3) 基础过滤，再只留高度接近最高行带的（标题 / 边框细带丢掉）。
        val bands = ArrayList<IntArray>(rawBands.size)
        var maxBandHeight = 0
        for (band in rawBands) {
            val top = band[0]
            val bottom = band[1]
            val bandHeight = bottom - top + 1
            if (bandHeight < minBandHeight) continue
            var bandInk = 0
            for (y in top..bottom) bandInk += rowInk[y]
            if (bandInk < MIN_BAND_INK_PIXELS) continue
            if (bandInk < MIN_BAND_INK_FRACTION * width * bandHeight) continue
            bands.add(band)
            if (bandHeight > maxBandHeight) maxBandHeight = bandHeight
            if (bands.size >= MAX_BANDS) break
        }
        trace?.appendLine("  bands=${bands.joinToString { "${it[0]}..${it[1]}" }}")
        if (bands.isEmpty()) return EMPTY_ATTEMPT
        val keepThreshold = maxBandHeight * BAND_HEIGHT_KEEP_RATIO
        val bandHeightForDisplay = height * DISPLAY_BAND_HEIGHT_RATIO

        val rows = ArrayList<BinaryRow>(bands.size)
        var displayBands = 0
        for (band in bands) {
            val top = band[0]
            val bottom = band[1]
            val bandHeight = bottom - top + 1
            if (bandHeight < keepThreshold) continue
            val bandRuns = keptColumnRuns(mask, width, top, bottom, bandHeight) ?: continue
            if (bandRuns.runCount == 0) continue
            if (bandHeight >= bandHeightForDisplay &&
                bandRuns.runCount in DISPLAY_RUN_MIN..DISPLAY_RUN_MAX
            ) {
                displayBands++
            }
            val rowMask = IntArray(width * bandHeight)
            for (x in 0 until width) {
                if (!bandRuns.keep[x]) continue
                for (y in 0 until bandHeight) {
                    if (mask[(top + y) * width + x].toInt() != 0) rowMask[y * width + x] = 1
                }
            }
            rows.add(BinaryRow(rowMask, width, bandHeight))
            trace?.appendLine("  row y=$top..$bottom h=$bandHeight runs=${bandRuns.runCount} tallInk=${bandRuns.keep.count { it }}")
        }
        if (rows.size < 3) return AttemptResult(null, displayBands)

        // 4) 逐行解码 + 组装（组装内含金额 = 升数 × 单价的一致性校验）。
        val lines = SevenSegmentDecoder.decodeRows(rows)
        trace?.appendLine("  decodedLines=$lines")
        return AttemptResult(SevenSegmentDecoder.assembleReadings(lines), displayBands)
    }

    /**
     * 行带内保持的列范围（数字块 + 夹在数字之间的小数点块）。
     *
     * 屏上的中文标签（「金额」「升数」…）和单位文字（元/L）高度通常只有数字的 1/3：
     * 它们和数字落在同一条行带里，如果原样交给 [SevenSegmentDecoder]，整行会因为有
     * 解不出的列块直接返回 null。这里按列块的墨迹高度把它们剔掉，只留数字块。
     */
    private fun keptColumnRuns(
        mask: ByteArray,
        width: Int,
        top: Int,
        bottom: Int,
        bandHeight: Int,
    ): BandRuns? {
        val extent = IntArray(width)
        val firstInk = IntArray(width) { Int.MAX_VALUE }
        val lastInk = IntArray(width) { -1 }
        for (y in top..bottom) {
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x].toInt() == 0) continue
                if (y < firstInk[x]) firstInk[x] = y
                if (y > lastInk[x]) lastInk[x] = y
            }
        }
        for (x in 0 until width) {
            if (lastInk[x] >= 0) extent[x] = lastInk[x] - firstInk[x] + 1
        }

        // 列块分段（允许小缝）。
        val runs = ArrayList<IntArray>(16)
        var runStart = -1
        var lastColumn = -1
        for (x in 0 until width) {
            if (extent[x] <= 0) continue
            if (runStart < 0) {
                runStart = x
            } else if (x - lastColumn - 1 > RUN_GAP_TOLERANCE) {
                runs.add(intArrayOf(runStart, lastColumn))
                runStart = x
            }
            lastColumn = x
        }
        if (runStart >= 0) runs.add(intArrayOf(runStart, lastColumn))
        if (runs.isEmpty()) return null

        val tallThreshold = RUN_HEIGHT_RATIO * bandHeight
        val tall = BooleanArray(runs.size)
        for (index in runs.indices) {
            val run = runs[index]
            var maxExtent = 0
            for (x in run[0]..run[1]) {
                if (extent[x] > maxExtent) maxExtent = extent[x]
            }
            tall[index] = maxExtent >= tallThreshold
        }
        var hasTall = false
        for (value in tall) if (value) hasTall = true
        if (!hasTall) return null

        // 保留：数字块 + 夹在两个数字块之间的小数点块。
        val kept = ArrayList<IntArray>(runs.size)
        val dotMaxWidth = DOT_RUN_WIDTH_RATIO * bandHeight
        for (index in runs.indices) {
            val run = runs[index]
            if (tall[index]) {
                kept.add(run)
                continue
            }
            val runWidth = run[1] - run[0] + 1
            if (runWidth > dotMaxWidth) continue
            var before = false
            var after = false
            for (other in runs.indices) {
                if (!tall[other]) continue
                if (runs[other][1] < run[0]) before = true
                if (runs[other][0] > run[1]) after = true
            }
            if (before && after) kept.add(run)
        }

        // 逐列保留标记；顺手把「又矮又窄」的重采样毛刺列剔掉（见 LOW_COLUMN_RATIO 注释）。
        val keep = BooleanArray(width)
        for (run in kept) {
            for (x in run[0]..run[1]) keep[x] = true
        }
        val lowLimit = LOW_COLUMN_RATIO * bandHeight
        var x = 0
        while (x < width) {
            if (extent[x] <= 0 || extent[x] >= lowLimit) {
                x++
                continue
            }
            var end = x
            while (end + 1 < width && extent[end + 1] > 0 && extent[end + 1] < lowLimit) end++
            if (end - x + 1 <= MAX_SLIVER_WIDTH) {
                for (index in x..end) keep[index] = false
            }
            x = end + 1
        }
        return BandRuns(keep, kept.size)
    }

    // ------------------------------------------------------------ 旋转重试 ----

    /**
     * 用降采样图像的「行投影锐度」估计倾斜角（度）。
     *
     * 原理：图像水平时，墨迹在行方向最集中，按行统计的平方和最大。对每个候选角度做
     * `row = y - (x - cx) * tan(角度)` 的错切投影，取锐度最高的角度。
     *
     * @return 使行带回到水平所需的**纠正角**的相反数（调用处取 `-skew`），估不出来返回 null。
     */
    private fun estimateSkewDegrees(pixels: IntArray, width: Int, height: Int): Float? {
        val stride = max(1, ceil(max(width, height) / SKEW_SAMPLE_LONG_SIDE.toFloat()).toInt())
        val sampleWidth = (width + stride - 1) / stride
        val sampleHeight = (height + stride - 1) / stride
        if (sampleWidth < 16 || sampleHeight < 16) return null
        val sampleLum = IntArray(sampleWidth * sampleHeight)
        var index = 0
        for (y in 0 until height step stride) {
            for (x in 0 until width step stride) {
                val color = pixels[y * width + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                sampleLum[index++] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        val sortedLum = sampleLum.copyOf()
        sortedLum.sort()
        val median = sortedLum[sortedLum.size / 2]
        val inkXs = IntArray(sampleLum.size)
        val inkYs = IntArray(sampleLum.size)
        var inkCount = 0
        for (y in 0 until sampleHeight) {
            for (x in 0 until sampleWidth) {
                if (abs(sampleLum[y * sampleWidth + x] - median) <= INK_DEVIATION) continue
                inkXs[inkCount] = x
                inkYs[inkCount] = y
                inkCount++
            }
        }
        if (inkCount < 64) return null

        val centerX = (sampleWidth - 1) / 2.0
        var bestAngle = 0f
        var bestScore = -1.0
        var angle = -SKEW_RANGE_DEGREES
        val margin = (sampleWidth * tan(Math.toRadians(SKEW_RANGE_DEGREES.toDouble()))).toInt() + 2
        while (angle <= SKEW_RANGE_DEGREES + SKEW_STEP_DEGREES / 2f) {
            val slope = tan(Math.toRadians(angle.toDouble()))
            val counts = IntArray(sampleHeight + margin * 2)
            for (i in 0 until inkCount) {
                val row = inkYs[i] - (inkXs[i] - centerX) * slope
                val target = row.roundToInt() + margin
                if (target in counts.indices) counts[target]++
            }
            var score = 0.0
            for (count in counts) score += count.toDouble() * count
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += SKEW_STEP_DEGREES
        }
        return if (bestScore <= 0.0) null else bestAngle
    }

    /**
     * 旋转 ARGB 像素（双线性插值，画布扩到能容下整幅图，空白用背景色填充）。
     *
     * @param degrees 顺时针为正（与 `Matrix.postRotate` 一致）。
     */
    private fun rotateArgb(source: IntArray, width: Int, height: Int, degrees: Float): RotatedImage? {
        if (width <= 0 || height <= 0) return null
        if (abs(degrees) < 0.01f) return RotatedImage(source, width, height)
        val radians = Math.toRadians(degrees.toDouble())
        val cosA = cos(radians)
        val sinA = sin(radians)
        val targetWidth = ceil(abs(width * cosA) + abs(height * sinA)).toInt().coerceAtLeast(1)
        val targetHeight = ceil(abs(width * sinA) + abs(height * cosA)).toInt().coerceAtLeast(1)
        if (targetWidth.toLong() * targetHeight.toLong() > MAX_PIXELS) return null
        val background = dominantColor(source)
        val target = IntArray(targetWidth * targetHeight) { background }
        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val targetCenterX = (targetWidth - 1) / 2.0
        val targetCenterY = (targetHeight - 1) / 2.0
        val maxX = width - 1
        val maxY = height - 1
        // 双线性重采样（定点整数权重）：重采样后边缘平滑，避免最近邻在段边缘产生
        // 「只带底部一个像素」的毛刺列——那会被七段解码器误判成小数点。
        for (y in 0 until targetHeight) {
            val dy = y - targetCenterY
            val rowBase = y * targetWidth
            for (x in 0 until targetWidth) {
                val dx = x - targetCenterX
                val sourceX = cosA * dx + sinA * dy + centerX
                val sourceY = -sinA * dx + cosA * dy + centerY
                if (sourceX < 0.0 || sourceY < 0.0 || sourceX > maxX || sourceY > maxY) continue
                val x0 = sourceX.toInt()
                val y0 = sourceY.toInt()
                val x1 = min(x0 + 1, maxX)
                val y1 = min(y0 + 1, maxY)
                val wx = ((sourceX - x0) * 256).toInt().coerceIn(0, 256)
                val wy = ((sourceY - y0) * 256).toInt().coerceIn(0, 256)
                val w00 = (256 - wx) * (256 - wy)
                val w10 = wx * (256 - wy)
                val w01 = (256 - wx) * wy
                val w11 = wx * wy
                val c00 = source[y0 * width + x0]
                val c10 = source[y0 * width + x1]
                val c01 = source[y1 * width + x0]
                val c11 = source[y1 * width + x1]
                var color = (0xFF shl 24)
                var shift = 16
                while (shift >= 0) {
                    val r = ((c00 shr shift) and 0xFF) * w00 +
                        ((c10 shr shift) and 0xFF) * w10 +
                        ((c01 shr shift) and 0xFF) * w01 +
                        ((c11 shr shift) and 0xFF) * w11
                    color = color or ((r shr 16).coerceIn(0, 255) shl shift)
                    shift -= 8
                }
                target[rowBase + x] = color
            }
        }
        return RotatedImage(target, targetWidth, targetHeight)
    }

    /** 取画面的代表性背景色（各通道中位数），用于填充旋转后的空白角。 */
    private fun dominantColor(pixels: IntArray): Int {
        val stride = max(1, pixels.size / 4096)
        val reds = ArrayList<Int>(pixels.size / stride + 1)
        val greens = ArrayList<Int>(reds.size)
        val blues = ArrayList<Int>(reds.size)
        var index = 0
        while (index < pixels.size) {
            val color = pixels[index]
            reds.add((color shr 16) and 0xFF)
            greens.add((color shr 8) and 0xFF)
            blues.add(color and 0xFF)
            index += stride
        }
        if (reds.isEmpty()) return 0xFF000000.toInt()
        reds.sort()
        greens.sort()
        blues.sort()
        val middle = reds.size / 2
        return (0xFF shl 24) or (reds[middle] shl 16) or (greens[middle] shl 8) or blues[middle]
    }

    // ---------------------------------------------------------------- 工具 ----

    /** 直方图里 [from, to] 区间像素占全部像素的比例。 */
    private fun histogramFraction(histogram: IntArray, from: Int, to: Int): Float {
        if (from > to) return 0f
        var count = 0L
        var total = 0L
        for (value in 0..255) {
            total += histogram[value]
            if (value in from..to) count += histogram[value]
        }
        return if (total == 0L) 0f else count.toFloat() / total
    }

    /** Otsu 阈值（0–255）：像素值 <= 返回值算暗部，> 返回值算亮部。 */
    private fun otsu(histogram: IntArray): Int {
        var total = 0L
        var sumAll = 0.0
        for (value in 0..255) {
            total += histogram[value]
            sumAll += value.toDouble() * histogram[value]
        }
        if (total == 0L) return -1
        var weightBackground = 0L
        var sumBackground = 0.0
        var bestVariance = -1.0
        var threshold = 0
        for (value in 0..255) {
            weightBackground += histogram[value]
            if (weightBackground == 0L) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0L) break
            sumBackground += value.toDouble() * histogram[value]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val difference = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = value
            }
        }
        if (bestVariance < 0.0) return -1
        return threshold
    }

    private val EMPTY_ATTEMPT = AttemptResult(null, 0)
}
