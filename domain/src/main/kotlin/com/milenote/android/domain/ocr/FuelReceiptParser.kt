package com.milenote.android.domain.ocr

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.i18n.tr
import java.text.Normalizer

/**
 * 加油小票 OCR 文本解析结果。
 *
 * 只返回能从文本中明确读到的字段；识别不到的用 null / 空串，并附 warnings。
 */
data class FuelReceipt(
    /** 实付金额（元）；无明确实付时回退订单金额并 warning。 */
    val amount: Double? = null,
    /** 加油量（L），1–2000。 */
    val liters: Double? = null,
    /** 油价（元/L）。 */
    val unitPrice: Double? = null,
    /** 应付金额（元）；无明确应付时为空。 */
    val payable: Double? = null,
    /** 优惠金额（元）。 */
    val discount: Double? = null,
    /** 支付日期 `YYYY-MM-DD`；多候选或非法时为空串。 */
    val date: String = "",
    /** 明确标注的总里程（km）。 */
    val odometer: Double? = null,
    /** 需要提示用户核对的文案。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 加油小票纯文本解析。
 *
 * 关键点：OCR 引擎的阅读顺序并不等于小票排版。常见三种输出：
 * 1. 同一行「标签 值」；
 * 2. 标签和值被拆成同一水平线的两个文字块（需要按 y 坐标合并成行）；
 * 3. 整列标签在上、整列数值在下（如 OCR2，需要按列顺序配对）。
 *
 * 因此优先用 [OcrTextLine] 的坐标把文字块还原成“视觉行”，再做逐行解析；
 * 只有逐行失败时，才在“标签块整体位于数值块上方”的明确列布局下做顺序配对。
 */
object FuelReceiptParser {

    private val NUMERIC = Regex("[:：]?[^\\d\\n]{0,4}(\\d[\\d,]*(?:\\.\\d{1,2})?)(?![\\d.])")
    private val DATE_PATTERN = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?")
    private val PAID_DATE_LABEL = Regex("支付时间|支付日期|付款时间|付款日期|交易时间|交易日期")
    private val LITERS_UNIT = Regex(
        // 油量单位：L 常被 OCR 读成「上」（31.47 L → 31.47 上），一并接受。
        "(\\d+(?:\\.\\d{1,3})?)\\s*(?:L|升|公升|上)(?![\\dA-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    private val PAID_LABEL = Regex(
        "^.*?(?:实付金额|实付金額|实付全额|实付金领|实际支付|实际付款|实付款|实付|支付金额|付款金额)[:：]?",
    )
    private val TOTAL_LABEL = Regex("^.*?(?:订单金额|订单金額|合计金额|总金额|应付金额|应付金額|合计)[:：]?")
    private val LITERS_LABEL = Regex("^.*?(?:加油量|加油升数|油量|升数|升數|升效|数量)[:：]?")
    private val UNIT_PRICE_LABEL = Regex(
        // 「元/L」的「元」常被 OCR 读成 T（7.27 T/L），两种都认。
        "(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|T|τ)?\\s*/\\s*L",
        RegexOption.IGNORE_CASE,
    )
    private val PAYABLE_LABEL = Regex("^.*?(?:应付金额|应付金額|订单金额|订单金額|订单全额|合计金额|总金额)[:：]?")
    private val DISCOUNT_LABEL = Regex("^.*?(?:优惠金额|优惠金額|优惠|折扣)[:：]?")
    private val ODOMETER_LABEL = Regex("^.*?(?:总里程|当前里程|表显里程)[:：]?")
    private val ODOMETER_VALUE = Regex("(\\d[\\d,]*(?:\\.\\d)?)")

    private val OIL_AMOUNT_LABEL = Regex("油品金额|油品金額|油品费用")
    private val AMOUNT_LABEL_ANY = Regex(
        "实付金额|实付金額|实付全额|实付金领|实际支付|实际付款|实付款|实付|应付金额|应付金額|" +
            "订单金额|订单金額|合计金额|总金额|支付金额|付款金额|可开票金额|开票金额|油品金额|油品金額",
    )
    private val AMOUNT_VALUE_LINE = Regex("[¥￥]\\s*\\d+(?:[.,]\\d{1,2})?")
    private val CURRENCY_NUMBER = Regex("[¥￥]\\s*(\\d+(?:[.,]\\d{1,2})?)")
    private val ORDER_OR_ID_LINE = Regex("订单编号|订单号|编号|流水号|交易号|复制|电话|车牌")
    private val PURE_ID_LIKE = Regex("^\\d*[A-Za-z]\\d+[A-Za-z\\d]*$")

    /** 仅文本（旧调用 / 手工编辑后的重新解析）。 */
    fun parse(text: String): FuelReceipt = parse(text, emptyList())

    /**
     * 解析 OCR 结果。
     *
     * @param text 原始纯文本（用于展示/编辑）
     * @param ocrLines OCR 引擎带坐标的文字行；为空时退化为逐行解析。
     */
    fun parse(text: String, ocrLines: List<OcrTextLine>): FuelReceipt {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sourceLines = buildSourceLines(normalized, ocrLines)
        if (sourceLines.isEmpty()) {
            return FuelReceipt(warnings = listOf(tr("未识别到文字，请重拍或手动填写", "No text recognized — retake the photo or enter it manually")))
        }
        val rows = buildVisualRows(sourceLines)
        return parseRows(rows, sourceLines)
    }

    private fun buildSourceLines(text: String, ocrLines: List<OcrTextLine>): List<OcrTextLine> {
        if (ocrLines.isNotEmpty()) {
            return ocrLines.mapNotNull { line ->
                val cleaned = cleanText(line.text)
                if (cleaned.isEmpty()) null else line.copy(text = cleaned)
            }
        }
        return text.split(Regex("\\r?\\n"))
            .map { cleanText(it) }
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line -> OcrTextLine(text = line, top = index * 100, bottom = index * 100 + 60) }
    }

    /** 同一水平线上的文字块合并成“视觉行”，按 y、x 排序。 */
    private fun buildVisualRows(lines: List<OcrTextLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = mutableListOf<MutableList<OcrTextLine>>()
        for (line in sorted) {
            val target = rows.firstOrNull { row ->
                val top = row.minOf { it.top }
                val bottom = row.maxOf { it.bottom }
                val overlap = minOf(bottom, line.bottom) - maxOf(top, line.top)
                val minHeight = minOf(bottom - top, line.height)
                overlap > 0 && (overlap.toFloat() / minHeight >= 0.55f ||
                    kotlin.math.abs(row.first().centerY - line.centerY) <= minHeight * 0.55f)
            }
            if (target != null) target.add(line) else rows.add(mutableListOf(line))
        }
        return rows
            .sortedBy { row -> row.minOf { it.top } }
            .map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
    }

    private fun parseRows(rows: List<String>, sourceLines: List<OcrTextLine>): FuelReceipt {
        val lines = rows.map { it.replace(Regex("[ \\t\\u3000]"), "") }.filter { it.isNotEmpty() }
        val warnings = mutableListOf<String>()
        val compactAll = lines.joinToString("")
        val isPumpDisplay = compactAll.contains("提枪加油") ||
            (compactAll.contains("油量") && compactAll.contains("单价"))

        fun values(label: Regex, value: Regex): List<Double> {
            val anchored = Regex("^${value.pattern}")
            val out = mutableListOf<Double>()
            lines.forEachIndexed { index, line ->
                if (!label.containsMatchIn(line)) return@forEachIndexed
                val after = label.replaceFirst(line, "")
                var match = anchored.find(after)
                if (match == null && after.none { it.isDigit() }) {
                    val nextLine = lines.getOrNull(index + 1)
                    // 下一行若是别的标签或“¥8.83元/Lx27.98L”这类油品单价行，不能当成实付金额。
                    if (nextLine != null &&
                        !AMOUNT_LABEL_ANY.containsMatchIn(nextLine) &&
                        !LITERS_UNIT.containsMatchIn(nextLine)
                    ) {
                        match = anchored.find(nextLine)
                    }
                }
                if (match != null) {
                    val v = match.groupValues[1].replace(",", "").toDoubleOrNull()
                    if (v != null && v.isFinite()) out.add(v)
                }
            }
            return out
        }

        /** 从「金额 / 油量 / 单价」标签取数：同一行没有数字就取下一行。 */
        fun pumpNumberAfterLabel(keyword: String): Double? {
            lines.forEachIndexed { index, line ->
                if (!line.contains(keyword)) return@forEachIndexed
                if (keyword == "金额" && (line.contains("优惠") || line.contains("单价"))) return@forEachIndexed
                val after = line.substringAfter(keyword).trimStart(':', '：')
                val anchored = Regex("^${NUMERIC.pattern}")
                anchored.find(after)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                    ?.let { return it }
                lines.getOrNull(index + 1)?.let { next ->
                    anchored.find(next)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                        ?.let { return it }
                }
            }
            return null
        }

        fun pick(candidates: List<Double>, label: String): Double? {
            val unique = candidates.distinct()
            if (unique.size > 1) warnings.add(tr("%s有多个候选值，请对照原图填写", "Multiple candidate values for %s — check against the original photo", label))
            return if (unique.size == 1) unique[0] else null
        }

        // 1) 行列配对兜底：只对“标签整体在数值整体上方”的列布局启用。
        val columns = pairLabelColumns(sourceLines)

        var amount = pick(values(PAID_LABEL, NUMERIC), tr("实付金额", "Paid amount"))
        if (amount == null && columns.paid != null) {
            amount = columns.paid
        }
        if (amount == null) {
            amount = pick(values(TOTAL_LABEL, NUMERIC), tr("订单金额", "Order amount"))
            if (amount == null) amount = columns.total
            if (amount != null) {
                warnings.add(
                    tr(
                        "未找到明确实付金额，暂用应付/订单金额，请核对优惠和实际付款",
                        "No explicit paid amount found — using payable/order amount, check the discount and actual payment",
                    ),
                )
            }
        }

        if (isPumpDisplay && amount == null) {
            amount = pumpNumberAfterLabel("金额")?.takeIf { it > 0.0 && it < 100_000.0 }
        }

        // 2) 加油量：标签优先；没有标签就从油品金额行 / 含单位的行里取。
        val litersCandidates = values(LITERS_LABEL, NUMERIC).toMutableList()
        if (litersCandidates.isEmpty()) {
            collectLitersFromFallback(lines, litersCandidates)
        }
        if (litersCandidates.isEmpty()) {
            columns.oilLine?.let { oilLine ->
                LITERS_UNIT.find(oilLine)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.let { litersCandidates.add(it) }
            }
        }
        if (litersCandidates.isEmpty()) {
            val compact = lines.joinToString("")
            LITERS_UNIT.findAll(compact).forEach { m ->
                m.groupValues[1].toDoubleOrNull()?.let { litersCandidates.add(it) }
            }
        }
        if (isPumpDisplay && litersCandidates.isEmpty()) {
            pumpNumberAfterLabel("油量")?.takeIf { it in 0.1..2000.0 }?.let { litersCandidates.add(it) }
        }
        val liters = pick(litersCandidates.filter { it > 0 && it <= 2000 }, tr("加油量", "Fuel amount"))

        // 3) 日期：优先支付/交易时间；否则全文唯一日期。
        fun validDate(line: String): String? {
            val m = DATE_PATTERN.find(line) ?: return null
            val value = "%s-%s-%s".format(
                m.groupValues[1],
                m.groupValues[2].padStart(2, '0'),
                m.groupValues[3].padStart(2, '0'),
            )
            return if (Dates.isValid(value)) value else null
        }

        val paidDates = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (!PAID_DATE_LABEL.containsMatchIn(line)) return@forEachIndexed
            val value = validDate(line) ?: lines.getOrNull(index + 1)?.let { validDate(it) }
            if (value != null) paidDates.add(value)
        }
        val dates = (if (paidDates.isNotEmpty()) paidDates else lines.mapNotNull { validDate(it) }).distinct()
        val date = if (dates.size == 1) dates[0] else ""
        if (dates.size > 1) warnings.add(tr("识别到多个日期，请选择实际支付日期", "Multiple dates detected — choose the actual payment date"))

        val unitPrice = pick(
            rows.mapNotNull { line ->
                UNIT_PRICE_LABEL.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.takeIf { it > 0.1 && it < 100.0 }
            },
            tr("油价", "Unit price"),
        ) ?: if (isPumpDisplay) {
            pumpNumberAfterLabel("单价")?.takeIf { it > 0.1 && it < 100.0 }
        } else {
            null
        }
        val payable = pick(values(PAYABLE_LABEL, NUMERIC), tr("应付金额", "Payable amount"))
            ?.takeIf { it > 0.0 && it < 100_000.0 }
            ?: columns.total
        val discountLabelValue = pick(values(DISCOUNT_LABEL, NUMERIC), tr("优惠金额", "Discount"))
            ?.takeIf { it >= 0.0 && it < 100_000.0 }
        // 应付 - 实付 = 优惠；OCR 把“优惠金额”标签和数值拆开时用这个关系补全。
        val discount = discountLabelValue ?: if (
            payable != null && amount != null && payable >= amount
        ) {
            (payable - amount).takeIf { it >= 0.0 }
        } else {
            null
        }

        val odometer = pick(values(ODOMETER_LABEL, ODOMETER_VALUE), tr("总里程", "Odometer"))

        // 加油机屏幕的金额与升数是同一屏的成对读数：金额都没读出来时，单独的「升数」基本是噪声
        // （实测 Tesseract 会把屏幕杂讯读成 1 L）。宁可留空让用户手填，也不要静默写错一笔加油量。
        val trustedLiters = if (isPumpDisplay && amount == null) null else liters
        // 单独一个单价对回填没有意义：金额和升数都不可信时，单价多半是屏幕噪声（如 7t/L）误命中。
        val trustedUnitPrice = if (amount == null && trustedLiters == null) null else unitPrice

        if (amount == null || amount <= 0.0) warnings.add(tr("未确定实付金额，请手动核对", "Paid amount not detected — check manually"))
        if (trustedLiters == null) warnings.add(tr("未确定加油量，请手动核对", "Fuel amount not detected — check manually"))
        if (date.isEmpty()) warnings.add(tr("未确定支付日期，请手动核对", "Payment date not detected — check manually"))

        return FuelReceipt(
            amount = amount?.takeIf { it > 0.0 && it < 100_000.0 },
            liters = trustedLiters,
            unitPrice = trustedUnitPrice,
            payable = payable,
            discount = discount,
            date = date,
            odometer = odometer,
            warnings = warnings.toList(),
        )
    }

    /**
     * 从全部行里找升数，排除订单号/车牌等噪声（如 `1l69`），
     * 并优先取「带 ×/x 或 ¥ 单价」的行。
     */
    private fun collectLitersFromFallback(lines: List<String>, out: MutableList<Double>) {
        val scored = mutableListOf<Pair<Int, Double>>()
        for (line in lines) {
            if (ORDER_OR_ID_LINE.containsMatchIn(line)) continue
            if (PURE_ID_LIKE.matches(line)) continue
            val matches = LITERS_UNIT.findAll(line).toList()
            if (matches.isEmpty()) continue
            val score = when {
                Regex("[x×X]").containsMatchIn(line) && AMOUNT_VALUE_LINE.containsMatchIn(line) -> 3
                AMOUNT_VALUE_LINE.containsMatchIn(line) -> 2
                else -> 1
            }
            for (m in matches) {
                val value = m.groupValues[1].toDoubleOrNull() ?: continue
                if (value in 0.1..2000.0) scored.add(score to value)
            }
        }
        if (scored.isEmpty()) return
        val bestScore = scored.maxOf { it.first }
        // 同一优先级出现多个不同值：保留多个，交给 pick() 提示用户核对。
        scored.filter { it.first == bestScore }.mapTo(out) { it.second }
    }

    private data class ColumnValues(
        val paid: Double? = null,
        val total: Double? = null,
        val oilLine: String? = null,
    )

    /**
     * 处理 OCR2 这类「左列标签 / 右列数值」：
     * 只有所有金额标签都位于第一个金额数值上方时，才按 y 顺序做标签↔数值配对。
     */
    private fun pairLabelColumns(sourceLines: List<OcrTextLine>): ColumnValues {
        val labels = sourceLines.mapNotNull { line ->
            val text = cleanText(line.text)
            val match = AMOUNT_LABEL_ANY.find(text) ?: return@mapNotNull null
            if (text.any { it.isDigit() } || AMOUNT_VALUE_LINE.containsMatchIn(text)) return@mapNotNull null
            val key = when {
                match.value.contains("实付") -> "paid"
                match.value.contains("应付") || match.value.contains("订单") ||
                    match.value.contains("合计") || match.value.contains("总金额") -> "total"
                OIL_AMOUNT_LABEL.containsMatchIn(match.value) -> "oil"
                match.value.contains("可开票") || match.value.contains("开票") -> "invoice"
                else -> "paid"
            }
            Triple(line.top, key, text)
        }
        val values = sourceLines.mapNotNull { line ->
            val text = cleanText(line.text)
            val number = CURRENCY_NUMBER.find(text)?.groupValues?.get(1)
                ?.replace(",", "")?.toDoubleOrNull() ?: return@mapNotNull null
            Triple(line.top, number, text)
        }.sortedBy { it.first }
        if (labels.size < 2 || values.size < labels.size) return ColumnValues()
        val sortedLabels = labels.sortedBy { it.first }
        val firstValueTop = values.first().first
        // 必须是“标签块整体在数值块上方”的明确列布局；交错小票交给逐行解析。
        if (sortedLabels.any { it.first >= firstValueTop }) return ColumnValues()
        val paired = sortedLabels.mapIndexedNotNull { index, label ->
            values.getOrNull(index)?.let { label.second to it }
        }
        var paid: Double? = null
        var total: Double? = null
        var oilLine: String? = null
        paired.forEach { (label, value) ->
            when (label) {
                "paid" -> paid = value.second
                "total" -> total = value.second
                "oil" -> oilLine = value.third
            }
        }
        return ColumnValues(
            paid = paid?.takeIf { it in 0.0..100_000.0 },
            total = total?.takeIf { it in 0.0..100_000.0 },
            oilLine = oilLine,
        )
    }

    private fun cleanText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()

    /**
     * OCR 完成后做金额校验：
     * - 加油机默认无优惠：应付 = 实付，优惠 = 0；
     * - 普通小票：应付 − 优惠 = 实付，油量 × 油价 ≈ 应付；
     * - 缺一个字段时用另外两个补齐；不一致时保留原值并加 warning，交给用户核对。
     */
    fun reconcile(receipt: FuelReceipt, pumpDisplay: Boolean): FuelReceipt {
        var amount = receipt.amount
        var payable = receipt.payable
        var discount = receipt.discount
        var unitPrice = receipt.unitPrice
        val warnings = receipt.warnings.toMutableList()

        fun warn(message: String) {
            if (warnings.none { it == message }) warnings.add(message)
        }

        if (pumpDisplay) {
            val paid = amount ?: payable
            if (paid != null) {
                amount = paid
                if (payable != null && kotlin.math.abs(payable - paid) > 0.01) {
                    warn(
                        tr(
                            "加油机默认无优惠，应付金额已按实付 ¥%s 修正",
                            "Pump display has no discount — payable adjusted to paid ¥%s",
                            moneyText(paid),
                        ),
                    )
                }
                payable = paid
                if (discount != null && discount > 0.01) {
                    warn(tr("加油机默认无优惠，已忽略优惠金额", "Pump display has no discount — discount ignored"))
                }
                discount = 0.0
            }
        }

        when {
            amount != null && discount != null -> {
                val expected = amount + discount
                if (payable == null || kotlin.math.abs(payable - expected) > 0.01) {
                    if (payable != null) {
                        warn(
                            tr(
                                "校验修正：应付金额已按「实付 + 优惠」调整为 ¥%s",
                                "Check fix: payable adjusted to paid + discount, ¥%s",
                                moneyText(expected),
                            ),
                        )
                    }
                    payable = expected
                }
            }
            amount != null && payable != null -> {
                val diff = payable - amount
                if (diff >= -0.01) {
                    discount = diff.coerceAtLeast(0.0)
                } else {
                    warn(tr("校验修正：应付金额低于实付，已按实付重算", "Check fix: payable was below paid — recalculated from paid amount"))
                    payable = amount + (discount ?: 0.0)
                }
            }
            payable != null && discount != null -> {
                amount = (payable - discount).takeIf { it >= 0.0 }
            }
            pumpDisplay && amount != null -> {
                payable = amount
                if (discount == null) discount = 0.0
            }
        }

        val liters = receipt.liters
        if (liters != null && unitPrice == null && payable != null) {
            unitPrice = (payable / liters).takeIf { it in 0.1..100.0 }
        }
        if (liters != null && unitPrice != null && payable != null) {
            val calculated = liters * unitPrice
            val tolerance = maxOf(0.10, payable * 0.005)
            if (kotlin.math.abs(calculated - payable) > tolerance) {
                warn(
                    tr(
                        "校验未通过：%s升 × %s元/升 ≈ ¥%s，与应付 ¥%s 不一致，请核对",
                        "Check failed: %s L × %s CNY/L ≈ ¥%s, does not match payable ¥%s — please verify",
                        decimalText(liters),
                        moneyText(unitPrice),
                        moneyText(calculated),
                        moneyText(payable),
                    ),
                )
            }
        }
        return receipt.copy(
            amount = amount,
            payable = payable,
            discount = discount,
            unitPrice = unitPrice,
            warnings = warnings.distinct(),
        )
    }

    private fun moneyText(value: Double): String = String.format(java.util.Locale.CHINA, "%.2f", value)

    private fun decimalText(value: Double): String =
        if (value % 1.0 == 0.0) String.format(java.util.Locale.CHINA, "%.0f", value)
        else String.format(java.util.Locale.CHINA, "%.2f", value)

    /**
     * 加油机 LED/LCD 屏幕兜底解析。
     *
     * OCR 引擎对七段数码管常把 `228.78` 读成 `22878`、`31.47` 读成 `3L47`；
     * 这里汇总所有 OCR 变体的数字行，按“金额 / 油量”顺序还原小数点，并用金额÷油量补出单价。
     * 仅在 [texts] 明确包含加油机标记（提枪加油 / 油量 + 单价）时启用。
     */
    fun parsePumpDisplay(texts: List<String>): FuelReceipt? {
        val joined = texts.joinToString("\n")
        if (!joined.contains("提枪加油") &&
            !(joined.contains("油量") && joined.contains("单价"))
        ) {
            return null
        }
        val tokens = mutableListOf<String>()
        for (text in texts) {
            for (line in text.split(Regex("\\r?\\n"))) {
                val compact = line.replace(" ", "").replace(",", "").replace("，", "")
                // 保留数字与可能被 OCR 成 L/I/O 的位；至少 3 个字符。
                val match = Regex("[0-9OoIlL.]+").find(compact) ?: continue
                val token = match.value
                if (token.count { it.isDigit() || it in "OoIlL" } >= 3) tokens.add(token)
            }
        }
        if (tokens.isEmpty()) return null

        fun normalizeDigits(token: String): String =
            token.replace('O', '0').replace('o', '0')
                .replace('I', '1').replace('l', '1').replace('L', '1')

        fun number(token: String): Double? = normalizeDigits(token)
            .replace(".", "")
            .toDoubleOrNull()

        val amountCandidates = mutableListOf<Double>()
        val literCandidates = mutableListOf<Double>()
        for (token in tokens) {
            val normalized = normalizeDigits(token)
            val value = normalized.toDoubleOrNull() ?: continue
            val hasLetter = token.any { it in "OoIlL" }
            val digits = normalized.count { it.isDigit() }
            when {
                !hasLetter && !token.contains('.') && digits in 4..8 && value >= 1000 -> {
                    amountCandidates.add(value / 100.0)
                }
                token.contains('.') && value in 10.0..100_000.0 -> amountCandidates.add(value)
                hasLetter && digits in 3..6 -> {
                    val liters = if (value >= 100) value / 100.0 else value
                    if (liters in 1.0..200.0) literCandidates.add(liters)
                }
                digits in 3..6 && value in 100.0..20_000.0 -> {
                    // 无 L 的“3147”也按 31.47 处理；仅作为油量候选，避免把金额重复算入。
                    val liters = value / 100.0
                    if (liters in 1.0..200.0) literCandidates.add(liters)
                }
            }
        }

        // 跨 OCR 变体复现至少两次才算可信：ML Kit 时代的七段读数会在多个变体里重复出现；
        // Tesseract 读不出数字时只会吐出随机噪声，单次出现的 token 直接丢掉，避免「编」出金额/升数。
        fun mostFrequent(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val mode = values.groupBy { String.format(java.util.Locale.US, "%.2f", it) }
                .maxByOrNull { it.value.size } ?: return null
            return mode.value.first().takeIf { mode.value.size >= 2 }
        }

        val amount = mostFrequent(amountCandidates) ?: return null
        var liters = mostFrequent(literCandidates) ?: return null
        // 金额与油量量级明显不合理时不做兜底，避免把订单号当读数。
        if (amount !in 1.0..100_000.0 || liters !in 1.0..200.0) return null
        // 金额 ÷ 升数应落在合理油价区间（本币/升，覆盖常见市场 0.5–20）；
        // 噪声凑出来的「35.46 ÷ 1.11 = 31.95」会在这里被拒掉，宁可不填也不静默写错值。
        val unitPrice = (amount / liters).takeIf { it in 0.5..20.0 } ?: return null
        return FuelReceipt(
            amount = amount,
            liters = liters,
            unitPrice = unitPrice,
            warnings = listOf(
                tr(
                    "已按加油机读数自动换算，请核对金额与升数",
                    "Auto-converted from the pump display — verify the amount and fuel volume",
                ),
            ),
        )
    }
}
