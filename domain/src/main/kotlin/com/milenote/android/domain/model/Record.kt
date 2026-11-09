package com.milenote.android.domain.model

import kotlinx.serialization.Serializable

/** 燃油标号合法取值；导入清洗用（界面选项见 app 的 `FuelGradeOptions`）。 */
val FUEL_GRADES: List<String> = listOf("92#", "95#", "98#", "0#")

/**
 * 一条费用记录（Web 备份 `ledger.records[]`）。
 *
 * 字段集合与 Web 版完全一致，包括历史遗留字段（`time`、`currency`、`importedKeys`），
 * 以保证 Android 导出的备份可被 Web 读取、Web 备份可被 Android 无损读入。
 */
@Serializable
data class Record(
    /** 记录唯一编号（Web `crypto.randomUUID()` 或时间戳随机串）。 */
    val id: String = "",
    /** 日期 `YYYY-MM-DD`，账本唯一时间粒度。 */
    val date: String = "",
    /** 费用分类：9 类之一 / 自定义分类 / 旧版「维修保养」。 */
    val category: String = "",
    /** 实付金额（元），有效记录 ≥ 0。 */
    val amount: Double = 0.0,
    /** 总里程 km。 */
    val odometer: Double? = null,
    /** 加油量 L（仅燃油记录；纯电记录为 null）。 */
    val liters: Double? = null,
    /**
     * 充电量 kWh（度，仅纯电记录；燃油记录为 null）。
     *
     * 与 [liters] 互斥，保证一条记录的能量量纲自描述：
     * 切换车辆动力类型时历史数据不会被按另一种单位重新解释。
     */
    val kwh: Double? = null,
    /** 能源单价：燃油=元/L，纯电=元/kWh；手工填写/OCR 回填，不参与能耗计算。 */
    val unitPrice: Double? = null,
    /** 应付金额（元），仅油费记录；用于和实付金额、优惠金额对照。 */
    val payable: Double? = null,
    /** 优惠金额（元），仅油费记录。 */
    val discount: Double? = null,
    /** 满箱法区间油耗或历史手填油耗（L/100km）。 */
    val rate: Double? = null,
    /** 导入的「原始油耗」，仅供对照，不参与计算。 */
    val reportedRate: Double? = null,
    /** 手填油耗对应的行驶里程。 */
    val distance: Double? = null,
    /** 本次是否加满。 */
    val full: Boolean = false,
    /** 此前是否有漏记加油。 */
    val missed: Boolean = false,
    /** 备注，≤ 1000 字。 */
    val note: String = "",
    /** 服务类记录的地点，≤ 200 字。 */
    val location: String? = null,
    /** 服务类记录的项目，顿号分隔。 */
    val projects: String? = null,
    /** 来源：手动记录 / 小熊油耗 / 钱迹 / 保养记录导入 / 演示数据 等。 */
    val source: String = "",
    /** 导入源内的唯一编号，用于幂等判重。 */
    val sourceId: String? = null,
    /** `expense`（普通支出）或 `manual`（历史手填油耗）。 */
    val kind: String = "expense",
    /** 油费是否计入成本（三态；缺省时回退旧 `fuelCostSource` 口径）。 */
    val fuelCounted: Boolean? = null,
    /** 已合并过的 `来源:编号` 集合，用于幂等。 */
    val importedKeys: List<String>? = null,
    /** 导入时的币种，仅校验用，不参与计算。 */
    val currency: String? = null,
    /** 旧版遗留时刻字段；Web 编辑时会删除，但备份中可能存在。 */
    val time: String? = null,
    /** 燃油标号（仅油费记录）：取值见 [FUEL_GRADES]，缺省表示未指定。 */
    val fuelGrade: String? = null,
)
