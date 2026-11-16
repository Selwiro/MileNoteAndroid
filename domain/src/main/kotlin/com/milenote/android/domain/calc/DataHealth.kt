package com.milenote.android.domain.calc

import com.milenote.android.domain.format.Format
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import java.time.LocalDate

/**
 * 数据体检（F1）：把「会让统计 / 提醒出错、但写入校验拦不到的历史脏数据」列成可操作清单。
 *
 * 纯函数、只读账本：不改数据、不依赖 UI；`today` 可注入便于单测。
 * 修复动作是独立的纯函数（[removeDuplicateRecords] / [repairReminders] / [markFuelCounted]），
 * 由界面层决定是否执行、并在执行前留快照。
 */

/** 问题严重度：ERROR 会让统计 / 提醒出错；WARN 可疑需人工确认；INFO 只提示。 */
enum class HealthSeverity { ERROR, WARN, INFO }

/** 可执行的修复动作；`null` 表示只能人工处理（打开记录 / 车辆编辑）。 */
enum class HealthFix {
    /** 打开记录编辑器（记录 id 在 [HealthIssue.recordId]）。 */
    OPEN_EDITOR,

    /** 打开车辆编辑（车辆级问题）。 */
    OPEN_VEHICLE,

    /** 同 id 多条只保留一条（较新的一条），其余删除；可撤销。 */
    DELETE_DUPLICATE,

    /** 重算提醒：补 dueKm / dueDate，清掉指向已删记录的引用。 */
    RECOMPUTE_REMINDER,

    /** 把这条油费记录标记为「计入成本」。 */
    MARK_FUEL_COUNTED,
}

/** 一条体检结果。 */
data class HealthIssue(
    /** 稳定编号（UI / 测试用，不翻译）。 */
    val code: String,
    val severity: HealthSeverity,
    /** 短标题（已 i18n）。 */
    val title: String,
    /** 具体数值 / 原因（已 i18n）。 */
    val detail: String,
    val recordId: String? = null,
    val reminderId: String? = null,
    val fix: HealthFix? = null,
)

/** 一次体检的结果。 */
data class HealthReport(
    val issues: List<HealthIssue> = emptyList(),
) {
    val errorCount: Int get() = issues.count { it.severity == HealthSeverity.ERROR }
    val warnCount: Int get() = issues.count { it.severity == HealthSeverity.WARN }
    val infoCount: Int get() = issues.count { it.severity == HealthSeverity.INFO }

    /** 需要处理的数量（错误 + 提示；INFO 只是告知）。 */
    val attentionCount: Int get() = errorCount + warnCount

    val isEmpty: Boolean get() = issues.isEmpty()
}

/** 体检规则编号。 */
object HealthCodes {
    const val DUPLICATE_ID = "DUPLICATE_ID"
    const val DATE_INVALID = "DATE_INVALID"
    const val DATE_BEFORE_1970 = "DATE_BEFORE_1970"
    const val DATE_FUTURE = "DATE_FUTURE"
    const val CATEGORY_UNKNOWN = "CATEGORY_UNKNOWN"
    const val AMOUNT_INVALID = "AMOUNT_INVALID"
    const val ODOMETER_BACKWARDS = "ODOMETER_BACKWARDS"
    const val ODOMETER_BELOW_START = "ODOMETER_BELOW_START"
    const val MIXED_ENERGY_UNITS = "MIXED_ENERGY_UNITS"
    const val SERVICE_WITHOUT_ODOMETER = "SERVICE_WITHOUT_ODOMETER"
    const val REMINDER_DANGLING = "REMINDER_DANGLING"
    const val REMINDER_NO_DUE_KM = "REMINDER_NO_DUE_KM"
    const val REMINDER_NO_DUE_DATE = "REMINDER_NO_DUE_DATE"
    const val FUEL_NOT_COUNTED = "FUEL_NOT_COUNTED"
    const val VEHICLE_MILEAGE_CONFLICT = "VEHICLE_MILEAGE_CONFLICT"
    const val START_ABOVE_CURRENT = "START_ABOVE_CURRENT"
}

/**
 * 跑一次数据体检。
 *
 * 规则保守：只报**确定**的错（例如读数确实下降、日期确实非法），可疑项放 WARN / INFO；
 * 宁可少报，也不让用户对「体检」失去信任。
 */
fun dataHealth(ledger: Ledger, today: LocalDate = Dates.today()): HealthReport {
    val issues = mutableListOf<HealthIssue>()
    val records = ledger.records
    val ids = records.mapTo(HashSet()) { it.id }
    val customCategories = ledger.customCategories.orEmpty()

    // 1. 同 id 重复（导入合并 / 手动改 id 的产物）。
    records.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, rows) ->
        val sample = rows.take(3).joinToString(tr("、", ", ")) { row ->
            tr("%s %s 元", "%s · %s", Format.dateDots(row.date), Format.money(row.amount))
        }
        issues += HealthIssue(
            code = HealthCodes.DUPLICATE_ID,
            severity = HealthSeverity.ERROR,
            title = tr("同一条记录出现了多次", "The same record appears more than once"),
            detail = tr("编号 %s 有 %d 条：%s", "ID %s has %d records: %s", id, rows.size, sample),
            recordId = id,
            fix = HealthFix.DELETE_DUPLICATE,
        )
    }

    records.forEach { record ->
        val where = tr("%s · %s", "%s · %s", Format.dateDots(record.date), Format.money(record.amount))

        // 2. 日期：非法 / 早于 1970 / 未来（预录只提示）。
        if (Dates.dateValue(record.date).isEmpty()) {
            issues += HealthIssue(
                code = HealthCodes.DATE_INVALID,
                severity = HealthSeverity.ERROR,
                title = tr("日期无效", "Invalid date"),
                detail = tr("%s：日期「%s」无法识别", "%s: date \"%s\" is not valid", where, record.date),
                recordId = record.id,
                fix = HealthFix.OPEN_EDITOR,
            )
        } else {
            if (!Dates.isPlausibleRecordDate(record.date)) {
                issues += HealthIssue(
                    code = HealthCodes.DATE_BEFORE_1970,
                    severity = HealthSeverity.ERROR,
                    title = tr("日期早于 1970 年", "Date before 1970"),
                    detail = tr("%s：这条记录会把月均 / 年趋势算错", "%s: this record skews monthly averages and yearly trends", where),
                    recordId = record.id,
                    fix = HealthFix.OPEN_EDITOR,
                )
            }
            val parsed = Dates.parse(record.date)
            if (parsed != null && parsed.isAfter(today)) {
                issues += HealthIssue(
                    code = HealthCodes.DATE_FUTURE,
                    severity = HealthSeverity.INFO,
                    title = tr("未来日期的预录", "Future-dated record"),
                    detail = tr("%s：月报 / 年报不计入，累计视角会计入", "%s: excluded from monthly/yearly reports, included in the all-time view", where),
                    recordId = record.id,
                    fix = HealthFix.OPEN_EDITOR,
                )
            }
        }

        // 3. 分类：空或不在内置 + 自定义里（旧「维修保养」是合法存储值）。
        if (record.category.isBlank() ||
            (record.category !in Categories.ALL &&
                record.category !in customCategories &&
                record.category != "维修保养")
        ) {
            issues += HealthIssue(
                code = HealthCodes.CATEGORY_UNKNOWN,
                severity = HealthSeverity.ERROR,
                title = tr("分类缺失或无法识别", "Missing or unknown category"),
                detail = tr("%s：分类「%s」", "%s: category \"%s\"", where, record.category),
                recordId = record.id,
                fix = HealthFix.OPEN_EDITOR,
            )
        }

        // 4. 金额：负数 / 非有限 / 非服务类为 0。
        val amountBad = !record.amount.isFinite() || record.amount < 0 ||
            (record.amount == 0.0 && record.kind != "manual" && record.category !in SERVICE_CATEGORIES)
        if (amountBad) {
            issues += HealthIssue(
                code = HealthCodes.AMOUNT_INVALID,
                severity = HealthSeverity.ERROR,
                title = tr("金额不合理", "Invalid amount"),
                detail = tr("%s：金额 %s", "%s: amount %s", where, Format.money(record.amount)),
                recordId = record.id,
                fix = HealthFix.OPEN_EDITOR,
            )
        }

        // 5. 一条记录只能有一种能量量纲（升 + 度会被油 / 电两条流各算一遍）。
        if (record.liters != null && record.kwh != null) {
            issues += HealthIssue(
                code = HealthCodes.MIXED_ENERGY_UNITS,
                severity = HealthSeverity.ERROR,
                title = tr("同时有加油量与充电量", "Both fuel and charge amounts"),
                detail = tr("%s：%s 升 + %s 度", "%s: %s L + %s kWh", where, Format.fmt(record.liters), Format.fmt(record.kwh)),
                recordId = record.id,
                fix = HealthFix.OPEN_EDITOR,
            )
        }

        // 6. 保养记录缺总里程（B12 之后新增会被拦，历史数据仍可能缺）。
        if (Categories.normalize(record.category) == "保养" && record.odometer == null) {
            issues += HealthIssue(
                code = HealthCodes.SERVICE_WITHOUT_ODOMETER,
                severity = HealthSeverity.WARN,
                title = tr("保养记录没有总里程", "Maintenance record without odometer"),
                detail = tr("%s：提醒无法按里程起算", "%s: reminders cannot be scheduled from mileage", where),
                recordId = record.id,
                fix = HealthFix.OPEN_EDITOR,
            )
        }
    }

    // 7. 里程读数倒挂（跨日期下降 / 低于起始里程）。
    //
    // 同一天可能加两次油、记两次里程，读数本来就可以有两条；按「日期 + id」排序会把
    // id 较小的那条当成先发生，于是同一天两笔也会被报成「读数比之前小」（用户实测）。
    // 同一天内没有可靠的先后（记录只有日期），所以同一天按读数升序处理、只和**更早日期**
    // 的最高读数比较——一天两笔即便录反也不会误报，跨天下降仍然会报。
    val startKm = ledger.vehicle.startKm
    var maxOdometer = Double.NEGATIVE_INFINITY
    records
        .filter { it.odometer != null }
        .sortedWith(compareBy({ it.date }, { it.odometer ?: 0.0 }))
        .forEach { record ->
            val odometer = record.odometer ?: return@forEach
            if (odometer < maxOdometer) {
                issues += HealthIssue(
                    code = HealthCodes.ODOMETER_BACKWARDS,
                    severity = HealthSeverity.WARN,
                    title = tr("里程读数比之前小", "Odometer lower than an earlier record"),
                    detail = tr(
                        "%s：%s 小于更早日期的最大读数 %s",
                        "%s: %s is lower than the highest reading on an earlier day (%s)",
                        Format.dateDots(record.date),
                        Format.fmt(odometer),
                        Format.fmt(maxOdometer),
                    ),
                    recordId = record.id,
                    fix = HealthFix.OPEN_EDITOR,
                )
            } else {
                maxOdometer = odometer
            }
            if (startKm != null && odometer < startKm) {
                issues += HealthIssue(
                    code = HealthCodes.ODOMETER_BELOW_START,
                    severity = HealthSeverity.WARN,
                    title = tr("读数早于记账起始里程", "Odometer below the starting mileage"),
                    detail = tr(
                        "%s：%s 小于起始里程 %s",
                        "%s: %s is below the starting mileage %s",
                        Format.dateDots(record.date),
                        Format.fmt(odometer),
                        Format.fmt(startKm),
                    ),
                    recordId = record.id,
                    fix = HealthFix.OPEN_EDITOR,
                )
            }
        }

    // 8. 提醒：引用已删记录 / 没有到期条件。
    ledger.reminders.filter { it.done != true }.forEach { reminder ->
        val dangling = listOfNotNull(
            reminder.recordId?.takeIf { it !in ids },
            reminder.historyRecordId?.takeIf { it !in ids },
        )
        if (dangling.isNotEmpty()) {
            issues += HealthIssue(
                code = HealthCodes.REMINDER_DANGLING,
                severity = HealthSeverity.WARN,
                title = tr("提醒指向已删除的记录", "Reminder points to a deleted record"),
                detail = tr("「%s」引用了不存在的记录 %s", "\"%s\" references missing record %s", reminder.title, dangling.joinToString(", ")),
                reminderId = reminder.id,
                fix = HealthFix.RECOMPUTE_REMINDER,
            )
        }
        if ((reminder.intervalKm ?: 0.0) > 0 && reminder.dueKm == null) {
            issues += HealthIssue(
                code = HealthCodes.REMINDER_NO_DUE_KM,
                severity = HealthSeverity.WARN,
                title = tr("里程提醒没有到期里程", "Mileage reminder without a due mileage"),
                detail = tr("「%s」永远不会按里程触发", "\"%s\" will never trigger by mileage", reminder.title),
                reminderId = reminder.id,
                fix = HealthFix.RECOMPUTE_REMINDER,
            )
        }
        if ((reminder.intervalMonths ?: 0) > 0 && reminder.dueDate.isNullOrBlank()) {
            issues += HealthIssue(
                code = HealthCodes.REMINDER_NO_DUE_DATE,
                severity = HealthSeverity.WARN,
                title = tr("时间提醒没有到期日期", "Time reminder without a due date"),
                detail = tr("「%s」永远不会按时间触发", "\"%s\" will never trigger by date", reminder.title),
                reminderId = reminder.id,
                fix = HealthFix.RECOMPUTE_REMINDER,
            )
        }
    }

    // 9. 油费记录被标记为「不计入成本」（可能是导入口径，也可能是误操作）。
    records.filter {
        Categories.isEnergy(it.category) && it.fuelCounted == false && it.amount > 0
    }.forEach { record ->
        issues += HealthIssue(
            code = HealthCodes.FUEL_NOT_COUNTED,
            severity = HealthSeverity.INFO,
            title = tr("油费没有计入成本", "Fuel record excluded from cost"),
            detail = tr(
                "%s：合计与元/公里不含这一笔",
                "%s: totals and cost per km exclude this record",
                tr("%s · %s 元", "%s · %s", Format.dateDots(record.date), Format.money(record.amount)),
            ),
            recordId = record.id,
            fix = HealthFix.MARK_FUEL_COUNTED,
        )
    }

    // 10. 车辆里程：当前里程小于最大读数 / 起始里程大于当前里程。
    // 车辆里的「当前里程」是用户手填的字段（不会随记录自动更新）；只在用户填过（> 0）时才比对，
    // 避免把所有「没填当前里程」的用户都报成问题。
    val storedKm = ledger.vehicle.currentKm
    val maxReading = records.mapNotNull { it.odometer }.maxOrNull()
    if (storedKm > 0 && maxReading != null && storedKm < maxReading) {
        issues += HealthIssue(
            code = HealthCodes.VEHICLE_MILEAGE_CONFLICT,
            severity = HealthSeverity.WARN,
            title = tr("当前里程小于记录读数", "Current mileage is below a record reading"),
            detail = tr(
                "当前 %s，记录里最大 %s",
                "Current %s, highest reading %s",
                Format.fmt(storedKm),
                Format.fmt(maxReading),
            ),
            fix = HealthFix.OPEN_VEHICLE,
        )
    }
    if (storedKm > 0 && startKm != null && startKm > storedKm) {
        issues += HealthIssue(
            code = HealthCodes.START_ABOVE_CURRENT,
            severity = HealthSeverity.WARN,
            title = tr("起始里程大于当前里程", "Starting mileage is above current mileage"),
            detail = tr(
                "起始 %s，当前 %s",
                "Start %s, current %s",
                Format.fmt(startKm),
                Format.fmt(storedKm),
            ),
            fix = HealthFix.OPEN_VEHICLE,
        )
    }

    return HealthReport(issues)
}

/**
 * 同 id 的多条记录只保留一条：优先 `updatedAt` 较新、其次日期较晚、最后原顺序靠后；
 * 其余删除并返回撤销快照（与批量删除同一套 [DeletedRecords]）。
 */
fun removeDuplicateRecords(ledger: Ledger, id: String): RecordRemoval {
    val matches = ledger.records.mapIndexedNotNull { index, record ->
        if (record.id == id) index to record else null
    }
    if (matches.size <= 1) return RecordRemoval(ledger, DeletedRecords())
    val keep = matches.maxWithOrNull(
        compareBy<Pair<Int, Record>> { it.second.updatedAt ?: 0L }
            .thenBy { it.second.date }
            .thenBy { it.first },
    )!!.first
    val removed = matches.filter { it.first != keep }
    return RecordRemoval(
        ledger = ledger.copy(
            records = ledger.records.filterIndexed { index, _ ->
                index == keep || matches.none { it.first == index }
            },
        ),
        undo = DeletedRecords(
            records = removed.map { it.second },
            indices = removed.map { it.first },
        ),
    )
}

/**
 * 重算提醒：补上缺失的 dueKm / dueDate，清掉指向已删记录的引用。
 *
 * - 里程：`dueKm = (baseKm ?: 当前里程) + intervalKm`；
 * - 时间：`dueDate = baseDate + intervalMonths`（baseDate 缺失时用今天）。
 */
fun repairReminders(ledger: Ledger, today: LocalDate = Dates.today()): Ledger {
    val ids = ledger.records.mapTo(HashSet()) { it.id }
    val current = currentKm(ledger)
    val todayIso = Dates.iso(today)
    val repaired = ledger.reminders.map { reminder ->
        var next = reminder
        if (next.recordId != null && next.recordId !in ids) next = next.copy(recordId = null)
        if (next.historyRecordId != null && next.historyRecordId !in ids) {
            next = next.copy(historyRecordId = null)
        }
        val intervalKm = next.intervalKm
        if (intervalKm != null && intervalKm > 0 && next.dueKm == null) {
            val base = next.baseKm ?: current
            next = next.copy(dueKm = base + intervalKm)
        }
        val months = next.intervalMonths
        if (months != null && months > 0 && next.dueDate.isNullOrBlank()) {
            val baseDate = next.baseDate?.takeIf { Dates.dateValue(it).isNotEmpty() } ?: todayIso
            next = next.copy(dueDate = Dates.addMonths(baseDate, months))
        }
        next
    }
    return ledger.copy(reminders = repaired)
}

/** 把一条油费记录标记为「计入成本」。 */
fun markFuelCounted(ledger: Ledger, recordId: String): Ledger =
    ledger.copy(
        records = ledger.records.map { record ->
            if (record.id == recordId) record.copy(fuelCounted = true) else record
        },
    )
