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
    private val STRIP = Regex("[,，¥￥\\s]")

    /** 解析金额类数值；空 / 非法返回 null（Web `number`）。 */
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
        val cleaned = text.replace(STRIP, "")
        return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
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
