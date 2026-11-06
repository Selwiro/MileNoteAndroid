package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record

/** 合法的服务类分类（金额允许为 0）。 */
val SERVICE_CATEGORIES: Set<String> = setOf("维修保养", "保养", "维修", "汽车美容")

/**
 * 记录业务校验（Web `validateRecord`）。
 *
 * @return 空串表示通过，否则为可直接展示的中文错误文案。
 */
fun validateRecord(record: Record, customCategories: List<String> = emptyList()): String {
    if (Dates.dateValue(record.date).isEmpty()) return "日期无效"
    if (record.category !in Categories.ALL &&
        record.category !in customCategories &&
        record.category != "维修保养"
    ) {
        return "请选择汽车费用分类"
    }
    val amount = record.amount
    if (!amount.isFinite() || amount < 0 ||
        (amount == 0.0 && record.kind != "manual" && record.category !in SERVICE_CATEGORIES)
    ) {
        return "金额必须大于 0"
    }
    val numericFields = listOf(record.odometer, record.liters, record.rate, record.distance)
    for (value in numericFields) {
        if (value != null && (!value.isFinite() || value < 0)) {
            return "里程、加油量、油耗必须是有效非负数"
        }
    }
    if (record.kind == "manual" && !((record.rate ?: 0.0) > 0)) return "请填写有效油耗"
    return ""
}
