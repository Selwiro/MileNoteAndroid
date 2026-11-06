package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/**
 * 车辆信息（Web 备份 `ledger.vehicle`）。
 *
 * 字段与 Web 版逐字段一致：可空字段对应 Web 上可能缺省的属性，
 * 序列化时配合 `explicitNulls = false` 省略，语义等价于 Web 的 `undefined`。
 */
@Serializable
data class Vehicle(
    /** 车辆昵称，Web 默认「我的爱车」。 */
    val name: String = "",
    /** 车牌，可为空字符串。 */
    val plate: String = "",
    /** 品牌 / 车型，可为空字符串。 */
    val model: String = "",
    /** 记账起始里程；Web `blank()` 中为 null。 */
    val startKm: Double? = null,
    /** 当前总里程。 */
    val currentKm: Double = 0.0,
    /**
     * 旧版油费成本口径：`bear` / `qianji` / `combined`。
     * Web 缺省时按 `combined` 处理，故这里保留可空而不设默认值。
     */
    val fuelCostSource: String? = null,
    /** 燃油标号：`92#` / `95#` / `98#` / `0#`，用于今日油价展示。 */
    val fuelGrade: String? = null,
    /** 提车日期，`YYYY-MM-DD`。 */
    val companionDate: String? = null,
    /** 车辆图片：dataURL 或 http(s) URL。 */
    val image: String? = null,
    /** 图片显示方式：`contain` / `cover`。 */
    val imageFit: String? = null,
)
