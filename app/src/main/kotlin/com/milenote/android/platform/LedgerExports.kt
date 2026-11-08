package com.milenote.android.platform

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Reminder
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

    private val CSV_HEADERS = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "油价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源",
    )

    /** 当前车辆全部记录 → CSV 字节（含 BOM）。 */
    fun csv(ledger: Ledger): ByteArray {
        val sorted = ledger.records.sortedWith(
            compareByDescending<com.milenote.android.domain.model.Record> { it.date }
                .thenByDescending { it.odometer ?: 0.0 },
        )
        val lines = buildString {
            append(CSV_HEADERS.joinToString(",") { cell(it) })
            append("\r\n")
            for (r in sorted) {
                val values = listOf(
                    r.date,
                    r.location.orEmpty(),
                    r.projects.orEmpty(),
                    r.category,
                    numberCell(r.amount),
                    r.payable?.let { numberCell(it) } ?: "",
                    r.discount?.let { numberCell(it) } ?: "",
                    r.unitPrice?.let { numberCell(it) } ?: "",
                    r.fuelGrade.orEmpty(),
                    r.odometer?.let { numberCell(it) } ?: "",
                    r.liters?.let { numberCell(it) } ?: "",
                    if (r.full) "是" else "否",
                    if (r.missed) "是" else "否",
                    (r.rate ?: r.reportedRate)?.let { numberCell(it) } ?: "",
                    r.distance?.let { numberCell(it) } ?: "",
                    r.note,
                    r.source,
                )
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
