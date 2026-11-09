package com.milenote.android.domain.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一行二值掩膜（行内的位图切片）。
 *
 * 用于把「加油机屏幕某一行的墨迹」从图片预处理层传进纯几何解码器：
 * [mask] 里非 0 表示墨迹 / 点亮的段，0 表示背景。
 *
 * @property mask 长度必须为 [width] * [height]；长度不符时解码器返回 null 而不是抛异常。
 */
data class BinaryRow(
    val mask: IntArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryRow) return false
        return width == other.width && height == other.height && mask.contentEquals(other.mask)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + mask.contentHashCode()
}

/** 加油机屏幕读数（金额 / 升数 / 单价）。识别不到的字段为 null。 */
data class PumpReadings(
    val amount: Double? = null,
    val liters: Double? = null,
    val unitPrice: Double? = null,
)

/**
 * 七段数码管（加油机 LED/LCD 屏）纯几何解码器。
 *
 * Tesseract 这类 OCR 引擎按字形匹配，对七段数码管（同一个「8」由 7 个分离的矩形段拼成）
 * 结构性读不出，只能吐随机噪声。这里改用几何方式：把一行墨迹切成「数字单元」，
 * 在每个单元里按固定比例取 7 个矩形区域的平均墨迹量，判断哪几段点亮，
 * 再和 0–9 的标准段位做汉明距离匹配。
 *
 * 处理链路（全部无 Android / 无第三方依赖，可 JVM 单测）：
 * 1. [decodeLine]：一行的列切分 → 小数点判定 → 「1」这类窄字块右对齐 → 七段采样 → 数字；
 * 2. [decodeRows]：逐行解码，返回与输入等长（解不出的行为 null）；
 * 3. [assembleReadings]：把多行数值按「金额 / 升数 / 单价」各种排列试配，用
 *    `金额 ≈ 升数 × 单价` 做一致性校验，宁可返回 null 也不静默写错。
 *
 * 设计取向：所有异常输入（空行、退化尺寸、mask 长度不符、噪声块）一律返回 null，不抛异常。
 */
object SevenSegmentDecoder {

    /** 列分段的绝对最小「缝」：小数点、抗锯齿会让相邻数字之间只剩几列空白。 */
    private const val GAP_TOLERANCE = 3

    /** 列分段容差按行高自适应：同一数字内横段与竖段之间常有几像素直角缝。 */
    private const val SEGMENT_GAP_RATIO = 0.06f

    /** 一行墨迹的最小高度（像素）；再矮不可能是可读的七段数字。 */
    private const val MIN_INK_HEIGHT = 8

    /** 一行最多允许的单元（数字 + 小数点）数量，防止噪声把内存/时间吃光。 */
    private const val MAX_CELLS = 32

    /** 行内最多允许的字符数。 */
    private const val MAX_LINE_LENGTH = 16

    /**
     * 列墨迹高度 < 行高 25% 且靠底：判定为小数点（七段屏的小数点都画在基线上）。
     * 取 25% 而不是更宽松的值：段厚不同时，数字左右两半的竖段也只有约 1/3 行高，
     * 再高就会把 e/f 段误判成小数点、把数字切开。
     */
    private const val DOT_HEIGHT_RATIO = 0.25f

    /** 小数点墨水中心必须落在行高 55% 以下（基线上），用来把「7」顶部那条 a 段排除掉。 */
    private const val DOT_CENTER_RATIO = 0.55f

    /** 小数点的最大宽度（相对行高），超过这个宽度就不是小数点，而是噪声。 */
    private const val DOT_WIDTH_RATIO = 0.35f

    /** 字块宽度 < 中位字宽 60%：视为「1」这类只占右半边的数字，右对齐到标准字宽。 */
    private const val NARROW_DIGIT_RATIO = 0.6f

    /** 七段数字的典型宽高比（字宽 / 字高），仅在整行都识别不到标准字宽时兜底。 */
    private const val DIGIT_WIDTH_RATIO = 0.45f

    /** 数字单元内墨迹占比的最低要求，低于它只能是噪声块（空白块会被误判成「1」）。 */
    private const val MIN_INK_COVERAGE = 0.10f

    /** 采样区域平均墨迹比例 > 40% 视为该段点亮。 */
    private const val SEGMENT_ON_THRESHOLD = 0.40f

    /** 汉明距离超过 2 视为不可信；并列最优也放弃（宁可不识别）。 */
    private const val MAX_HAMMING_DISTANCE = 2

    /** 常见油价区间（元/L）：仅用于「升数 / 单价」角色消歧，不参与最终范围校验。 */
    private val TYPICAL_PRICE_RANGE = 0.5..20.0

    /** 段位：a=bit0 … g=bit6。 */
    private const val SEGMENT_COUNT = 7

    /** 0–9 的标准段位（含 0，即无段点亮）。下标即数字。 */
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

    /**
     * 七段采样区域（相对数字归一化框的比例）：左、上、右、下。
     * 顺序 a b c d e f g。
     */
    private val SEGMENT_REGIONS: Array<FloatArray> = arrayOf(
        floatArrayOf(0.14f, 0.00f, 0.86f, 0.13f), // a 上横
        floatArrayOf(0.80f, 0.06f, 1.00f, 0.45f), // b 右上竖
        floatArrayOf(0.80f, 0.55f, 1.00f, 0.94f), // c 右下竖
        floatArrayOf(0.14f, 0.87f, 0.86f, 1.00f), // d 下横
        floatArrayOf(0.00f, 0.55f, 0.20f, 0.94f), // e 左下竖
        floatArrayOf(0.00f, 0.06f, 0.20f, 0.45f), // f 左上竖
        floatArrayOf(0.14f, 0.43f, 0.86f, 0.57f), // g 中横
    )

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

    /**
     * 解码一行七段数字（可含小数点）。
     *
     * @param mask 长度必须为 [width] * [height]，非 0 = 墨迹。
     * @return 如 `228.78`；无法可靠解码（含任何一位拿不准）时返回 null。
     */
    fun decodeLine(mask: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (mask.size.toLong() != width.toLong() * height.toLong()) return null

        // 1) 行内墨迹紧边 + 每列的垂直墨迹范围。
        val colTop = IntArray(width) { Int.MAX_VALUE }
        val colBottom = IntArray(width) { -1 }
        var inkTop = height
        var inkBottom = -1
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x] == 0) continue
                if (y < inkTop) inkTop = y
                if (y > inkBottom) inkBottom = y
                if (y < colTop[x]) colTop[x] = y
                if (y > colBottom[x]) colBottom[x] = y
            }
        }
        if (inkBottom < 0) return null
        val inkHeight = inkBottom - inkTop + 1
        if (inkHeight < MIN_INK_HEIGHT) return null

        val dotMinHeight = DOT_HEIGHT_RATIO * inkHeight

        /** 该列是不是「小数点墨水」：矮（<35% 行高）且落在基线上。 */
        fun isDotColumn(x: Int): Boolean {
            if (colBottom[x] < 0) return false
            if (colBottom[x] - colTop[x] + 1 >= dotMinHeight) return false
            val center = (colTop[x] + colBottom[x]) / 2f
            return (center - inkTop) / inkHeight >= DOT_CENTER_RATIO
        }

        // 2) 列分段：连续墨迹列成段，允许与行高成比例的小缝（同一数字内横/竖段的直角缝）。
        //    小数点常把左右两个数字「桥接」成同一段，所以段内还要再按小数点切开。
        val gapTolerance = maxOf(GAP_TOLERANCE, (inkHeight * SEGMENT_GAP_RATIO).roundToInt())
        val segments = ArrayList<IntArray>(8)
        var segStart = -1
        var lastInk = -1
        for (x in 0 until width) {
            if (colBottom[x] < 0) continue
            if (segStart < 0) {
                segStart = x
            } else if (x - lastInk - 1 > gapTolerance) {
                segments.add(intArrayOf(segStart, lastInk))
                segStart = x
            }
            lastInk = x
        }
        if (segStart >= 0) segments.add(intArrayOf(segStart, lastInk))
        if (segments.isEmpty() || segments.size > MAX_CELLS) return null

        // 3) 段内按列高矮切成「数字单元」和「小数点单元」。
        val cells = ArrayList<IntArray>(8) // [startCol, endCol, isDot(0/1)]
        for (segment in segments) {
            val start = segment[0]
            val end = segment[1]
            var runStart = start
            var runIsDot = isDotColumn(start)
            for (x in (start + 1)..end) {
                val dot = isDotColumn(x)
                if (dot != runIsDot) {
                    cells.add(intArrayOf(runStart, x - 1, if (runIsDot) 1 else 0))
                    runStart = x
                    runIsDot = dot
                }
            }
            cells.add(intArrayOf(runStart, end, if (runIsDot) 1 else 0))
        }
        if (cells.isEmpty() || cells.size > MAX_CELLS) return null

        // 4) 每个单元的墨迹外接框（以及框内墨迹像素数，用于排除噪声块）。
        fun bounds(cell: IntArray): IntArray? {
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var ink = 0
            for (x in cell[0]..cell[1]) {
                if (colBottom[x] < 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (colTop[x] < minY) minY = colTop[x]
                if (colBottom[x] > maxY) maxY = colBottom[x]
                for (y in colTop[x]..colBottom[x]) {
                    if (mask[y * width + x] != 0) ink++
                }
            }
            return if (maxX < 0) null else intArrayOf(minX, maxX, minY, maxY, ink)
        }

        val cellBoxes = ArrayList<IntArray?>(cells.size)
        val digitWidths = ArrayList<Int>(cells.size)
        for (cell in cells) {
            val box = bounds(cell) ?: return null
            cellBoxes.add(box)
            val cellWidth = box[1] - box[0] + 1
            if (cell[2] == 1) {
                // 「矮而宽」的墨块不是小数点，多半是噪声/污渍：整行不识别。
                if (cellWidth > DOT_WIDTH_RATIO * inkHeight) return null
            } else {
                digitWidths.add(cellWidth)
            }
        }
        if (digitWidths.isEmpty()) return null
        digitWidths.sort()
        val medianWidth = digitWidths[digitWidths.size / 2]
        if (medianWidth < 3) return null
        // 整行只有「1」这类窄字（中位字宽异常小）时，用字高的经验比例兜底，
        // 否则窄字块归一化后所有采样区域都落在同一根竖条上，会被误判成「8」。
        val referenceWidth = maxOf(medianWidth, (inkHeight * DIGIT_WIDTH_RATIO).roundToInt())

        // 5) 逐个单元采样出数字 / 小数点。
        val out = StringBuilder(cells.size)
        for (index in cells.indices) {
            val cell = cells[index]
            val box = cellBoxes[index] ?: return null
            if (cell[2] == 1) {
                out.append('.')
                continue
            }
            val boxHeight = box[3] - box[2] + 1
            var x0 = box[0]
            val x1 = box[1]
            val cellWidth = x1 - x0 + 1
            // 噪声块：外接框里几乎没有墨，匹配出来的「1」不可信。
            if (box[4] < MIN_INK_COVERAGE * cellWidth * boxHeight) return null
            if (cellWidth < NARROW_DIGIT_RATIO * referenceWidth) {
                // 「1」在七段字体里只占右半边：数字单元右对齐到标准字宽。
                x0 = (x1 + 1 - referenceWidth).coerceIn(0, width - 1)
            }
            if (x0 > x1) return null
            val digit = decodeDigit(mask, width, height, x0, box[2], x1, box[3]) ?: return null
            out.append(digit)
        }

        if (out.isEmpty() || out.length > MAX_LINE_LENGTH) return null
        val text = out.toString()
        if (text.startsWith(".") || text.endsWith(".") || text.contains("..")) return null
        if (text.none { it in '0'..'9' }) return null
        return text
    }

    /** 逐行解码；返回与输入等长（解不出的行为 null）。 */
    fun decodeRows(rows: List<BinaryRow>): List<String?> = rows.map { row ->
        try {
            decodeLine(row.mask, row.width, row.height)
        } catch (e: RuntimeException) {
            // 解码器应当只返回 null；这里兜底，保证一行出问题不毁掉整屏。
            null
        }
    }

    /**
     * 从解码出的数字串（如 `["228.78", "31.47", "7.27"]`）组装读数。
     *
     * 加油机屏幕的三行顺序并不固定（有的机型金额在上、有的在中），因此按上→下顺序
     * 尝试「金额 / 升数 / 单价」的各种排列，用 `金额 ≈ 升数 × 单价` 做一致性校验；
     * 至少 3 行解码成功、且找到唯一合理排列才返回，否则 null。
     */
    fun assembleReadings(lines: List<String?>): PumpReadings? {
        val values = ArrayList<Double>(lines.size)
        for (line in lines) {
            val value = parseValue(line) ?: continue
            values.add(value)
        }
        if (values.size < 3) return null
        val limit = minOf(values.size, 6)
        // 升数与单价在「金额 = 升数 × 单价」里完全对称，光靠乘式无法区分；
        // 先用常见油价区间收一遍（与 FuelReceiptParser.parsePumpDisplay 的 0.5–20 元/L 一致），
        // 只有在整屏都不落在该区间时才退回宽松范围。
        for (preferTypicalPrice in booleanArrayOf(true, false)) {
            for (i in 0 until limit - 2) {
                for (j in i + 1 until limit - 1) {
                    for (k in j + 1 until limit) {
                        matchRoles(values[i], values[j], values[k], preferTypicalPrice)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** 把三个候选值按 6 种角色排列试配，返回第一个通过一致性校验的读数。 */
    private fun matchRoles(
        first: Double,
        second: Double,
        third: Double,
        preferTypicalPrice: Boolean,
    ): PumpReadings? {
        val values = doubleArrayOf(first, second, third)
        for (amountIndex in 0..2) {
            for (litersIndex in 0..2) {
                if (litersIndex == amountIndex) continue
                val priceIndex = 3 - amountIndex - litersIndex
                val readings = checked(
                    amount = values[amountIndex],
                    liters = values[litersIndex],
                    unitPrice = values[priceIndex],
                    preferTypicalPrice = preferTypicalPrice,
                )
                if (readings != null) return readings
            }
        }
        return null
    }

    /** 量级 + 乘式一致性校验；不通过返回 null。 */
    private fun checked(
        amount: Double,
        liters: Double,
        unitPrice: Double,
        preferTypicalPrice: Boolean,
    ): PumpReadings? {
        if (amount !in 1.0..100_000.0) return null
        if (liters !in 0.1..2000.0) return null
        if (unitPrice !in 0.1..100.0) return null
        if (preferTypicalPrice && unitPrice !in TYPICAL_PRICE_RANGE) return null
        val tolerance = maxOf(0.10, amount * 0.01)
        if (abs(amount - liters * unitPrice) > tolerance) return null
        return PumpReadings(amount = amount, liters = liters, unitPrice = unitPrice)
    }

    /** 解码出的一行 → 数值；空行、含杂字符、多小数点、非正数一律丢弃。 */
    private fun parseValue(line: String?): Double? {
        val text = line?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LINE_LENGTH) return null
        if (text.count { it == '.' } > 1) return null
        if (text.first() == '.' || text.last() == '.') return null
        if (text.any { (it !in '0'..'9') && it != '.' }) return null
        val value = text.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * 在归一化数字框里采样 7 段，按汉明距离匹配 0–9。
     *
     * @return 匹配到的数字；距离 >2 或并列多解返回 null。
     */
    private fun decodeDigit(
        mask: IntArray,
        stride: Int,
        rows: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Char? {
        val cellWidth = x1 - x0 + 1
        val cellHeight = y1 - y0 + 1
        if (cellWidth < 3 || cellHeight < MIN_INK_HEIGHT) return null
        var bits = 0
        for (index in 0 until SEGMENT_COUNT) {
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

    /** 矩形区域（右/下开区间）内墨迹像素的平均占比。 */
    private fun regionMean(
        mask: IntArray,
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
                if (mask[base + x] != 0) ink++
                count++
            }
        }
        return if (count == 0) 0f else ink.toFloat() / count
    }
}
