package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.MileNoteTheme
import com.milenote.android.ui.theme.moneyLarge
import com.milenote.android.ui.theme.moneyMedium
import com.milenote.android.ui.theme.moneySmall

/**
 * 金额字号档位：[LARGE] 英雄数字（总览主卡）、[MEDIUM] 指标/列表金额、[SMALL] 紧凑金额。
 */
enum class MoneySize { LARGE, MEDIUM, SMALL }

/**
 * 金额排版：`¥`（label 级、`onSurfaceVariant`）+ 数字（tabular figures）+ 单位（labelMedium，不与数字同字号）。
 *
 * 组件**不做业务格式化**：`amount` 传入已格式化的数字字符串（如 `1,234.56`），
 * 由 domain 层格式化保证与 Web `money()` 口径一致。
 *
 * ```kotlin
 * MoneyText(amount = "1,234.56", size = MoneySize.LARGE, unit = "元")
 * MoneyText(amount = "266.00", negative = true) // 负数自动加 - 前缀并使用 error 色
 * ```
 *
 * @param amount 已格式化的金额数字（不含 `¥` 与单位）。
 * @param size 字号档位。
 * @param unit 单位（元 / 元/升 / 元/km），显示在数字右侧 4dp 处。
 * @param showCurrencySymbol 是否显示 `¥`，默认 true。
 * @param negative 是否为负向金额（如退款/调整），自动使用 `-` 前缀与 `error` 色。
 * @param color 覆盖数字颜色；默认 `onSurface`，[negative] 时 `error`。
 */
@Composable
fun MoneyText(
    amount: String,
    modifier: Modifier = Modifier,
    size: MoneySize = MoneySize.MEDIUM,
    unit: String? = null,
    showCurrencySymbol: Boolean = true,
    negative: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val numberStyle = when (size) {
        MoneySize.LARGE -> typography.moneyLarge
        MoneySize.MEDIUM -> typography.moneyMedium
        MoneySize.SMALL -> typography.moneySmall
    }
    val symbolStyle = when (size) {
        MoneySize.LARGE -> typography.labelLarge
        MoneySize.MEDIUM -> typography.labelMedium
        MoneySize.SMALL -> typography.labelSmall
    }
    val numberColor = when {
        color != Color.Unspecified -> color
        negative -> scheme.error
        else -> scheme.onSurface
    }
    val symbolColor = if (numberColor == scheme.error) {
        scheme.error
    } else {
        scheme.onSurfaceVariant
    }
    val displayAmount = if (negative && !amount.startsWith("-")) "-$amount" else amount

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showCurrencySymbol) {
            Text(
                text = "¥",
                style = symbolStyle,
                color = symbolColor,
                modifier = Modifier.padding(end = 2.dp, bottom = 2.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = displayAmount,
            style = numberStyle,
            color = numberColor,
            maxLines = 1,
            softWrap = false,
        )
        if (!unit.isNullOrBlank()) {
            Text(
                text = unit,
                style = typography.labelMedium,
                color = symbolColor,
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MoneyTextPreview() {
    MileNoteTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MoneyText(amount = "10,727.22", size = MoneySize.LARGE, unit = "元")
            MoneyText(amount = "266.00", unit = "元")
            MoneyText(amount = "7.38", size = MoneySize.SMALL, unit = "升/百公里")
            MoneyText(amount = "32.71", negative = true, unit = "元")
        }
    }
}
