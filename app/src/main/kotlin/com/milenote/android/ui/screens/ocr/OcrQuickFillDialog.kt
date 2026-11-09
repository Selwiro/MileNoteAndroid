package com.milenote.android.ui.screens.ocr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.milenote.android.domain.ocr.FuelReceipt
import com.milenote.android.domain.ocr.FuelReceiptParser
import com.milenote.android.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 从 OCR 原文里挑出可点的数字候选（金额 / 升数 / 油价的快速输入来源）。
 *
 * 只做「看起来像读数」的筛选，不猜语义：去重、限制数量，顺序保持原文出现顺序。
 */
internal fun numericCandidates(rawText: String, limit: Int = 9): List<String> {
    val pattern = Regex("""\d+(?:[.,]\d{1,3})?""")
    val out = LinkedHashSet<String>()
    for (match in pattern.findAll(rawText)) {
        val normalized = match.value.replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: continue
        // 过滤明显不是读数的碎片：单页 OCR 文本里的 1、3 这类小数字（升数/金额/油价的
        // 实际量级都 ≥5，汽车加油场景不会出现更小的数）。
        if (value < 5.0 || value > 100_000.0) continue
        out.add(normalized)
        if (out.size >= limit) break
    }
    return out.toList()
}

/** 解析用户输入：容忍逗号小数点、忽略空串与非法值。 */
internal fun parseDecimal(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it <= 100_000.0 }

/** 输入框回显：去掉无意义的小数尾零。 */
private fun formatInput(value: Double?): String {
    if (value == null || value <= 0.0) return ""
    val text = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    return text.ifEmpty { "0" }
}

/** 金额/升数/油价的合理区间（宽松，只挡明显离谱的输入）。 */
private val AMOUNT_RANGE = 0.01..100_000.0
private val LITERS_RANGE = 0.01..2_000.0
private val UNIT_PRICE_RANGE = 0.1..100.0

/**
 * 用「手动填写」的三格输入组装 [FuelReceipt]：
 * - 只填一项也能用（其余字段留空，由调用方提示）；
 * - 填两项时用第三项的关系补齐（金额 = 升数 × 油价）；
 * - 金额与「升数 × 油价」明显不一致时保留原值并加校验警告，交给用户核对。
 *
 * @return 三项都为空时返回 null。
 */
internal fun buildQuickFillReceipt(
    initial: FuelReceipt?,
    amountText: String,
    litersText: String,
    unitPriceText: String,
    pumpDisplay: Boolean = false,
): FuelReceipt? {
    var amount = parseDecimal(amountText)?.takeIf { it in AMOUNT_RANGE }
    var liters = parseDecimal(litersText)?.takeIf { it in LITERS_RANGE }
    var unitPrice = parseDecimal(unitPriceText)?.takeIf { it in UNIT_PRICE_RANGE }
    if (amount == null && liters == null && unitPrice == null) return null

    if (amount == null && liters != null && unitPrice != null) {
        amount = (liters * unitPrice).takeIf { it in AMOUNT_RANGE }
    }
    if (liters == null && amount != null && unitPrice != null && unitPrice > 0.0) {
        liters = (amount / unitPrice).takeIf { it in LITERS_RANGE }
    }
    if (unitPrice == null && amount != null && liters != null && liters > 0.0) {
        unitPrice = (amount / liters).takeIf { it in UNIT_PRICE_RANGE }
    }

    val gateway = initial?.payable?.takeIf { it > 0.0 } ?: amount
    val base = FuelReceipt(
        amount = amount,
        liters = liters,
        unitPrice = unitPrice,
        payable = gateway,
        discount = initial?.discount,
        date = initial?.date.orEmpty(),
        odometer = initial?.odometer,
        warnings = emptyList(),
    )
    val reconciled = FuelReceiptParser.reconcile(base, pumpDisplay)
    val manualWarning = tr(
        "手动填写，请核对金额与升数",
        "Manually entered — verify the amount and fuel volume",
    )
    return reconciled.copy(warnings = (reconciled.warnings + manualWarning).distinct())
}

/**
 * 「手动填写 / 修改读数」弹窗（OCR 识别不出时的兜底入口）。
 *
 * 三格数字键盘输入 + 原文数字候选 + 实时一致性校验；应用后回填结果面板，不直接提交。
 */
@Composable
internal fun OcrQuickFillDialog(
    initial: FuelReceipt?,
    rawText: String,
    pumpDisplay: Boolean = false,
    onDismiss: () -> Unit,
    onApply: (FuelReceipt) -> Unit,
) {
    var amountText by remember(initial) { mutableStateOf(formatInput(initial?.amount)) }
    var litersText by remember(initial) { mutableStateOf(formatInput(initial?.liters)) }
    var unitPriceText by remember(initial) { mutableStateOf(formatInput(initial?.unitPrice)) }
    val candidates = remember(rawText) { numericCandidates(rawText) }

    val amount = parseDecimal(amountText)
    val liters = parseDecimal(litersText)
    val unitPrice = parseDecimal(unitPriceText)

    val hint = when {
        amount != null && liters != null && unitPrice != null -> {
            val diff = abs(amount - liters * unitPrice)
            if (diff <= max(0.10, amount * 0.01)) {
                tr("金额 ≈ 升数 × 油价，校验通过", "Amount ≈ fuel × unit price — OK")
            } else {
                tr(
                    "金额与「升数 × 油价」相差 %s，请核对",
                    "Amount differs from fuel × unit price by %s — please verify",
                    String.format(Locale.US, "%.2f", diff),
                )
            }
        }
        amount != null && liters != null ->
            tr("将按 金额 ÷ 升数 补出油价", "Unit price will be derived from amount ÷ fuel volume")
        amount != null && unitPrice != null ->
            tr("将按 金额 ÷ 油价 补出升数", "Fuel volume will be derived from amount ÷ unit price")
        liters != null && unitPrice != null ->
            tr("将按 升数 × 油价 补出金额", "Amount will be derived from fuel × unit price")
        else -> tr("至少填写一项：金额 / 升数 / 油价", "Fill at least one: amount / fuel volume / unit price")
    }

    fun fillNext(value: String) {
        when {
            amountText.isBlank() -> amountText = value
            litersText.isBlank() -> litersText = value
            unitPriceText.isBlank() -> unitPriceText = value
            else -> amountText = value
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("手动填写读数", "Fill in the readings"), style = MaterialTheme.typography.titleLarge)
                Text(
                    tr(
                        "识别不出时直接输入金额、升数或油价；填两项会自动补出第三项。",
                        "Type the amount, fuel volume or unit price — fill two and the third is derived.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (candidates.isNotEmpty()) {
                    Text(
                        tr("原文里的数字（点选填入空的输入框）", "Numbers found in the text (tap to fill an empty field)"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        candidates.forEach { candidate ->
                            AssistChip(
                                onClick = { fillNext(candidate) },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("实付金额", "Amount paid")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = litersText,
                    onValueChange = { litersText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("加油量", "Fuel amount")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = unitPriceText,
                    onValueChange = { unitPriceText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("油价", "Unit price")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(tr("取消", "Cancel"))
                    }
                    Button(
                        onClick = {
                            buildQuickFillReceipt(initial, amountText, litersText, unitPriceText, pumpDisplay)
                                ?.let(onApply)
                        },
                        enabled = amount != null || liters != null || unitPrice != null,
                    ) {
                        Text(tr("应用", "Apply"))
                    }
                }
            }
        }
    }
}
