package com.milenote.android.domain.calc

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** 生成记录 / 提醒 / 车辆 id，对应 Web `uid()`。 */
object Ids {
    /** 等价于 Web `crypto.randomUUID()`。 */
    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * 数值解析与 JS 风格数字格式化。
 *
 * Web 的 `number(v)` 会先 `String(v)` 再 `Number(...)`，这里保持同样语义，
 * 让 Excel 单元格（Double/Boolean/String）与导入 CSV 走同一条路径。
 */
object Numbers {
    /** 货币符号与空白：无条件剔除（不进数字语义）。 */
    private val STRIP = Regex("[¥￥\\s]")

    /** 千分位逗号（半角 / 全角）；只有满足 [isThousandsSeparator] 时才剔除。 */
    private const val COMMA = ','
    private const val FULLWIDTH_COMMA = '，'

    /**
     * 解析金额类数值；空 / 非法返回 null（Web `number`）。
     *
     * 逗号只在「确实是千分位」时剔除：逗号后正好 3 位数字，且这组数字后面是结尾、小数点
     * 或下一组千分位。所以 `1,234.56` → 1234.56、`¥1,234` → 1234，而欧洲写法 `12,50`
     * （12 块 5 毛）保持原样 → 解析失败走「金额无法识别」的错误行，不会被读成 1250 元。
     */
    fun parse(value: Any?): Double? {
        if (value == null) return null
        val text = when (value) {
            is Double -> return value.takeIf { it.isFinite() }
            is Float -> return value.toDouble().takeIf { it.isFinite() }
            is Int -> return value.toDouble()
            is Long -> return value.toDouble()
            is Number -> return value.toDouble().takeIf { it.isFinite() }
            is Boolean -> return if (value) 1.0 else 0.0
            else -> value.toString()
        }
        if (text.trim().isEmpty()) return null
        return stripThousands(text.replace(STRIP, "")).toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * 去掉数字串里的千分位逗号：仅当逗号后正好 3 位数字且这 3 位后面不是数字。
     *
     * 其余逗号（`12,50` 的小数逗号、`1,2345` 的异常分组）原样保留，让 `toDouble()` 失败，
     * 由调用方按「无法识别的数字」处理，而不是静默改值。
     */
    internal fun stripThousands(text: String): String {
        if (text.indexOf(COMMA) < 0 && text.indexOf(FULLWIDTH_COMMA) < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if ((c == COMMA || c == FULLWIDTH_COMMA) && isThousandsSeparator(text, i)) {
                i++
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** `text[index]` 是逗号：判断它是否处在「,ddd」千分位位置上。 */
    private fun isThousandsSeparator(text: String, index: Int): Boolean {
        var end = index + 1
        while (end < text.length && text[end] in '0'..'9') end++
        if (end - (index + 1) != 3) return false
        if (end == text.length) return true
        return text[end] == COMMA || text[end] == FULLWIDTH_COMMA || text[end] == '.'
    }

    /**
     * JS `String(number)` 风格的渲染：整数不带 `.0`，小数去掉尾随 0。
     * 用于 HTML / 备注等字符串拼接处的数字展示。
     */
    fun jsString(value: Double): String {
        if (!value.isFinite()) return value.toString()
        if (value == 0.0) return "0"
        val bd = BigDecimal(value.toString()).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** JS `Number.prototype.toFixed(2)` 风格（用于 signature 等内部比较）。 */
    fun toFixed2(value: Double): String =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** 将单元格转成 JS `String(cell)` 的结果：null→""，布尔→"true"/"false"。 */
    fun cellString(value: Any?): String = when (value) {
        null -> ""
        is Double -> jsString(value)
        is Float -> jsString(value.toDouble())
        is Boolean -> value.toString()
        else -> value.toString()
    }
}
