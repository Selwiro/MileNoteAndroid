package com.milenote.android.platform

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.energyProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 与 Web 版一致的文本导出：费用 CSV 与单条提醒 ICS。
 *
 * CSV 为 UTF-8 BOM + CRLF，全部单元格加引号并防公式注入；
 * 列在 Web 版基础上于「金额」后补齐「应付金额 / 优惠金额 / 油价 / 燃油标号」
 * （取记录字段，空则空单元格），与导入别名表 [com.milenote.android.domain.import.FIELDS]
 * 中对应字段互相映射，保证导出文件可被导入流程原样回灌。
 * ICS 为全天事件 + 提前一天 DISPLAY 提醒。
 */
object LedgerExports {

    /** 燃油车 CSV 表头：保持历史 17 列与列序不变（测试与 CSV 回读都依赖）。 */
    private val CSV_HEADERS_FUEL = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "油价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源",
    )

    /**
     * 纯电 CSV 表头：列数与列序不变，只把能源相关列改成充电语义，
     * 第 9 列保留为通用「标号」（纯电无值，留空），保证导出/导入列位置稳定。
     */
    private val CSV_HEADERS_EV = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "电价", "标号",
        "总里程", "充电量", "充满", "漏记", "能耗", "行驶里程", "备注", "来源",
    )

    /**
     * 插混（PHEV / 增程）CSV 表头：**18 列** = 燃油 17 列 + 末尾「充电量」。
     *
     * 第 8 列取中性「单价」（同一列既可能是油价也可能是电价），
     * 第 9 列仍是「燃油标号」（只有加油行有值）；加油行写「加油量」、
     * 充电行写末尾「充电量」，两者互斥（与记录里 `liters` / `kwh` 互斥一致）。
     */
    private val CSV_HEADERS_PHEV = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "单价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源", "充电量",
    )

    /**
     * 当前车辆全部记录 → CSV 字节（含 BOM）；表头与能量列按动力类型切换。
     *
     * - 燃油 / HEV：17 列，能量列「加油量」；
     * - 纯电：17 列，能量列「充电量」；
     * - 插混：18 列，加油行写「加油量」、充电行写末尾「充电量」（互斥）。
     */
    fun csv(ledger: Ledger): ByteArray {
        val profile = ledger.vehicle.energyProfile
        val headers = when (profile) {
            EnergyProfile.ELECTRIC_ONLY -> CSV_HEADERS_EV
            EnergyProfile.DUAL -> CSV_HEADERS_PHEV
            EnergyProfile.FUEL_ONLY -> CSV_HEADERS_FUEL
        }
        val dual = profile == EnergyProfile.DUAL
        val sorted = ledger.records.sortedWith(
            compareByDescending<com.milenote.android.domain.model.Record> { it.date }
                .thenByDescending { it.odometer ?: 0.0 },
        )
        val lines = buildString {
            append(headers.joinToString(",") { cell(it) })
            append("\r\n")
            for (r in sorted) {
                // 充电行：有充电量且没有加油量（一条记录只认一种能量）。
                val charging = dual && r.kwh != null && r.liters == null
                val energy = when (profile) {
                    EnergyProfile.ELECTRIC_ONLY -> r.kwh
                    EnergyProfile.DUAL -> if (charging) null else r.liters
                    EnergyProfile.FUEL_ONLY -> r.liters
                }
                val values = buildList {
                    add(r.date)
                    add(r.location.orEmpty())
                    add(r.projects.orEmpty())
                    add(r.category)
                    add(numberCell(r.amount))
                    add(r.payable?.let { numberCell(it) } ?: "")
                    add(r.discount?.let { numberCell(it) } ?: "")
                    add(r.unitPrice?.let { numberCell(it) } ?: "")
                    // 燃油标号只属于加油行；充电行留空。
                    add(if (charging) "" else r.fuelGrade.orEmpty())
                    add(r.odometer?.let { numberCell(it) } ?: "")
                    add(energy?.let { numberCell(it) } ?: "")
                    add(if (r.full) "是" else "否")
                    add(if (r.missed) "是" else "否")
                    add((r.rate ?: r.reportedRate)?.let { numberCell(it) } ?: "")
                    add(r.distance?.let { numberCell(it) } ?: "")
                    add(r.note)
                    add(r.source)
                    if (dual) add(if (charging) r.kwh?.let { numberCell(it) } ?: "" else "")
                }
                append(values.joinToString(",") { cell(it) })
                append("\r\n")
            }
        }
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + lines.toByteArray(Charsets.UTF_8)
    }

    /** 单条提醒 → ICS 文本（与 Web `calendar()` 字段一致）。 */
    fun ics(reminder: Reminder): String? {
        val dueDate = Dates.parse(reminder.dueDate) ?: return null
        val end = dueDate.plusDays(1)
        val dtstamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val lines = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//DriveLedger//CN",
            "BEGIN:VEVENT",
            "UID:${reminder.id}@drive-ledger",
            "DTSTAMP:$dtstamp",
            "DTSTART;VALUE=DATE:${dueDate.format(DateTimeFormatter.BASIC_ISO_DATE)}",
            "DTEND;VALUE=DATE:${end.format(DateTimeFormatter.BASIC_ISO_DATE)}",
            "SUMMARY:${escape(reminder.title)}",
            "DESCRIPTION:${escape(reminder.note.orEmpty())}",
            "BEGIN:VALARM",
            "TRIGGER:-P1D",
            "ACTION:DISPLAY",
            "DESCRIPTION:${escape(reminder.title)}",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return lines.joinToString("\r\n")
    }

    /** 备份文件名，如 `里程志备份-2026-11-04.json`。 */
    fun backupFileName(date: LocalDate = Dates.today()): String =
        "里程志备份-${Dates.iso(date)}.json"

    fun csvFileName(date: LocalDate = Dates.today()): String = "里程志-${Dates.iso(date)}.csv"

    fun icsFileName(reminder: Reminder): String = "保养提醒-${reminder.dueDate}.ics"

    private fun cell(value: String): String {
        val text = value.replace("\r", " ")
        val safe = if (text.isNotEmpty() && text.first() in charArrayOf('=', '+', '@', '-')) "'$text" else text
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun numberCell(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")
        .replace("\r", "")
}
