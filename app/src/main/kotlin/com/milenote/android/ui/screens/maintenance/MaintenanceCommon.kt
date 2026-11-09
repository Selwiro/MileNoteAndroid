package com.milenote.android.ui.screens.maintenance

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReminderLevel
import com.milenote.android.domain.calc.ReminderState
import com.milenote.android.domain.calc.ServiceProgress
import com.milenote.android.domain.calc.serviceProgress
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Reminder
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.components.StatusKind
import java.time.LocalDate

/**
 * 维修保养页共用的纯展示辅助（状态映射、进度文案、数值解析）。
 *
 * 所有数值口径与 Web `app.mjs` / `maintenance.mjs` 一致，页面与弹层都从这里取，
 * 避免「待办卡」与「计划表」两处状态文案漂移。
 */

/** 维修 / 保养 / 汽车美容记录分类（含旧版「维修保养」）。 */
internal val SERVICE_RECORD_CATEGORIES: Set<String> = setOf("维修保养", "保养", "维修", "汽车美容")

/** Web `reminderNoteHTML` 中视为「内置说明」、不重复展示的备注。 */
internal val GENERATED_REMINDER_NOTES: Set<String> = setOf(
    "用户设置的默认周期；公里或时间先到为准",
    "日期或公里数先到为准",
    "日期和里程任一达到即提醒",
    "按最近一次实际保养日期和里程计算，先到为准",
)

/** 提醒等级 → 中文徽章（Web `labels`）。 */
internal fun reminderLevelLabel(level: ReminderLevel): String = when (level) {
    ReminderLevel.DONE -> tr("已完成", "Completed")
    ReminderLevel.OVERDUE -> tr("已到期", "Overdue")
    ReminderLevel.SOON -> tr("即将到期", "Due soon")
    ReminderLevel.NORMAL -> tr("计划中", "Planned")
}

/** 提醒等级 → [StatusKind]。 */
internal fun reminderLevelStatus(level: ReminderLevel): StatusKind = when (level) {
    ReminderLevel.DONE -> StatusKind.DONE
    ReminderLevel.OVERDUE -> StatusKind.OVERDUE
    ReminderLevel.SOON -> StatusKind.SOON
    ReminderLevel.NORMAL -> StatusKind.NORMAL
}

/** 计划表状态 → 中文徽章（Web `servicePlanTable` 的 `labels`）。 */
internal fun planStatusLabel(status: String): String = when (status) {
    "overdue" -> tr("过期", "Overdue")
    "done" -> tr("已做", "Done")
    "unconfigured" -> tr("未启用", "Not enabled")
    else -> tr("待做", "To do")
}

/** 计划表状态 → [StatusKind]。 */
internal fun planStatusKind(status: String): StatusKind = when (status) {
    "overdue" -> StatusKind.OVERDUE
    "done" -> StatusKind.DONE
    "unconfigured" -> StatusKind.NORMAL
    else -> StatusKind.NORMAL
}

/**
 * 完成保养记录的费用分类：默认「保养」，按提醒标题/备注推断维修与汽车美容
 * （Web 完成弹层允许用户改分类，本实现按提醒来源自动归类）。
 */
internal fun inferServiceCategory(reminder: Reminder): String {
    val text = "${reminder.title} ${reminder.note.orEmpty()}"
    return when {
        Regex("美容|洗车|打蜡|抛光|镀晶|内饰").containsMatchIn(text) -> "汽车美容"
        Regex("维修|补胎|电瓶|钣金|喷漆").containsMatchIn(text) -> "维修"
        else -> "保养"
    }
}

/** 需要展示的提醒备注（过滤内置说明，Web `reminderNoteHTML`）。 */
internal fun reminderNote(reminder: Reminder): String? =
    reminder.note?.trim()?.takeIf { it.isNotEmpty() && it !in GENERATED_REMINDER_NOTES }

/**
 * 待办卡的「状态行」文案：状态词 + 剩余量（先到维度）。
 *
 * 例：`已到期 · 超出 2,000 公里` / `即将到期 · 还有 22 天` / `计划中 · 还剩 1,000 公里`。
 * 剩余量取 [serviceProgress] 判定的那条轨（日期 / 里程先到），没有进度数据时按
 * [ReminderState] 里的天数或里程兜底。
 */
internal fun reminderStatusLine(
    reminder: Reminder,
    state: ReminderState,
    progress: ServiceProgress?,
    distanceUnit: String = Units.distanceUnit,
): String {
    val label = reminderLevelLabel(state.level)
    val remaining = when (progress?.kind) {
        "km" -> remainingKmText(state.left, distanceUnit)
        "days" -> remainingDaysText(state.days)
        else -> when {
            state.days != null -> remainingDaysText(state.days)
            state.left != null -> remainingKmText(state.left, distanceUnit)
            else -> null
        }
    }
    return if (remaining == null) label else "$label · $remaining"
}

/** 剩余里程文案：`还剩 1,000 公里` / `里程已用完` / `超出 2,000 公里`。 */
private fun remainingKmText(left: Double?, distanceUnit: String): String? {
    if (left == null) return null
    return when {
        left > 0 -> tr("还剩 %s", "%s left", Format.km(left, distanceUnit))
        left == 0.0 -> tr("里程已用完", "mileage used up")
        else -> tr("超出 %s", "over by %s", Format.km(-left, distanceUnit))
    }
}

/** 剩余天数文案：`还有 22 天` / `已过 10 天`。 */
private fun remainingDaysText(days: Long?): String? {
    if (days == null) return null
    return if (days >= 0) {
        tr("还有 %d 天", "%d days left", days)
    } else {
        tr("已过 %d 天", "%d days overdue", -days)
    }
}

/**
 * 待办卡的「绝对值」行：到期日 / 到期里程，两者都在时用 ` · ` 连接。
 * 例：`2026-12-09 到期 · 40,000 公里`、只有日期 `2026-12-01 到期`。
 */
internal fun reminderAbsoluteDeadline(reminder: Reminder): String {
    val parts = buildList {
        reminder.dueDate?.takeIf { it.isNotEmpty() }?.let { add(tr("%s 到期", "Due %s", it)) }
        reminder.dueKm?.let { add(distanceText(it)) }
    }
    return parts.joinToString(" · ")
}

/** 里程显示：`42,000 公里` / `26,098 mi`（里程单位随设置 → 外观 → 单位）。 */
internal fun distanceText(value: Double?): String = Format.km(value, Units.distanceUnit)

/** 到期日期 + 到期里程文案（Web 计划表「下次日期 / 里程」列）。 */
internal fun reminderDueText(reminder: Reminder): String {
    val parts = buildList {
        reminder.dueDate?.takeIf { it.isNotEmpty() }?.let { add(it) }
        reminder.dueKm?.let { add(distanceText(it)) }
    }
    return if (parts.isEmpty()) {
        tr("待补充日期 / 里程", "Date / mileage to be filled in")
    } else {
        parts.joinToString(tr(" 或 ", " or "))
    }
}

/** 输入框数值解析：容忍千分位、货币符号与全角逗号（Web `number`）。 */
internal fun parseDecimal(text: String): Double? {
    val cleaned = text
        .replace("，", "")
        .replace(",", "")
        .replace("¥", "")
        .replace("￥", "")
        .trim()
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** 可编辑数字的初始文案：整数不带小数，保留 1 位小数。 */
internal fun formatDecimalInput(value: Double?): String {
    if (value == null || !value.isFinite()) return ""
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/** 表单日期输入校验：空视为未填，非空必须为合法 `YYYY-MM-DD`。 */
internal fun normalizeDateInput(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return if (Dates.isValid(trimmed)) trimmed else null
}
