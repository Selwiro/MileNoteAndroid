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
 * 校验失败指向的表单字段。
 *
 * UI 用它把错误标在对应输入框下方，并把该字段滚进视野（UX1）：
 * 只给一句总述时，用户不知道「错在哪、要改哪里」。
 */
enum class RecordField {
    DATE,

    CATEGORY,

    AMOUNT,

    /** 加油量 / 充电量（能源量）。 */
    ENERGY,

    ODOMETER,

    /** 手填油耗 / 本次行驶里程。 */
    ECONOMY,
}

/** 一条校验结果：出错字段 + 可直接展示的文案。 */
data class RecordIssue(val field: RecordField, val message: String)

/**
 * 记录业务校验（Web `validateRecord`），带出错字段。
 *
 * 编辑器用 [recordIssue] 做字段级标红与滚动定位；导入 / 备份等只需要文案的地方
 * 继续用 [validateRecord]。
 *
 * @return 通过返回 null，否则为 [RecordIssue]。
 */
fun recordIssue(
    record: Record,
    customCategories: List<String> = emptyList(),
    /**
     * 是否要求服务类记录必须填总里程。
     *
     * 编辑器 / 导入向导传 true（B12：所有保养记录总里程必填）；备份校验保持默认 false，
     * 否则老备份里没里程的保养记录会被整份拒绝，破坏 `version:2` 兼容。
     */
    requireServiceOdometer: Boolean = false,
    /** 是否拒绝早于 1970 的日期（导入 / 编辑器传 true；备份校验保持默认 false）。 */
    requirePlausibleDate: Boolean = false,
): RecordIssue? {
    if (Dates.dateValue(record.date).isEmpty()) {
        return RecordIssue(RecordField.DATE, tr("日期无效", "Invalid date"))
    }
    if (requirePlausibleDate && !Dates.isPlausibleRecordDate(record.date)) {
        return RecordIssue(RecordField.DATE, tr("日期早于 1970 年，请检查", "The date is before 1970 — check it"))
    }
    if (record.category !in Categories.ALL &&
        record.category !in customCategories &&
        record.category != "维修保养"
    ) {
        return RecordIssue(RecordField.CATEGORY, tr("请选择汽车费用分类", "Select an expense category"))
    }
    val amount = record.amount
    if (!amount.isFinite() || amount < 0 ||
        (amount == 0.0 && record.kind != "manual" && record.category !in SERVICE_CATEGORIES)
    ) {
        return RecordIssue(RecordField.AMOUNT, tr("金额必须大于 0", "Amount must be greater than 0"))
    }
    val numericMessage = tr(
        "里程、加油量/充电量、能耗必须是有效非负数",
        "Mileage, energy amount and energy economy must be valid non-negative numbers",
    )
    fun invalid(value: Double?): Boolean = value != null && (!value.isFinite() || value < 0)
    if (invalid(record.odometer)) return RecordIssue(RecordField.ODOMETER, numericMessage)
    if (invalid(record.liters) || invalid(record.kwh)) return RecordIssue(RecordField.ENERGY, numericMessage)
    if (invalid(record.rate) || invalid(record.distance)) return RecordIssue(RecordField.ECONOMY, numericMessage)
    // 一条记录只能有一种能量量纲：升与度同时存在会被油/电两条流各算一遍（静默双计量）。
    if (record.liters != null && record.kwh != null) {
        return RecordIssue(
            RecordField.ENERGY,
            tr(
                "一条记录只能填加油量或充电量中的一种",
                "A record can hold either a fuel amount or a charge amount, not both",
            ),
        )
    }
    if (requireServiceOdometer &&
        Categories.normalize(record.category) in ODOMETER_REQUIRED_CATEGORIES &&
        record.odometer == null
    ) {
        return RecordIssue(
            RecordField.ODOMETER,
            tr("保养 / 维修记录请填写总里程", "Enter the odometer for service or repair records"),
        )
    }
    if (record.kind == "manual" && !((record.rate ?: 0.0) > 0)) {
        return RecordIssue(RecordField.ECONOMY, tr("请填写有效油耗/电耗", "Enter a valid fuel/energy economy"))
    }
    return null
}

/**
 * 记录业务校验（Web `validateRecord`），只要文案。
 *
 * @return 空串表示通过，否则为可直接展示的中文错误文案。
 */
fun validateRecord(
    record: Record,
    customCategories: List<String> = emptyList(),
    requireServiceOdometer: Boolean = false,
    requirePlausibleDate: Boolean = false,
): String = recordIssue(
    record = record,
    customCategories = customCategories,
    requireServiceOdometer = requireServiceOdometer,
    requirePlausibleDate = requirePlausibleDate,
)?.message ?: ""
