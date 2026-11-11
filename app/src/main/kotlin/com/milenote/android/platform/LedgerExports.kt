package com.milenote.android.platform

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.export.ExpenseWorkbook
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.energyProfile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 与 Web 版一致的文本导出：费用 CSV、费用 Excel 与保养提醒 ICS。
 *
 * CSV 为 UTF-8 BOM + CRLF，全部单元格加引号并防公式注入；
 * 列定义与 Excel 导出共用 [ExpenseWorkbook.headers]（燃油 / 纯电 17 列、插混 18 列），
 * 与导入别名表 [com.milenote.android.domain.import.FIELDS] 中对应字段互相映射。
 * ICS 为全天事件 + 提前一天 DISPLAY 提醒；支持单条与「一次性导出全部」。
 */
object LedgerExports {

    /**
     * 当前车辆全部记录 → CSV 字节（含 BOM）；表头与能量列按动力类型切换。
     *
     * - 燃油 / HEV：17 列，能量列「加油量」；
     * - 纯电：17 列，能量列「充电量」；
     * - 插混：18 列，加油行写「加油量」、充电行写末尾「充电量」（互斥）。
     */
    fun csv(ledger: Ledger): ByteArray {
        val profile = ledger.vehicle.energyProfile
        val headers = ExpenseWorkbook.headers(ledger)
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

    /** 当前车辆全部记录 → `.xlsx`（费用明细 + 分类汇总，金额/里程为数字单元格）。 */
    fun xlsx(ledger: Ledger): ByteArray = ExpenseWorkbook.build(ledger)

    /** 单条提醒 → ICS 文本（与 Web `calendar()` 字段一致）。 */
    fun ics(reminder: Reminder): String? {
        val dueDate = Dates.parse(reminder.dueDate) ?: return null
        return wrapCalendar(listOf(vevent(reminder, dueDate)))
    }

    /**
     * 一次性导出多条提醒：未完成且有有效到期日期的提醒合成**一个** ICS 文件。
     *
     * @return 没有可导出项时返回 null（调用方提示用户）。
     */
    fun icsAll(reminders: List<Reminder>): String? {
        val events = reminders.mapNotNull { reminder ->
            if (reminder.done) return@mapNotNull null
            val dueDate = Dates.parse(reminder.dueDate) ?: return@mapNotNull null
            vevent(reminder, dueDate)
        }
        if (events.isEmpty()) return null
        return wrapCalendar(events)
    }

    /** 备份文件名，如 `里程志备份-2026-11-04.json`。 */
    fun backupFileName(date: LocalDate = Dates.today()): String =
        "里程志备份-${Dates.iso(date)}.json"

    fun csvFileName(date: LocalDate = Dates.today()): String = "里程志-${Dates.iso(date)}.csv"

    fun xlsxFileName(date: LocalDate = Dates.today()): String = "里程志-${Dates.iso(date)}.xlsx"

    fun icsFileName(reminder: Reminder): String = "保养提醒-${reminder.dueDate}.ics"

    /** 批量 ICS 文件名，如 `保养提醒-全部-2026-11-11.ics`。 */
    fun icsAllFileName(date: LocalDate = Dates.today()): String =
        "保养提醒-全部-${Dates.iso(date)}.ics"

    private fun wrapCalendar(events: List<String>): String {
        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//DriveLedger//CN")
            addAll(events)
            add("END:VCALENDAR")
        }
        return lines.joinToString("\r\n")
    }

    private fun vevent(reminder: Reminder, dueDate: LocalDate): String {
        val end = dueDate.plusDays(1)
        val dtstamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return listOf(
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
        ).joinToString("\r\n")
    }

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
