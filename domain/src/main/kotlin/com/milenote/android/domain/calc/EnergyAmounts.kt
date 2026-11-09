package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import kotlin.math.round

private fun round2(value: Double): Double = round(value * 100.0) / 100.0

/**
 * 能源费记录金额字段自动补全（燃油「加油量×油价」/ 纯电「充电量×电价」）。
 *
 * 业务关系：`能源量 × 单价 = 应付金额`，`应付金额 - 优惠金额 = 实付金额`。
 * 只补空字段，不覆盖已经填写的内容；至少有一个能源相关字段时才计算。
 *
 * 覆盖的典型场景：
 * - 填了实付金额 + 能源量：补单价（实付 / 能源量）、应付金额 = 实付、优惠 = 0；
 * - 填了能源量 + 单价 + 实付金额：补应付金额（量 × 价）、优惠金额（应付 - 实付）；
 * - 填了能源量 + 单价（未填实付）：补应付金额 = 量 × 价，再补实付 = 应付 - 优惠；
 * - 填了实付/应付 + 单价（未填能源量）：补能源量 = 应付（或实付）/ 单价（能算就补）。
 */
fun fillEnergyAmounts(record: Record, ev: Boolean = false): Record {
    if (record.kind == "manual" || !Categories.isEnergy(record.category)) return record

    // 纯电记录用 kwh，燃油记录用 liters；哪种字段已存在就写回哪种，
    // 都为空时按传入的动力类型（ev）决定新建哪个字段。
    val isKwh = when {
        record.kwh != null || record.liters != null -> record.kwh != null && record.liters == null
        else -> ev
    }
    var amountOf = record.kwh ?: record.liters
    var unitPrice = record.unitPrice
    var payable = record.payable
    var discount = record.discount
    var amount = record.amount.takeIf { it > 0.0 }

    // 没有任何能源金额/量字段时不猜。
    if (amountOf == null && unitPrice == null && payable == null && discount == null) return record

    if (amountOf != null && unitPrice != null) {
        if (payable == null) payable = round2(amountOf * unitPrice)
        when {
            amount != null && discount == null -> discount = round2(payable!! - amount)
            amount == null && discount != null -> amount = round2(payable!! - discount)
            amount == null && discount == null -> {
                amount = payable
                discount = 0.0
            }
        }
    }
    if (payable == null && amount != null && discount != null) payable = round2(amount + discount)
    if (discount == null && payable != null && amount != null) discount = round2(payable - amount)
    if (amount == null && payable != null && discount != null) amount = round2(payable - discount)
    if (payable == null && amount != null) payable = amount
    // 能源量 = 应付（优先；量 × 价 = 应付更准确）/ 单价，用于「只填了金额和电价/油价」的场景。
    if (amountOf == null && unitPrice != null && unitPrice > 0) {
        (payable ?: amount)?.let { money ->
            if (money > 0) amountOf = round2(money / unitPrice)
        }
    }
    if (unitPrice == null && amountOf != null && amountOf > 0) {
        (amount ?: payable)?.let { unitPrice = round2(it / amountOf) }
    }
    if (discount == null && amount != null) discount = 0.0

    return record.copy(
        amount = amount ?: record.amount,
        liters = if (isKwh) record.liters else amountOf,
        kwh = if (isKwh) amountOf else record.kwh,
        unitPrice = unitPrice,
        payable = payable,
        discount = discount,
    )
}
