package com.milenote.android.domain.export

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.costOf
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile

/**
 * 费用工作簿（`.xlsx`）：与费用 CSV 同口径的「费用明细」+ 一张「分类汇总」。
 *
 * 列定义是 CSV 的同一套口径（17 列；插混 18 列，末尾「充电量」），
 * 由 `LedgerExports.csv` 与本工作簿共用 [headers]，避免两份导出漂移。
 * 金额 / 里程 / 能源量写成**数字单元格**，Excel 里可直接求和、排序、透视。
 */
object ExpenseWorkbook {

    /** 燃油车 / HEV：17 列（与 CSV 一致）。 */
    private val HEADERS_FUEL = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "油价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源",
    )

    /** 纯电：17 列，能源列改充电语义（第 9 列仍是通用「标号」）。 */
    private val HEADERS_EV = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "电价", "标号",
        "总里程", "充电量", "充满", "漏记", "能耗", "行驶里程", "备注", "来源",
    )

    /** 插混：18 列 = 燃油 17 列 + 末尾「充电量」。 */
    private val HEADERS_PHEV = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "单价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源", "充电量",
    )

    /** 当前动力类型对应的表头（CSV 与 XLSX 共用）。 */
    fun headers(ledger: Ledger): List<String> = when (ledger.vehicle.energyProfile) {
        EnergyProfile.ELECTRIC_ONLY -> HEADERS_EV
        EnergyProfile.DUAL -> HEADERS_PHEV
        EnergyProfile.FUEL_ONLY -> HEADERS_FUEL
    }

    /** 生成 `.xlsx` 字节。 */
    fun build(ledger: Ledger): ByteArray = XlsxWriter.write(sheets(ledger))

    /** 两张工作表：费用明细、分类汇总（测试也直接检查这里）。 */
    fun sheets(ledger: Ledger): List<XlsxSheet> = listOf(
        XlsxSheet(name = "费用明细", rows = detailRows(ledger)),
        XlsxSheet(name = "分类汇总", rows = summaryRows(ledger)),
    )

    private fun detailRows(ledger: Ledger): List<List<XlsxCell>> {
        val profile = ledger.vehicle.energyProfile
        val dual = profile == EnergyProfile.DUAL
        val header = headers(ledger).map { XlsxCell.Text(it) }
        val rows = ledger.records
            .sortedWith(
                compareByDescending<Record> { it.date }
                    .thenByDescending { it.odometer ?: 0.0 },
            )
            .map { record ->
                // 充电行：有充电量且没有加油量（一条记录只认一种能量）。
                val charging = dual && record.kwh != null && record.liters == null
                val energy = when (profile) {
                    EnergyProfile.ELECTRIC_ONLY -> record.kwh
                    EnergyProfile.DUAL -> if (charging) null else record.liters
                    EnergyProfile.FUEL_ONLY -> record.liters
                }
                buildList {
                    add(XlsxCell.Text(record.date))
                    add(XlsxCell.Text(record.location.orEmpty()))
                    add(XlsxCell.Text(record.projects.orEmpty()))
                    add(XlsxCell.Text(record.category))
                    add(XlsxCell.Number(record.amount))
                    add(record.payable.numberCell())
                    add(record.discount.numberCell())
                    add(record.unitPrice.numberCell())
                    // 燃油标号只属于加油行；插混充电行留空。
                    add(XlsxCell.Text(if (charging) "" else record.fuelGrade.orEmpty()))
                    add(record.odometer.numberCell())
                    add(energy.numberCell())
                    add(XlsxCell.Text(if (record.full) "是" else "否"))
                    add(XlsxCell.Text(if (record.missed) "是" else "否"))
                    add((record.rate ?: record.reportedRate).numberCell())
                    add(record.distance.numberCell())
                    add(XlsxCell.Text(record.note))
                    add(XlsxCell.Text(record.source))
                    if (dual) add(if (charging) record.kwh.numberCell() else XlsxCell.Blank)
                }
            }
        return listOf(header) + rows
    }

    private fun summaryRows(ledger: Ledger): List<List<XlsxCell>> {
        val header = listOf("分类", "金额", "记录数", "占比").map { XlsxCell.Text(it) }
        val counted = ledger.records.map { it to costOf(it, ledger) }
        val total = counted.sumOf { it.second }
        val byCategory = counted
            .groupBy { Categories.normalize(it.first.category) }
            .map { (category, entries) ->
                Triple(category, entries.sumOf { it.second }, entries.size)
            }
            .filter { it.second > 0.0 || it.third > 0 }
            .sortedByDescending { it.second }
        val rows = byCategory.map { (category, amount, count) ->
            listOf(
                XlsxCell.Text(categoryLabel(ledger, category)),
                XlsxCell.Number(round2(amount)),
                XlsxCell.Number(count.toDouble()),
                XlsxCell.Text(if (total > 0) percent(amount / total) else "—"),
            )
        }
        val totalRow = listOf(
            XlsxCell.Text("合计"),
            XlsxCell.Number(round2(total)),
            XlsxCell.Number(counted.size.toDouble()),
            XlsxCell.Text(if (total > 0) "100.0%" else "—"),
        )
        return listOf(header) + rows + listOf(totalRow)
    }

    /** 能源分类按动力类型显示（油费 / 电费 / 能源），其余保留存储值。 */
    private fun categoryLabel(ledger: Ledger, category: String): String = when {
        category != "油费" -> category
        ledger.vehicle.energyProfile == EnergyProfile.ELECTRIC_ONLY -> "电费"
        ledger.vehicle.energyProfile == EnergyProfile.DUAL -> "能源"
        else -> "油费"
    }

    private fun percent(value: Double): String {
        val scaled = round1(value * 100)
        return if (scaled == scaled.toLong().toDouble()) "${scaled.toLong()}.0%" else "$scaled%"
    }

    private fun round1(value: Double): Double = kotlin.math.round(value * 10) / 10
    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100

    private fun Double?.numberCell(): XlsxCell = this?.let { XlsxCell.Number(it) } ?: XlsxCell.Blank
}
