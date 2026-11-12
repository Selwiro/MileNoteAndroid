package com.milenote.android.ui

import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import java.time.LocalDate

/**
 * 列表页筛选（费用明细 / 能耗明细 / 维修保养记录）。
 *
 * 与「统计范围」(`UiState.period` + `customRange`) 区分：
 * - 统计范围只服务总览与油耗/能耗两个统计页，两页共享；
 * - 列表筛选每页独立，只影响本页列表，切车时重置。
 *
 * 页面 key 用路由名，便于在 ViewModel 里按页面保存。
 */
object ListFilterPages {
    const val RECORDS = "records"
    const val FUEL_DETAIL = "fuel-detail"
    const val SERVICE_HISTORY = "service-history"
}

/** 能耗明细的「类型」取值（仅插混显示）。 */
object EnergyTypeFilter {
    const val FUEL = "fuel"
    const val CHARGE = "charge"
}

/**
 * 单个列表页的筛选条件。
 *
 * @param period 时间预设；自定义范围非空时覆盖它（与统计范围同一套语义）。
 * @param customRange 自定义起止日（闭区间，ISO）。
 * @param categories 选中分类；空集合 = 全部分类。
 * @param types 页面特有类型（能耗：加油/充电；保养记录：维修/保养/美容）；空集合 = 全部。
 * @param query 搜索关键词（独立于筛选面板，直接可见）。
 */
data class ListFilterState(
    val period: Period = Period.ALL,
    val customRange: DateRange? = null,
    val categories: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val query: String = "",
) {
    /** 实际生效的时间区间：自定义优先。 */
    fun range(today: LocalDate = Dates.today()): DateRange =
        customRange ?: Dates.range(period, today)

    val hasTimeFilter: Boolean get() = customRange != null || period != Period.ALL

    /** 生效条件个数（搜索不计；与筛选栏胶囊、「筛选 · N」和 chips 数量一致）。 */
    val activeCount: Int
        get() = (if (hasTimeFilter) 1 else 0) + categories.size + types.size

    /** 恢复默认：全部时间、无分类、无类型、空搜索。 */
    fun cleared(): ListFilterState = ListFilterState()
}
