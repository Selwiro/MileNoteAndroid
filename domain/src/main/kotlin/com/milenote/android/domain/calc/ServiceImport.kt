package com.milenote.android.domain.calc

import com.milenote.android.domain.import.inferServiceNote
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder

/** 保养台账导入预览行：`record` 用于历史模式，`reminder` 用于周期模式。 */
data class ServiceImportRow(
    /** Excel/CSV 行号（含表头，从 2 开始）。 */
    val row: Int,
    val record: Record? = null,
    val reminder: Reminder? = null,
    /** 行级错误；空串表示通过。 */
    val error: String = "",
)

private fun rawCell(row: List<Any?>, map: Map<String, Int?>, key: String): Any? {
    val index = map[key] ?: return null
    if (index < 0 || index >= row.size) return null
    return row[index]
}

private fun textCell(row: List<Any?>, map: Map<String, Int?>, key: String): String =
    Numbers.cellString(rawCell(row, map, key))

/**
 * 保养台账逐行解析（Web `serviceRows`）。
 *
 * - `schedule`（手册/周期）：生成 [Reminder]，支持「周期」「周期公里数」「周期月数」三种写法，
 *   上次保养日期/公里数留空时用基准值；
 * - `history`（历史记录）：生成 `category=保养` 的 [Record]，项目与费用必填（免费填 0）。
 */
fun serviceRows(
    rows: List<List<Any?>>,
    map: Map<String, Int?>,
    mode: String,
    baseDate: String,
    baseKm: Double?,
): List<ServiceImportRow> = rows.mapIndexed { i, row ->
    val project = textCell(row, map, "project").trim()
    val note = textCell(row, map, "note")
    if (mode == "history") {
        val amount = Numbers.parse(rawCell(row, map, "amount"))
        var record = Record(
            id = Ids.newId(),
            date = Dates.dateValue(rawCell(row, map, "date")),
            location = textCell(row, map, "location").trim(),
            odometer = Numbers.parse(rawCell(row, map, "odometer")),
            projects = project,
            category = "保养",
            kind = "expense",
            amount = amount ?: 0.0,
            note = note,
            source = "保养记录导入",
        )
        record = inferServiceNote(record)
        val error = if (record.projects.isNullOrEmpty() || amount == null) {
            "项目和费用不能为空（免费请填 0）"
        } else {
            validateRecord(record)
        }
        ServiceImportRow(row = i + 2, record = record, error = error)
    } else {
        val cycle = parseCycleText(textCell(row, map, "cycle"))
        val intervalKm = Numbers.parse(rawCell(row, map, "intervalKm")) ?: cycle.km
        val intervalMonths = Numbers.parse(rawCell(row, map, "intervalMonths")) ?: cycle.months
        val lastDate = Dates.dateValue(rawCell(row, map, "lastDate")).ifEmpty { baseDate }
        val lastKm = Numbers.parse(rawCell(row, map, "lastKm")) ?: baseKm

        val error = when {
            project.isEmpty() -> "缺少保养项目"
            !((intervalKm ?: 0.0) > 0) && !((intervalMonths ?: 0.0) > 0) ->
                "请填写正数的周期公里数或周期月数"
            intervalMonths != null && (intervalMonths != Math.floor(intervalMonths) ||
                intervalMonths < 0 || intervalMonths > 600) -> "周期月数应为 1–600 的整数"
            intervalKm != null && intervalKm < 0 -> "周期公里数不能为负"
            (intervalMonths ?: 0.0) > 0 && Dates.dateValue(lastDate).isEmpty() -> "请填写有效基准日期"
            (intervalKm ?: 0.0) > 0 && (lastKm == null || lastKm < 0) -> "请填写有效基准里程"
            else -> ""
        }

        val months = intervalMonths?.takeIf { it > 0 }?.toInt()
        val km = intervalKm?.takeIf { it > 0 }
        val reminder = Reminder(
            id = Ids.newId(),
            title = project,
            baseDate = lastDate,
            baseKm = lastKm,
            intervalKm = km,
            intervalMonths = months,
            dueDate = if (error.isEmpty() && months != null) Dates.addMonths(lastDate, months) else "",
            dueKm = if (error.isEmpty() && km != null) (lastKm ?: 0.0) + km else null,
            done = false,
            note = note,
            source = "保养手册",
        )
        ServiceImportRow(row = i + 2, reminder = reminder, error = error)
    }
}
