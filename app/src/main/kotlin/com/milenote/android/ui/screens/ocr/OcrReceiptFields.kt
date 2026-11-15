package com.milenote.android.ui.screens.ocr

import com.milenote.android.domain.ocr.FuelReceipt

/**
 * OCR 结果里是否至少有一项可回填的字段。
 *
 * 加油机屏幕常常只能读出一部分（例如只读到单价 / 应付 / 优惠），这也算识别成功；
 * 不能只把金额、升数、日期、里程当有效字段，否则会误报「没有识别到有效字段」（审计 B18）。
 */
internal fun hasUsefulReceiptField(receipt: FuelReceipt?): Boolean {
    if (receipt == null) return false
    return receipt.amount != null ||
        receipt.liters != null ||
        receipt.date.isNotBlank() ||
        receipt.odometer != null ||
        receipt.unitPrice != null ||
        receipt.payable != null ||
        receipt.discount != null
}
