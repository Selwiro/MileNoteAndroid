package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import com.milenote.android.domain.i18n.tr

/** 合法的服务类分类（金额允许为 0）。 */
val SERVICE_CATEGORIES: Set<String> = setOf("维修保养", "保养", "维修", "汽车美容")

/**
 * 必须填写总里程的分类（B12）。
 *
 * 汽车美容（洗车 / 打蜡）虽然也是服务类，但与保养周期无关，不强制里程；
 * 保养 / 维修 / 旧「维修保养」会更新保养提醒，必须填。
 */
val ODOMETER_REQUIRED_CATEGORIES: Set<String> = setOf("维修保养", "保养", "维修")

/**
 * 记录业务校验（Web `validateRecord`）。
 *
 * @return 空串表示通过，否则为可直接展示的中文错误文案。
 */
fun validateRecord(
    record: Record,
    customCategories: List<String> = emptyList(),
    /**
     * 是否要求服务类记录必须填总里程。
     *
     * 编辑器 / 导入向导传 true（B12：所有保养记录总里程必填）；备份校验保持默认 false，
     * 否则老备份里没里程的保养记录会被整份拒绝，破坏 `version:2` 兼容。
     */
    requireServiceOdometer: Boolean = false,
): String {
    if (Dates.dateValue(record.date).isEmpty()) return tr("日期无效", "Invalid date")
    if (record.category !in Categories.ALL &&
        record.category !in customCategories &&
        record.category != "维修保养"
    ) {
        return tr("请选择汽车费用分类", "Select an expense category")
    }
    val amount = record.amount
    if (!amount.isFinite() || amount < 0 ||
        (amount == 0.0 && record.kind != "manual" && record.category !in SERVICE_CATEGORIES)
    ) {
        return tr("金额必须大于 0", "Amount must be greater than 0")
    }
    val numericFields = listOf(record.odometer, record.liters, record.kwh, record.rate, record.distance)
    for (value in numericFields) {
        if (value != null && (!value.isFinite() || value < 0)) {
            return tr(
                "里程、加油量/充电量、能耗必须是有效非负数",
                "Mileage, energy amount and energy economy must be valid non-negative numbers",
            )
        }
    }
    // 一条记录只能有一种能量量纲：升与度同时存在会被油/电两条流各算一遍（静默双计量）。
    if (record.liters != null && record.kwh != null) {
        return tr(
            "一条记录只能填加油量或充电量中的一种",
            "A record can hold either a fuel amount or a charge amount, not both",
        )
    }
    if (requireServiceOdometer &&
        Categories.normalize(record.category) in ODOMETER_REQUIRED_CATEGORIES &&
        record.odometer == null
    ) {
        return tr("保养 / 维修记录请填写总里程", "Enter the odometer for service or repair records")
    }
    if (record.kind == "manual" && !((record.rate ?: 0.0) > 0)) return tr("请填写有效油耗/电耗", "Enter a valid fuel/energy economy")
    return ""
}
