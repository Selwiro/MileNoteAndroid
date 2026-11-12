package com.milenote.android.domain.calc

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 统计时间范围（全 App 唯一一套时间预设）。
 *
 * 顺序即筛选菜单 / 筛选面板的显示顺序；「自定义」不是枚举值，由 `DateRange` 单独承载。
 */
enum class Period { ALL, MONTH, LAST_MONTH, LAST_3_MONTHS, YEAR }

/** 统计区间：ISO 日期字符串，闭区间（与 Web `stats(s,from,to)` 一致）。 */
data class DateRange(val from: String, val to: String)

/**
 * 日期工具。全部使用无时区的 [LocalDate] 与 `YYYY-MM-DD` 字符串，
 * 与 Web 的「日历日」语义一致，避免 Android 上因时区差 1 天。
 */
object Dates {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val LOOSE = Regex("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})")
    private val STRICT = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** 可注入的「今天」，测试不依赖真实系统时区。 */
    fun today(): LocalDate = LocalDate.now()

    /** 严格校验 `YYYY-MM-DD` 且为真实日历日。 */
    fun isValid(value: String?): Boolean {
        if (value == null || !STRICT.matches(value)) return false
        return runCatching { LocalDate.parse(value, ISO) }.isSuccess
    }

    /** 严格解析；非法返回 null。 */
    fun parse(value: String?): LocalDate? =
        if (isValid(value)) runCatching { LocalDate.parse(value, ISO) }.getOrNull() else null

    /** 格式化 [LocalDate] 为 `YYYY-MM-DD`。 */
    fun iso(date: LocalDate): String = date.format(ISO)

    /**
     * 把任意单元格 / 输入转成 `YYYY-MM-DD`，非法返回空串（Web `dateValue`）。
     *
     * 支持：`LocalDate`、Excel 1900 日期序列号、`2026/2/3 10:00`、`2026年2月3日` 等。
     */
    fun dateValue(value: Any?): String {
        when (value) {
            null -> return ""
            is LocalDate -> return iso(value)
            is Number -> {
                val serial = value.toDouble()
                if (!serial.isFinite()) return ""
                val millis = Math.round((serial - 25569.0) * 86_400_000.0)
                val epochDay = Math.floorDiv(millis, 86_400_000L)
                return runCatching { iso(LocalDate.ofEpochDay(epochDay)) }.getOrDefault("")
            }
        }
        val text = value.toString().trim()
        val m = LOOSE.find(text) ?: return ""
        val candidate = "%s-%s-%s".format(
            m.groupValues[1],
            m.groupValues[2].padStart(2, '0'),
            m.groupValues[3].padStart(2, '0'),
        )
        return if (isValid(candidate)) candidate else ""
    }

    /** 逐月加减，日期大于目标月末时钳到月末（Web UTC 版本语义）。 */
    fun addMonths(date: LocalDate, count: Int): LocalDate {
        val first = date.withDayOfMonth(1).plusMonths(count.toLong())
        val day = minOf(date.dayOfMonth, first.lengthOfMonth())
        return first.withDayOfMonth(day)
    }

    /** 字符串版 [addMonths]；输入非法时返回空串。 */
    fun addMonths(date: String, count: Int): String {
        val parsed = parse(date) ?: return ""
        return iso(addMonths(parsed, count))
    }

    /** 两个日期相差天数（b - a）。 */
    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    /** 两个 ISO 日期字符串相差天数；非法输入返回 null。 */
    fun daysBetween(from: String, to: String): Long? {
        val a = parse(from) ?: return null
        val b = parse(to) ?: return null
        return daysBetween(a, b)
    }

    /**
     * 统计区间：
     * - all → 全量；
     * - month → 本月 1 日至今；
     * - `LAST_MONTH` → 上月 1 日至上月末日；
     * - `LAST_3_MONTHS` → 含本月在内的 3 个自然月（本月 1 日往前推 2 个月）至今；
     * - year → 今年 1 月 1 日至今。
     */
    fun range(period: Period, today: LocalDate = today()): DateRange = when (period) {
        Period.ALL -> DateRange("", "9999-12-31")
        Period.MONTH -> DateRange(
            iso(today.withDayOfMonth(1)),
            iso(today),
        )
        Period.LAST_MONTH -> {
            val lastMonth = today.minusMonths(1)
            DateRange(
                iso(lastMonth.withDayOfMonth(1)),
                iso(lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())),
            )
        }
        Period.LAST_3_MONTHS -> DateRange(
            iso(today.withDayOfMonth(1).minusMonths(2)),
            iso(today),
        )
        Period.YEAR -> DateRange(
            iso(today.withDayOfMonth(1).withMonth(1)),
            iso(today),
        )
    }

    private val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /** 中文星期几（Web 顶栏）。 */
    fun weekday(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value % 7]

    /** `2026-11-04` → `2026.11.04`（Web 顶栏日期）。 */
    fun displayDots(date: String): String = date.replace('-', '.')

    /** 空串 / null 统一成空串。 */
    fun orEmpty(value: String?): String = value ?: ""
}
