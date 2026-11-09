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
 * - 填了能源量 + 单价 + 实付金额：补应付金额（量 × 价）、优惠金额（应付 - 实付）。
 */
fun fillEnergyAmounts(record: Record): Record {
    if (record.kind == "manual" || !Categories.isEnergy(record.category)) return record

    // 纯电记录用 kwh，燃油记录用 liters；哪种字段存在就写回哪种。
    val isKwh = record.kwh != null && record.liters == null
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
