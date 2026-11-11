package com.milenote.android.platform.ocr

import com.milenote.android.domain.ocr.PumpReadings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 加油机屏幕照片（LCD 液晶 / 空心七段数码）读数器。
 *
 * 与 [PumpDisplayReader]（面向干净二值掩膜、实心段）互补：照片里的七段数字常常是
 * **空心轮廓**（每段只画出边框、中间是背景色），也可能整屏躺在花纹背景上。这里换一套
 * 自底向上的流程，纯像素运算、不依赖 Android API，可在 JVM 侧单测：
 *
 * 1. 亮度 + 局部对比度掩膜（box mean 自适应阈值）：只保留「比周围明显更暗/更亮」的
 *    结构，天然忽略大面积均匀背景；对多种阈值各跑一遍。
 * 2. 连通域分析：屏的边框/外壳是一圈大而空的连通域（bbox 大、填充率低），把它的
 *    包围盒当作显示屏候选区域；金额/升数/单价小窗也会各自成为一个候选。
 * 3. 候选区域往里收一圈边距后做行投影得到数字行带（把过高的带在谷底切开）；
 * 4. 对每条行带用**纯数字解码**（小数点直接跳过，最后两位固定是小数）读出一串数字；
 * 5. 用 `金额 ≈ 升数 × 单价` 做一致性校验确定三项读数；只有两项时按常见油价区间补出单价。
 *
 * 刻意「宁可不填，不写错值」：任何一项过不了解析/一致性校验就返回 null，让上层回退手填。
 */
internal object PumpDisplayPhotoReader {

    /** 局部对比度阈值候选：暗字（深色 LCD 反光/无背光）与亮字（背光 LCD）各几档。 */
    private val DARK_DELTAS = intArrayOf(14, 18, 22, 26, 30, 40)
    private val LIGHT_DELTAS = intArrayOf(16, 22, 26, 32, 38)

    /** 局部均值窗口半径：取画面短边的这个比例（约为数字高度的 1/2–1 倍）。 */
    private const val MEAN_RADIUS_RATIO = 1f / 16f
    private const val MIN_MEAN_RADIUS = 8
    private const val MAX_MEAN_RADIUS = 96

    /** 行带提取：行墨量阈值（相对该候选区域行墨量峰值）与切带容差。 */
    private const val ROW_SEED_RATIO = 0.12f
    private const val ROW_MERGE_GAP = 2
    private const val MIN_BAND_HEIGHT = 8

    /** 候选区域（屏/小窗）的连通域过滤条件。 */
    private const val MIN_REGION_WIDTH_RATIO = 0.08f
    private const val MIN_REGION_HEIGHT_RATIO = 0.05f
    private const val MAX_REGION_AREA_RATIO = 0.55f
    private const val MAX_REGION_FILL = 0.42f
    private const val MIN_REGION_ASPECT = 0.55f
    private const val MAX_REGION_ASPECT = 7f

    /** 候选区域往里收的边距（去掉边框/倒角）：短边的比例 + 绝对上下限。 */
    private const val REGION_MARGIN_RATIO = 0.04f
    private const val MIN_REGION_MARGIN = 5
    private const val MAX_REGION_MARGIN = 28

    /** 一条行带最多看几个候选区域；防止照片里花纹多时耗时失控。 */
    private const val MAX_REGIONS = 6

    /** 一行数字串允许的长度（3–6 位：7.78 / 80.00 / 622.40 / 228.78）。 */
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 6

    /** 加油机小数位固定 2 位（金额/升数/单价均如此）。 */
    private const val DECIMALS = 100.0

    private class Component(
        var left: Int,
        var top: Int,
        var right: Int,
        var bottom: Int,
        var area: Int,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private class RowValue(val value: Double, val top: Int, val bottom: Int, val text: String)

    /**
     * 从 ARGB 像素读加油机读数；读不出返回 null。
     */
    fun read(pixels: IntArray, width: Int, height: Int, trace: StringBuilder? = null): PumpReadings? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size.toLong() != width.toLong() * height.toLong()) return null
        val allRows = ArrayList<RowValue>()
        // 小图（手机相册里常只有几百像素）会被上层放大到 1400+，掩膜会糊；同时用原尺度和
        // 降采样后再跑一遍，多尺度结果一起做一致性校验。
        for (scale in floatArrayOf(1f)) {
            val sw = (width * scale).roundToInt().coerceAtLeast(32)
            val sh = (height * scale).roundToInt().coerceAtLeast(32)
            val scaled = if (scale == 1f) pixels else downsample(pixels, width, height, sw, sh)
            allRows += readRows(scaled, sw, sh, trace)
        }
        // 跨阈值复现 ≥2 次的读数更可信（同一数字在不同掩膜下都读成一样）；先用它做严格校验，
        // 不行再退回全部候选。
        val repeated = allRows
            .groupBy { it.text }
            .filterValues { it.size >= 2 }
            .values
            .flatten()
        // 宁可不填、不写错值：只接受「跨阈值复现 + 常见油价区间 + 行序」都通过的结果。
        val readings = assemble(repeated)
        trace?.appendLine("photo assemble=$readings rows=${allRows.size} repeated=${repeated.size}")
        return readings
    }

    /** 逐尺度：亮度 → 局部对比度掩膜 → 连通域候选区域 → 行带 → 数字候选。 */
    private fun readRows(pixels: IntArray, width: Int, height: Int, trace: StringBuilder?): List<RowValue> {
        val lum = IntArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            lum[index] = (r * 299 + g * 587 + b * 114) / 1000
        }
        val radius = (min(width, height) * MEAN_RADIUS_RATIO).roundToInt()
            .coerceIn(MIN_MEAN_RADIUS, MAX_MEAN_RADIUS)
        val mean = boxMean(lum, width, height, radius)
        val allRows = ArrayList<RowValue>()
        for (delta in DARK_DELTAS) {
            val rows = readWithMask(lum, mean, width, height, dark = true, delta = delta, trace = trace)
            trace?.appendLine("photo ${width}x$height dark delta=$delta rows=${rows.joinToString { "${it.text}@${it.top}" }}")
            allRows += rows
        }
        for (delta in LIGHT_DELTAS) {
            val rows = readWithMask(lum, mean, width, height, dark = false, delta = delta, trace = trace)
            trace?.appendLine("photo ${width}x$height light delta=$delta rows=${rows.joinToString { "${it.text}@${it.top}" }}")
            allRows += rows
        }
        return allRows
    }

    /** 最近邻降采样（只用于多尺度重试，保边比插值更利于七段数字）。 */
    private fun downsample(pixels: IntArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): IntArray {
        val output = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y.toLong() * height / targetHeight).toInt().coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val sourceX = (x.toLong() * width / targetWidth).toInt().coerceIn(0, width - 1)
                output[y * targetWidth + x] = pixels[sourceY * width + sourceX]
            }
        }
        return output
    }

    private fun readWithMask(
        lum: IntArray,
        mean: IntArray,
        width: Int,
        height: Int,
        dark: Boolean,
        delta: Int,
        trace: StringBuilder? = null,
    ): List<RowValue> {
        val mask = BooleanArray(pixelsCount(width, height))
        var ink = 0
        for (index in lum.indices) {
            val hit = if (dark) lum[index] < mean[index] - delta else lum[index] > mean[index] + delta
            if (hit) {
                mask[index] = true
                ink++
            }
        }
        val fraction = ink.toFloat() / mask.size
        if (fraction < 0.005f || fraction > 0.45f) return emptyList()

        val components = components(mask, width, height)
        val candidates = components
            .filter { component ->
                component.width >= width * MIN_REGION_WIDTH_RATIO &&
                    component.height >= height * MIN_REGION_HEIGHT_RATIO &&
                    component.width.toLong() * component.height.toLong() <=
                    (width.toLong() * height.toLong() * MAX_REGION_AREA_RATIO).toLong() &&
                    component.area.toFloat() / (component.width * component.height) <= MAX_REGION_FILL &&
                    component.width.toFloat() / component.height in MIN_REGION_ASPECT..MAX_REGION_ASPECT
            }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .take(MAX_REGIONS)

        val rows = ArrayList<RowValue>()
        trace?.appendLine("  fraction=$fraction components=${components.size} candidates=${candidates.size} " +
            candidates.joinToString { "(${it.left},${it.top},${it.width}x${it.height},fill=${"%.2f".format(it.area.toFloat() / (it.width * it.height))})" })
        for (component in candidates) {
            val margin = (min(component.width, component.height) * REGION_MARGIN_RATIO)
                .roundToInt()
                .coerceIn(MIN_REGION_MARGIN, MAX_REGION_MARGIN)
            val left = component.left + margin
            val top = component.top + margin
            val right = component.right - margin
            val bottom = component.bottom - margin
            if (right - left < 24 || bottom - top < 16) continue
            trace?.appendLine("    region x$left..$right y$top..$bottom")
            val regionRows = readRegion(mask, width, left, top, right, bottom, trace)
            trace?.appendLine("      rows=${regionRows.joinToString { "${it.text}@${it.top}" }}")
            rows += regionRows
        }
        return mergeRows(rows)
    }

    /** 在一个候选区域内做行投影 → 行带 → 逐行数字解码。 */
    private fun readRegion(
        mask: BooleanArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        trace: StringBuilder? = null,
    ): List<RowValue> {
        val regionWidth = right - left + 1
        val regionHeight = bottom - top + 1
        val rowInk = IntArray(regionHeight)
        for (y in top..bottom) {
            val base = y * stride
            var count = 0
            for (x in left..right) if (mask[base + x]) count++
            rowInk[y - top] = count
        }
        val peak = rowInk.maxOrNull() ?: return emptyList()
        if (peak <= 0) return emptyList()
        val seed = max(2, (peak * ROW_SEED_RATIO).toInt())

        val rawBands = ArrayList<IntArray>(4)
        var start = -1
        var last = -1
        for (y in rowInk.indices) {
            if (rowInk[y] < seed) continue
            if (start < 0) {
                start = y
            } else if (y - last - 1 > ROW_MERGE_GAP) {
                rawBands.add(intArrayOf(start, last))
                start = y
            }
            last = y
        }
        if (start >= 0) rawBands.add(intArrayOf(start, last))

        val rows = ArrayList<RowValue>()
        // 行首可能粘着屏框/反光碎片（如 `68000` 实为 `80.00`）：同时保留去掉 1–2 位前导字符的
        // 变体，交给一致性校验挑出正确读数。
        fun addVariants(row: RowValue) {
            rows.add(row)
            for (drop in 1..2) {
                if (row.text.length - drop < MIN_DIGITS) break
                val text = row.text.substring(drop)
                val value = toValue(text) ?: continue
                rows.add(RowValue(value, row.top, row.bottom, text))
            }
        }
        for (band in rawBands) {
            val decodedRows = decodeBand(mask, stride, left, top, right, regionWidth, band[0], band[1], trace)
            if (decodedRows.isNotEmpty()) {
                decodedRows.forEach { addVariants(it) }
                continue
            }
            // 整条带解不出（多半是金额/升数两行连成一条）→ 在谷底切开再试。
            val bandHeight = band[1] - band[0] + 1
            if (bandHeight <= max(MIN_BAND_HEIGHT, (regionHeight * 0.35f).toInt())) continue
            for ((bandTop, bandBottom) in splitBand(rowInk, band[0], band[1], regionHeight)) {
                decodeBand(mask, stride, left, top, right, regionWidth, bandTop, bandBottom, trace)?.let { rows += it }
            }
        }
        return rows
    }

    /** 取行带掩膜并解码成读数；解不出返回 null。 */
    private fun decodeBand(
        mask: BooleanArray,
        stride: Int,
        left: Int,
        regionTop: Int,
        right: Int,
        regionWidth: Int,
        bandTop: Int,
        bandBottom: Int,
        trace: StringBuilder? = null,
    ): List<RowValue> {
        val bandHeight = bandBottom - bandTop + 1
        if (bandHeight < MIN_BAND_HEIGHT) return emptyList()
        val rowMask = BooleanArray(regionWidth * bandHeight)
        for (y in 0 until bandHeight) {
            val sourceBase = (regionTop + bandTop + y) * stride
            val targetBase = y * regionWidth
            for (x in 0 until regionWidth) {
                if (mask[sourceBase + left + x]) rowMask[targetBase + x] = true
            }
        }
        // 去掉贯穿整行的「边框横线」：这类行在行投影里很强，却会把数字高度撑大、破坏采样。
        val lineThreshold = 0.7f * regionWidth
        for (y in 0 until bandHeight) {
            var count = 0
            val base = y * regionWidth
            for (x in 0 until regionWidth) if (rowMask[base + x]) count++
            if (count >= lineThreshold) {
                for (x in 0 until regionWidth) rowMask[base + x] = false
            }
        }
        // 行带可能横跨整屏（含边框/反光残影）：按「高列」聚成若干数字簇，逐簇解码。
        val texts = decodeBandCandidates(rowMask, regionWidth, bandHeight, trace)
        trace?.appendLine("      band y${regionTop + bandTop}..${regionTop + bandBottom} h${bandHeight} texts=${texts.joinToString()}")
        val values = texts.mapNotNull { toValue(it) }
        return values.mapIndexed { index, value -> RowValue(value, regionTop + bandTop, regionTop + bandBottom, texts[index]) }
    }

    /**
     * 把行带按「高列」切成若干数字簇（排除屏框/反光等矮结构），逐簇解码；
     * 簇宽至少约一个字高，避免把小数点/碎片当成一条读数。
     */
    private fun decodeDigitGroups(
        mask: BooleanArray,
        width: Int,
        bandHeight: Int,
        trace: StringBuilder? = null,
    ): String? {
        val firstInk = IntArray(width) { Int.MAX_VALUE }
        val lastInk = IntArray(width) { -1 }
        for (y in 0 until bandHeight) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                if (y < firstInk[x]) firstInk[x] = y
                if (y > lastInk[x]) lastInk[x] = y
            }
        }
        val tallThreshold = 0.55f * bandHeight
        val gapTolerance = max(3, (bandHeight * 0.15f).toInt())
        val groups = ArrayList<IntArray>(4)
        var groupStart = -1
        var groupEnd = -1
        var lastTall = -1
        for (x in 0 until width) {
            val extent = if (lastInk[x] >= 0) lastInk[x] - firstInk[x] + 1 else 0
            if (extent < tallThreshold) continue
            if (groupStart < 0) {
                groupStart = x
            } else if (x - lastTall - 1 > gapTolerance) {
                groups.add(intArrayOf(groupStart, lastTall))
                groupStart = x
            }
            lastTall = x
            groupEnd = x
        }
        if (groupStart >= 0) groups.add(intArrayOf(groupStart, groupEnd))
        for (group in groups) {
            val groupWidth = group[1] - group[0] + 1
            if (groupWidth < bandHeight) continue
            val subMask = BooleanArray(groupWidth * bandHeight)
            for (y in 0 until bandHeight) {
                val sourceBase = y * width
                val targetBase = y * groupWidth
                for (x in 0 until groupWidth) {
                    if (mask[sourceBase + group[0] + x]) subMask[targetBase + x] = true
                }
            }
            val text = decodeDigits(subMask, groupWidth, bandHeight, trace)
            trace?.appendLine("        group x${group[0]}..${group[1]} w$groupWidth -> $text")
            if (text != null && text.length >= MIN_DIGITS) return text
        }
        return null
    }

    /**
     * 一条行带可能给出多个候选读数（整行 / 数字簇 / 上下裁切变体）：全部返回，
     * 交由一致性校验（金额≈升数×单价）挑出正确组合。宁多勿漏，但每个候选都要能解成数。
     */
    private fun decodeBandCandidates(
        mask: BooleanArray,
        width: Int,
        bandHeight: Int,
        trace: StringBuilder? = null,
    ): List<String> {
        val texts = LinkedHashSet<String>()
        decodeDigits(mask, width, bandHeight, trace)?.let { texts.add(it) }
        decodeDigitGroups(mask, width, bandHeight, trace)?.let { texts.add(it) }
        // 上下界被屏框/反光带偏：按若干行高比例与偏移重新裁切再解。
        if (bandHeight >= MIN_BAND_HEIGHT * 2) {
            for (fraction in floatArrayOf(0.95f, 0.88f, 0.80f, 0.72f, 0.64f)) {
                val height = (bandHeight * fraction).roundToInt().coerceAtLeast(MIN_BAND_HEIGHT)
                val maxOffset = bandHeight - height
                if (maxOffset <= 0) continue
                for (offset in 0..maxOffset step max(2, maxOffset / 5)) {
                    val subMask = BooleanArray(width * height)
                    for (y in 0 until height) {
                        val sourceBase = (y + offset) * width
                        val targetBase = y * width
                        for (x in 0 until width) if (mask[sourceBase + x]) subMask[targetBase + x] = true
                    }
                    decodeDigits(subMask, width, height, null)?.let { texts.add(it) }
                    if (texts.size >= 5) return texts.toList()
                }
            }
        }
        return texts.toList()
    }

    /** 过高的行带（可能把金额/升数两行连成一条）在轮廓谷底切开。 */
    private fun splitBand(rowInk: IntArray, top: Int, bottom: Int, regionHeight: Int): List<IntArray> {
        val height = bottom - top + 1
        if (height <= max(MIN_BAND_HEIGHT, (regionHeight * 0.22f).toInt())) {
            return listOf(intArrayOf(top, bottom))
        }
        val from = top + height / 3
        val to = bottom - height / 3
        if (to <= from) return listOf(intArrayOf(top, bottom))
        var splitIndex = from
        var lowest = Int.MAX_VALUE
        for (y in from..to) {
            if (rowInk[y] < lowest) {
                lowest = rowInk[y]
                splitIndex = y
            }
        }
        val leftPeak = (top..splitIndex).maxOf { rowInk[it] }
        val rightPeak = (splitIndex..bottom).maxOf { rowInk[it] }
        if (lowest >= 0.8f * max(leftPeak, rightPeak)) return listOf(intArrayOf(top, bottom))
        return listOf(intArrayOf(top, splitIndex), intArrayOf(splitIndex + 1, bottom))
    }

    /**
     * 纯数字解码：与 [com.milenote.android.domain.ocr.SevenSegmentDecoder.decodeLine] 相同的
     * 七段采样思路，但**忽略小数点/矮碎片**（七段屏小数点常与数字粘连、且总是 2 位小数，
     * 交给 [toValue] 统一补），并允许行首尾解不出的碎片（多为边框残留）。
     *
     * @return 纯数字串（如 `22878`）；解不出返回 null。
     */
    private fun decodeDigits(mask: BooleanArray, width: Int, height: Int, trace: StringBuilder? = null): String? {
        if (width <= 0 || height <= 0) return null
        if (mask.size.toLong() != width.toLong() * height.toLong()) return null

        val colTop = IntArray(width) { Int.MAX_VALUE }
        val colBottom = IntArray(width) { -1 }
        var inkTop = height
        var inkBottom = -1
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                if (y < inkTop) inkTop = y
                if (y > inkBottom) inkBottom = y
                if (y < colTop[x]) colTop[x] = y
                if (y > colBottom[x]) colBottom[x] = y
            }
        }
        if (inkBottom < 0) return null
        val inkHeight = inkBottom - inkTop + 1
        if (inkHeight < MIN_BAND_HEIGHT) return null
        val gapTolerance = max(3, (inkHeight * 0.06f).roundToInt())

        // 列分段（允许同一数字内横/竖段之间的直角缝）。
        val segments = ArrayList<IntArray>(8)
        var start = -1
        var last = -1
        for (x in 0 until width) {
            if (colBottom[x] < 0) continue
            if (start < 0) {
                start = x
            } else if (x - last - 1 > gapTolerance) {
                segments.add(intArrayOf(start, last))
                start = x
            }
            last = x
        }
        if (start >= 0) segments.add(intArrayOf(start, last))
        if (segments.isEmpty() || segments.size > 32) return null
        trace?.appendLine("        decode inkH=$inkHeight segments=${segments.size} ${segments.joinToString { "${it[0]}-${it[1]}" }}")

        // 段内按列高矮切开：矮列（小数点/粘连碎片）直接跳过，只保留数字块。
        val cells = ArrayList<IntArray>(8)
        for (segment in segments) {
            var runStart = -1
            var runEnd = -1
            for (x in segment[0]..segment[1]) {
                val columnHeight = if (colBottom[x] >= 0) colBottom[x] - colTop[x] + 1 else 0
                if (columnHeight >= 0.30f * inkHeight) {
                    if (runStart < 0) runStart = x
                    runEnd = x
                } else if (runStart >= 0) {
                    cells.add(intArrayOf(runStart, runEnd))
                    runStart = -1
                }
            }
            if (runStart >= 0) cells.add(intArrayOf(runStart, runEnd))
        }
        if (cells.isEmpty()) return null
        trace?.appendLine("        cells=${cells.joinToString { "${it[0]}-${it[1]}" }}")

        // 先按字宽中位数估出参考字宽，再合并「同一数字被拆开的碎片」：空心七段字的竖段
        // 在列上可能断开（如 4 的左右竖段），碎片缝隙小、合并后仍是一个字宽。
        val widths = IntArray(cells.size) { cells[it][1] - cells[it][0] + 1 }
        widths.sort()
        val medianWidth = widths[widths.size / 2]
        val referenceWidth = max(medianWidth, (inkHeight * 0.45f).roundToInt())
        val merged = mergeFragments(cells, referenceWidth)
        // 整行可能夹着屏框/反光碎片：先试全体，再试「连续若干数字单元」的子窗口，
        // 取位数最多的那一条（数字串越长越像完整读数）。
        decodeMergedCells(mask, width, height, merged, referenceWidth, inkHeight, trace)?.let { return it }
        var best: String? = null
        for (start in 0 until merged.size - 1) {
            var widthSum = 0
            for (end in start until merged.size) {
                widthSum += merged[end][1] - merged[end][0] + 1
                if (end - start + 1 < 3) continue
                if (widthSum > 7 * referenceWidth) break
                if (widthSum < 2.0f * referenceWidth) continue
                val window = ArrayList<IntArray>(end - start + 1)
                for (index in start..end) window.add(merged[index])
                val text = decodeMergedCells(mask, width, height, window, referenceWidth, inkHeight, null)
                if (text != null && (best == null || text.length > best!!.length)) best = text
            }
        }
        trace?.appendLine("        subset best=$best")
        return best
    }

    /** 合并被列缝隙拆开的同一数字碎片；同时剔除屏框/反光在行首尾留下的残影。 */
    private fun mergeFragments(cells: ArrayList<IntArray>, referenceWidth: Int): ArrayList<IntArray> {
        val merged = ArrayList<IntArray>(cells.size)
        for (cell in cells) {
            if (merged.isNotEmpty()) {
                val previous = merged.last()
                val gap = cell[0] - previous[1] - 1
                val combined = cell[1] - previous[0] + 1
                if (gap <= max(4, (0.30f * referenceWidth).toInt()) && combined <= 1.2f * referenceWidth) {
                    merged[merged.size - 1] = intArrayOf(previous[0], cell[1])
                    continue
                }
            }
            merged.add(intArrayOf(cell[0], cell[1]))
        }
        return merged
    }

    private fun decodeMergedCells(
        mask: BooleanArray,
        width: Int,
        height: Int,
        cells: ArrayList<IntArray>,
        referenceWidth: Int,
        inkHeight: Int,
        trace: StringBuilder? = null,
    ): String? {
        val colTop = IntArray(width) { Int.MAX_VALUE }
        val colBottom = IntArray(width) { -1 }
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                if (y < colTop[x]) colTop[x] = y
                if (y > colBottom[x]) colBottom[x] = y
            }
        }
        val out = StringBuilder(cells.size)
        for (cell in cells) {
            var x0 = cell[0]
            val x1 = cell[1]
            var minY = Int.MAX_VALUE
            var maxY = -1
            for (x in x0..x1) {
                if (colTop[x] < minY) minY = colTop[x]
                if (colBottom[x] > maxY) maxY = colBottom[x]
            }
            if (maxY < minY) continue
            var cellWidth = x1 - x0 + 1
            if (cellWidth < 3) continue
            val atEdge = x0 <= width * 0.05f || x1 >= width * 0.95f
            if (atEdge && cellWidth < 0.45f * referenceWidth) {
                trace?.appendLine("        skip edge artifact x$x0..$x1 w$cellWidth")
                continue
            }
            if (cellWidth < 0.6f * referenceWidth) {
                x0 = (x1 + 1 - referenceWidth).coerceIn(0, width - 1)
                cellWidth = x1 - x0 + 1
            }
            if (cellWidth < 3) continue
            val digit = decodeDigit(mask, width, height, x0, minY, x1, maxY) ?: run {
                trace?.appendLine("        cell x$x0..$x1 y$minY..$maxY undecodable")
                return null
            }
            out.append(digit)
        }
        trace?.appendLine("        digits=$out")
        if (out.length < MIN_DIGITS || out.length > MAX_DIGITS + 1) return null
        return out.toString()
    }

    /** 在归一化数字框里采样 7 段，汉明距离匹配 0–9；距离 >2 或并列最优返回 null。 */
    private fun decodeDigit(
        mask: BooleanArray,
        stride: Int,
        rows: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Char? {
        val cellWidth = x1 - x0 + 1
        val cellHeight = y1 - y0 + 1
        if (cellWidth < 3 || cellHeight < MIN_BAND_HEIGHT) return null
        var bits = 0
        for (index in SEGMENT_REGIONS.indices) {
            val region = SEGMENT_REGIONS[index]
            val left = x0 + (region[0] * cellWidth).roundToInt()
            val top = y0 + (region[1] * cellHeight).roundToInt()
            val right = x0 + (region[2] * cellWidth).roundToInt()
            val bottom = y0 + (region[3] * cellHeight).roundToInt()
            if (regionMean(mask, stride, rows, left, top, right, bottom) > SEGMENT_ON_THRESHOLD) {
                bits = bits or (1 shl index)
            }
        }
        var bestDistance = Int.MAX_VALUE
        var bestDigit = -1
        var bestCount = 0
        for (digit in 0..9) {
            val distance = Integer.bitCount(bits xor DIGIT_PATTERNS[digit])
            when {
                distance < bestDistance -> {
                    bestDistance = distance
                    bestDigit = digit
                    bestCount = 1
                }
                distance == bestDistance -> bestCount++
            }
        }
        if (bestDistance > MAX_HAMMING_DISTANCE || bestCount != 1) return null
        return '0' + bestDigit
    }

    /** 纯数字串 → 数值（固定两位小数）；量级不合理返回 null。 */
    private fun toValue(text: String): Double? {
        if (text.length < MIN_DIGITS || text.length > MAX_DIGITS) return null
        if (text.any { it !in '0'..'9' }) return null
        val digits = text.toLongOrNull() ?: return null
        val value = digits / DECIMALS
        if (value <= 0.0) return null
        return value
    }

    /**
     * 同一行可能被多个阈值/区域各读一遍：按「y 相近且文本相同」去重，
     * 但保留不同文本（前导碎片变体，如 `662240` / `62240`），交给一致性校验挑选。
     */
    private fun mergeRows(rows: List<RowValue>): List<RowValue> {
        val merged = ArrayList<RowValue>()
        for (row in rows.sortedBy { it.top }) {
            val duplicate = merged.any {
                it.text == row.text && abs(it.top - row.top) <= max(4, (row.bottom - row.top) / 3)
            }
            if (!duplicate) merged.add(row)
        }
        return merged
    }

    /**
     * 用 `金额 ≈ 升数 × 单价` 一致性校验从候选行里挑出三项读数。
     *
     * 行顺序不固定（有的机型金额在上、有的在中），先试三行组合，再退回两行 + 常见油价区间
     * 反推单价（七段屏单价小窗常因反光读不出）。
     */
    private fun assemble(rows: List<RowValue>): PumpReadings? {
        if (rows.isEmpty()) return null
        // 同一行会带多个候选变体（662240 / 62240 / 2240）：按 y 归为一条线，
        // 每条线在一次一致性校验里只能贡献一个值。
        val lines = ArrayList<MutableList<RowValue>>()
        for (row in rows.sortedBy { it.top }) {
            val line = lines.firstOrNull { abs(it.first().top - row.top) <= 4 }
            if (line == null) lines.add(mutableListOf(row)) else line.add(row)
        }
        // 七段屏读数有结构约束：金额行在升数行上方；单价窗通常在它们下方（本机照片也都如此）。
        // 先用严格油价区间（国内常见 5–10 元/L）+ 行序挑分最高的一组，再放宽兜底。
        strictPriceRange(rows = lines)?.let { return it }
        return bestEffort(rows = lines, priceRange = TYPICAL_PRICE_RANGE)
    }

    /** 严格油价区间的三行/两行组合；必须满足金额行在升数行上方。 */
    private fun strictPriceRange(rows: List<MutableList<RowValue>>): PumpReadings? =
        bestEffort(rows, priceRange = 5.0..9.99, strict = true)

    private fun bestEffort(
        rows: List<MutableList<RowValue>>,
        priceRange: ClosedFloatingPointRange<Double>,
        strict: Boolean = false,
    ): PumpReadings? {
        var best: PumpReadings? = null
        var bestScore = Int.MIN_VALUE
        fun consider(candidate: PumpReadings?, score: Int) {
            if (candidate == null) return
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        val lines = rows
        // 三行组合：金额 / 升数 / 单价 的角色排列。
        for (i in 0 until lines.size) {
            for (j in i + 1 until lines.size) {
                for (k in j + 1 until lines.size) {
                    val lineTriples = listOf(i to j to k)
                    for (triple in lineTriples) {
                        val firstLine = lines[i]
                        val secondLine = lines[j]
                        val thirdLine = lines[k]
                        val lineList = listOf(firstLine, secondLine, thirdLine)
                        for (amountLine in lineList) {
                            for (litersLine in lineList) {
                                if (litersLine === amountLine) continue
                                val priceLine = lineList.first { it !== amountLine && it !== litersLine }
                                if (amountLine.first().top >= litersLine.first().top) continue
                                for (amountRow in amountLine) {
                                    for (litersRow in litersLine) {
                                        for (priceRow in priceLine) {
                                            val amount = amountRow.value
                                            val liters = litersRow.value
                                            val price = priceRow.value
                                            if (amount !in 10.0..100_000.0) continue
                                            if (liters !in 3.0..2000.0) continue
                                            if (price !in priceRange) continue
                                            if (priceRow.top < litersLine.first().top) continue
                                            val tolerance = maxOf(0.05, amount * 0.003, liters * 0.006)
                                            if (abs(amount - liters * price) > tolerance) continue
                                            val readings = PumpReadings(
                                                amount = round2(amount),
                                                liters = round2(liters),
                                                unitPrice = round2(price),
                                            )
                                            consider(readings, scoreOf(amountRow, litersRow, priceRow, hasPrice = true))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 两行兜底：单价窗读不出时用「金额 ÷ 升数」补（仍要求金额行在升数行上方）。
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val upper = lines[i]
                val lower = lines[j]
                for (amountRow in upper) {
                    for (litersRow in lower) {
                        if (amountRow.text.length < 4 || litersRow.text.length < 4) continue
                        val amount = amountRow.value
                        val liters = litersRow.value
                        if (amount !in 10.0..100_000.0) continue
                        if (liters !in 3.0..2000.0) continue
                        val price = amount / liters
                        if (price !in priceRange) continue
                        val readings = PumpReadings(amount = round2(amount), liters = round2(liters), unitPrice = round2(price))
                        consider(readings, scoreOf(amountRow, litersRow, null, hasPrice = false))
                    }
                }
            }
        }
        if (strict) {
            // 严格区间也允许返回（分数会偏向真实读数）；分数过低视为不可信。
            return best?.takeIf { bestScore >= 8 }
        }
        return best?.takeIf { bestScore >= 13 }
    }

    /** 候选读数打分：真实加油机读数通常金额 4–5 位、升数 3–4 位、单价 3 位且落在 5–10。 */
    private fun scoreOf(
        amountRow: RowValue,
        litersRow: RowValue,
        priceRow: RowValue?,
        hasPrice: Boolean,
    ): Int {
        var score = 0
        val amount = amountRow.value
        val liters = litersRow.value
        val price = priceRow?.value
        if (amount in 20.0..3000.0) score += 2
        if (liters in 8.0..150.0) score += 2
        if (price != null) {
            if (price in 5.0..10.0) score += 5
            if (price in 3.0..15.0) score += 2
            score += min(3, max(0, (priceRow?.text?.length ?: 0) - 2))
        }
        score += min(3, max(0, amountRow.text.length - 3))
        score += min(3, max(0, litersRow.text.length - 3))
        if (!hasPrice) score -= 1
        return score
    }

    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    // ------------------------------------------------------------ 连通域 ----

    /** 8 邻接连通域（行游程 + 并查集），返回 bbox 与像素数。 */
    private fun components(mask: BooleanArray, width: Int, height: Int): List<Component> {
        class Run(val start: Int, val end: Int, val label: Int)

        val parents = ArrayList<Int>(256).apply { add(0) }

        fun rootOf(x: Int): Int {
            var root = x
            while (parents[root] != root) root = parents[root]
            var current = x
            while (parents[current] != current) {
                val next = parents[current]
                parents[current] = root
                current = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = rootOf(a)
            val rb = rootOf(b)
            if (ra != rb) parents[max(ra, rb)] = min(ra, rb)
        }

        val runStart = IntArrayList(height * 2)
        val runEnd = IntArrayList(height * 2)
        val runLabel = IntArrayList(height * 2)
        val runY = IntArrayList(height * 2)

        var previous = ArrayList<Run>(16)
        for (y in 0 until height) {
            val current = ArrayList<Run>(16)
            val base = y * width
            var x = 0
            while (x < width) {
                if (!mask[base + x]) {
                    x++
                    continue
                }
                val start = x
                while (x + 1 < width && mask[base + x + 1]) x++
                val end = x
                var label = -1
                for (run in previous) {
                    if (run.start <= end && run.end >= start) {
                        if (label < 0) label = rootOf(run.label) else union(label, run.label)
                    }
                }
                if (label < 0) {
                    parents.add(parents.size)
                    label = parents.size - 1
                }
                current.add(Run(start, end, label))
                runStart.add(start)
                runEnd.add(end)
                runLabel.add(label)
                runY.add(y)
                x++
            }
            previous = current
        }
        if (runStart.size == 0) return emptyList()

        val rootToIndex = HashMap<Int, Int>()
        val result = ArrayList<Component>()
        for (index in 0 until runStart.size) {
            val root = rootOf(runLabel[index])
            val componentIndex = rootToIndex.getOrPut(root) {
                result.add(Component(runStart[index], runY[index], runEnd[index], runY[index], 0))
                result.size - 1
            }
            val component = result[componentIndex]
            if (runStart[index] < component.left) component.left = runStart[index]
            if (runEnd[index] > component.right) component.right = runEnd[index]
            if (runY[index] > component.bottom) component.bottom = runY[index]
            component.area += runEnd[index] - runStart[index] + 1
        }
        return result
    }

    /** 极简 int 数组（避免 ArrayList<Int> 装箱）。 */
    private class IntArrayList(capacity: Int) {
        private var data = IntArray(max(4, capacity))
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        operator fun get(index: Int): Int = data[index]
    }

    // ---------------------------------------------------------------- 工具 ----

    private fun pixelsCount(width: Int, height: Int): Int = width * height

    /** 分离式 box mean：先水平再垂直滑窗（窗口半径 [radius]，边界收缩）。 */
    private fun boxMean(lum: IntArray, width: Int, height: Int, radius: Int): IntArray {
        // 水平积分：每行求滑动窗口和。
        val horizontal = LongArray(lum.size)
        for (y in 0 until height) {
            val base = y * width
            var sum = 0L
            var left = 0
            var right = -1
            for (x in 0 until width) {
                val targetRight = min(width - 1, x + radius)
                while (right < targetRight) {
                    right++
                    sum += lum[base + right]
                }
                val targetLeft = max(0, x - radius)
                while (left < targetLeft) {
                    sum -= lum[base + left]
                    left++
                }
                horizontal[base + x] = sum
            }
        }
        // 垂直滑动窗口和。
        val result = IntArray(lum.size)
        for (x in 0 until width) {
            var sum = 0L
            var top = 0
            var bottom = -1
            for (y in 0 until height) {
                val targetBottom = min(height - 1, y + radius)
                while (bottom < targetBottom) {
                    bottom++
                    sum += horizontal[bottom * width + x]
                }
                val targetTop = max(0, y - radius)
                while (top < targetTop) {
                    sum -= horizontal[top * width + x]
                    top++
                }
                val count = (bottom - top + 1) * (min(width - 1, x + radius) - max(0, x - radius) + 1)
                result[y * width + x] = (sum / count).toInt()
            }
        }
        return result
    }

    private fun regionMean(
        mask: BooleanArray,
        stride: Int,
        rows: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Float {
        val xa = left.coerceIn(0, stride)
        var xb = right.coerceIn(0, stride)
        val ya = top.coerceIn(0, rows)
        var yb = bottom.coerceIn(0, rows)
        if (xb <= xa) xb = (xa + 1).coerceAtMost(stride)
        if (yb <= ya) yb = (ya + 1).coerceAtMost(rows)
        var ink = 0
        var count = 0
        for (y in ya until yb) {
            val base = y * stride
            for (x in xa until xb) {
                if (mask[base + x]) ink++
                count++
            }
        }
        return if (count == 0) 0f else ink.toFloat() / count
    }

    /** 七段：a=bit0 … g=bit6。 */
    private const val SEGMENT_COUNT = 7

    private val DIGIT_PATTERNS: IntArray = intArrayOf(
        maskOf("abcdef"), // 0
        maskOf("bc"), // 1
        maskOf("abdeg"), // 2
        maskOf("abcdg"), // 3
        maskOf("bcfg"), // 4
        maskOf("acdfg"), // 5
        maskOf("acdefg"), // 6
        maskOf("abc"), // 7
        maskOf("abcdefg"), // 8
        maskOf("abcdfg"), // 9
    )

    private val SEGMENT_REGIONS: Array<FloatArray> = arrayOf(
        floatArrayOf(0.14f, 0.00f, 0.86f, 0.13f), // a 上横
        floatArrayOf(0.80f, 0.06f, 1.00f, 0.45f), // b 右上竖
        floatArrayOf(0.80f, 0.55f, 1.00f, 0.94f), // c 右下竖
        floatArrayOf(0.14f, 0.87f, 0.86f, 1.00f), // d 下横
        floatArrayOf(0.00f, 0.55f, 0.20f, 0.94f), // e 左下竖
        floatArrayOf(0.00f, 0.06f, 0.20f, 0.45f), // f 左上竖
        floatArrayOf(0.14f, 0.43f, 0.86f, 0.57f), // g 中横
    )

    private const val SEGMENT_ON_THRESHOLD = 0.40f
    private const val MAX_HAMMING_DISTANCE = 2
    private val TYPICAL_PRICE_RANGE = 0.5..20.0

    private fun maskOf(segments: String): Int {
        var mask = 0
        for (c in segments) {
            mask = mask or when (c) {
                'a' -> 1 shl 0
                'b' -> 1 shl 1
                'c' -> 1 shl 2
                'd' -> 1 shl 3
                'e' -> 1 shl 4
                'f' -> 1 shl 5
                else -> 1 shl 6
            }
        }
        return mask
    }
}
