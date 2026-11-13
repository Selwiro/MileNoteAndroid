package com.milenote.android.domain.calc

import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.energyProfile
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 报告的三种视角：月报 / 年报 / 累计。
 *
 * 与 [Period]（筛选预设，语义是「从某天至今」）刻意分开：报告要的是**闭合自然期**，
 * 「本月」在报告里必须和「本月至今」区分开，否则数字对不上。
 */
enum class ReportKind { MONTH, YEAR, ALL }

/**
 * 报告周期。
 *
 * - [ReportKind.MONTH]：`year` / `month` 指定自然月；
 * - [ReportKind.YEAR]：`year` 指定自然年；
 * - [ReportKind.ALL]：首条记录 → 今天（没有可翻的期）。
 *
 * 全部日期计算走 [LocalDate] / [YearMonth]，禁止手写 `"$month-31"` 这类拼串。
 */
data class ReportPeriod(
    val kind: ReportKind,
    val year: Int = 0,
    val month: Int = 1,
) {
    /** 完整自然期（闭区间）；累计没有固定区间，返回 null，实际起点由账本决定。 */
    fun range(): DateRange? = when (kind) {
        ReportKind.MONTH -> {
            val ym = YearMonth.of(year, month)
            DateRange(Dates.iso(ym.atDay(1)), Dates.iso(ym.atEndOfMonth()))
        }
        ReportKind.YEAR -> DateRange(Dates.iso(LocalDate.of(year, 1, 1)), Dates.iso(LocalDate.of(year, 12, 31)))
        ReportKind.ALL -> null
    }

    /** 上一期；累计没有上一期。 */
    fun previous(): ReportPeriod? = when (kind) {
        ReportKind.MONTH ->
            if (month <= 1) ReportPeriod(ReportKind.MONTH, year - 1, 12)
            else ReportPeriod(ReportKind.MONTH, year, month - 1)
        ReportKind.YEAR -> ReportPeriod(ReportKind.YEAR, year - 1, 1)
        ReportKind.ALL -> null
    }

    /** 去年同月（月报）／上一年（年报）；累计没有。 */
    fun yearAgo(): ReportPeriod? = when (kind) {
        ReportKind.MONTH -> ReportPeriod(ReportKind.MONTH, year - 1, month)
        ReportKind.YEAR -> ReportPeriod(ReportKind.YEAR, year - 1, 1)
        ReportKind.ALL -> null
    }

    /** 是否包含「今天」（本月 / 今年）；累计恒为 true（永远到至今）。 */
    fun isCurrent(today: LocalDate): Boolean = when (kind) {
        ReportKind.MONTH -> today.year == year && today.monthValue == month
        ReportKind.YEAR -> today.year == year
        ReportKind.ALL -> true
    }

    /** 是否是未来的期（未来没有箭头可翻，也不该给「较上月」）。 */
    fun isFuture(today: LocalDate): Boolean = when (kind) {
        ReportKind.MONTH -> YearMonth.of(year, month).isAfter(YearMonth.from(today))
        ReportKind.YEAR -> year > today.year
        ReportKind.ALL -> false
    }

    /** 当前期（默认打开的那一期）：月报=本月、年报=今年。 */
    companion object {
        fun current(kind: ReportKind, today: LocalDate = Dates.today()): ReportPeriod = when (kind) {
            ReportKind.MONTH -> ReportPeriod(ReportKind.MONTH, today.year, today.monthValue)
            ReportKind.YEAR -> ReportPeriod(ReportKind.YEAR, today.year, 1)
            ReportKind.ALL -> ReportPeriod(ReportKind.ALL)
        }
    }
}

/** 一期与对比期的差值；[percent] 在上期为 0 时为 null（不出现 `∞`）。 */
data class ReportDelta(val current: Double, val previous: Double) {
    val diff: Double get() = current - previous
    val percent: Double? get() = if (previous > 0.0) diff / previous * 100.0 else null
}

/** 趋势图的一个月度点。 */
data class MonthlyTotal(val year: Int, val month: Int, val total: Double)

/** 趋势图的一个年度点（累计视角）。 */
data class YearlyTotal(val year: Int, val total: Double)

/** 常去的站点：地点 + 次数 + 该站点累计花费。 */
data class StationCount(val station: String, val count: Int, val cost: Double)

/**
 * 一条能量流的报告数字（燃油 / 电荷各自一份）。
 *
 * - [count] 加油 / 充电次数（不含历史手填油耗）；
 * - [amount] 升 / 度；[cost] 用 [costOf] 口径（与支出合计同源）；
 * - [rate] 满箱法 / 满电法；[intervals] 段数必须露出来，1 段的环比没有说服力；
 * - [estimated] 没有区间时的累计估算（界面要标「估算」）。
 */
data class ReportEnergyLine(
    val count: Int,
    val amount: Double,
    val cost: Double,
    val unitPrice: Double?,
    val rate: Double?,
    val intervals: Int,
    val estimated: Double?,
    val previous: ReportEnergyLine? = null,
)

/** 「之最」三条；任一条数据不足时为 null，界面整行不画。 */
data class ReportHighlights(
    val topExpense: Record? = null,
    val priciestEnergy: Record? = null,
    val topStation: StationCount? = null,
)

/**
 * 一份报告的全部数字（纯数据，不含任何展示文案 / 颜色）。
 *
 * @property range 实际统计区间（进行中已截到昨天/今天；累计 = 首条记录 → 今天）
 * @property firstDate 累计视角的起点；月报 / 年报为 null
 * @property inProgress 本月 / 今年尚未结束；累计恒为 false
 * @property monthlyAvg 年报 = 支出 ÷ 已过月数；累计 = 支出 ÷ 跨度月数（含空月）
 * @property spanMonths 累计视角跨的自然月数
 * @property monthlyTrend 月报=近 12 个月；年报=当年 12 个月；累计且跨度 < 12 个月时也用这个
 * @property yearlyTrend 累计且跨度 ≥ 12 个月时按年；其余视角为空
 */
data class ReportData(
    val period: ReportPeriod,
    val range: DateRange,
    val firstDate: String?,
    val inProgress: Boolean,
    val days: Int,
    val total: Double,
    val totalDelta: ReportDelta?,
    val yearAgoDelta: ReportDelta?,
    val records: List<Record>,
    val recordCount: Int,
    val distance: Double?,
    val odometerReadings: Int,
    val perKm: Double?,
    val daily: Double,
    val monthlyAvg: Double?,
    val monthsWithData: Int,
    val spanMonths: Int?,
    val categories: List<CategoryValue>,
    val fuel: ReportEnergyLine?,
    val electric: ReportEnergyLine?,
    val monthlyTrend: List<MonthlyTotal>,
    val yearlyTrend: List<YearlyTotal>,
    val highlights: ReportHighlights,
    val hasData: Boolean,
)

/**
 * 生成一期报告。一次调用最多做 3 次区间统计（本期 / 上期 / 去年同月，累计只做 1 次全量），
 * 再加 1 次单遍按月 / 按年汇总；不会按 12 个月各跑一遍 [stats]。
 *
 * 口径与总览、油耗页完全同源：[costOf] 计支出、[stats] 给里程与满箱/满电区间；
 * 累计视角走 `stats(ledger)` 全量口径，里程 = 当前里程 − 起始里程（不是首末读数差）。
 *
 * @param ledger 当前车的账本
 * @param period 周期（月 / 年 / 累计）
 * @param today 可注入的「今天」，测试不依赖系统时区
 */
fun report(ledger: Ledger, period: ReportPeriod, today: LocalDate = Dates.today()): ReportData {
    val fullRange = period.range()
    // 未来日期的记录（预录）不参与「累计」的起点：否则跨度会是 0 / 负数。
    val firstRecordDate = ledger.records
        .mapNotNull { Dates.parse(it.date) }
        .minOrNull()
        ?.coerceAtMost(today)
    val inProgress = period.kind != ReportKind.ALL && period.isCurrent(today)

    // 实际区间：进行中截到今天；累计 = 首条记录 → 今天；未来期保留完整自然期（自然查不到记录）。
    val range: DateRange = when {
        fullRange == null -> DateRange("", Dates.iso(today))
        inProgress -> DateRange(fullRange.from, Dates.iso(today))
        else -> fullRange
    }

    val statsResult = if (fullRange == null) stats(ledger) else stats(ledger, range.from, range.to)
    val records = statsResult.records
    val odometerReadings = records.count { it.odometer != null }

    // 里程：无读数 → null；月/年报只有一条读数且期前没有基线 → null（算出来一定是 0，会误导）。
    val priorOdometer = if (fullRange == null) {
        null
    } else {
        ledger.records
            .filter { it.odometer != null && it.date < range.from }
            .maxByOrNull { it.date }
    }
    val distance = when {
        odometerReadings == 0 -> null
        fullRange != null && odometerReadings == 1 && priorOdometer == null -> null
        else -> statsResult.distance
    }

    val days = run {
        val from = Dates.parse(range.from) ?: today
        val to = Dates.parse(range.to) ?: today
        (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)
    }

    // 对比期：进行中 → 上期同长度；完整期 → 完整上期；累计没有对比。
    val previousRange = period.previous()?.range()?.let { prev ->
        if (inProgress) sameSpan(prev, days) else prev
    }
    val previousStats = previousRange?.let { stats(ledger, it.from, it.to) }
    val totalDelta = previousStats?.let { ReportDelta(statsResult.total, it.total) }

    // 去年同月：只有月报给；该期完全没有记录时整行不出现（不是「0 元」，是「没数据」）。
    val yearAgoRange = if (period.kind == ReportKind.MONTH) {
        period.yearAgo()?.range()?.let { ya -> if (inProgress) sameSpan(ya, days) else ya }
    } else {
        null
    }
    val yearAgoStats = yearAgoRange?.let { stats(ledger, it.from, it.to) }
    val yearAgoDelta = yearAgoStats
        ?.takeIf { it.records.isNotEmpty() }
        ?.let { ReportDelta(statsResult.total, it.total) }

    val profile = ledger.energyProfile
    val previousFuel = previousStats?.let { energyLine(ledger, it.records, EnergyKind.FUEL, it.fuelEnergy, null) }
    val previousElectric = previousStats?.let { energyLine(ledger, it.records, EnergyKind.ELECTRIC, it.electricEnergy, null) }
    val fuel = energyLine(ledger, records, EnergyKind.FUEL, statsResult.fuelEnergy, previousFuel)
    val electric = energyLine(ledger, records, EnergyKind.ELECTRIC, statsResult.electricEnergy, previousElectric)
        .takeIf { profile != EnergyProfile.FUEL_ONLY }

    val categories = statsResult.categories
        .filter { it.value > 0.0 }
        .sortedWith(
            compareByDescending<CategoryValue> { it.value }
                .thenBy { categoryOrder(it.name) }
                .thenBy { it.name },
        )

    val monthsWithData = records
        .mapNotNull { Dates.parse(it.date)?.let { d -> YearMonth.of(d.year, d.monthValue) } }
        .distinct()
        .size

    val spanMonths = if (fullRange == null && firstRecordDate != null) {
        (today.year - firstRecordDate.year) * 12 + (today.monthValue - firstRecordDate.monthValue) + 1
    } else {
        null
    }

    val monthlyAvg = when {
        fullRange == null -> spanMonths?.let { if (it > 0) statsResult.total / it else null }
        period.kind == ReportKind.YEAR ->
            statsResult.total / if (inProgress) today.monthValue.toDouble() else 12.0
        else -> null
    }

    val (monthlyTrend, yearlyTrend) = buildTrends(
        ledger = ledger,
        period = period,
        firstRecordDate = firstRecordDate,
        today = today,
        spanMonths = spanMonths,
        // 累计用 stats(ledger) 全量口径（不筛日期，与总览「全部」一致）；
        // 月 / 年用实际区间过滤，避免当前月的趋势柱把未来日期的记录也画进去。
        filterRange = if (fullRange == null) null else range,
    )

    return ReportData(
        period = period,
        range = range,
        firstDate = if (fullRange == null) firstRecordDate?.let(Dates::iso) else null,
        inProgress = inProgress,
        days = days,
        total = statsResult.total,
        totalDelta = totalDelta,
        yearAgoDelta = yearAgoDelta,
        records = records,
        recordCount = records.size,
        distance = distance,
        odometerReadings = odometerReadings,
        perKm = distance?.takeIf { it > 0.0 }?.let { statsResult.total / it },
        daily = statsResult.total / days,
        monthlyAvg = monthlyAvg,
        monthsWithData = monthsWithData,
        spanMonths = spanMonths,
        categories = categories,
        fuel = fuel,
        electric = electric,
        monthlyTrend = monthlyTrend,
        yearlyTrend = yearlyTrend,
        highlights = highlightsOf(ledger, records),
        hasData = records.isNotEmpty(),
    )
}

/** 对比期截成和本期同样的长度（上月 1–13 日 / 去年 1 月 1 日–去年今日）。 */
private fun sameSpan(prev: DateRange, days: Int): DateRange {
    val from = Dates.parse(prev.from) ?: return prev
    val to = prev.to.let { Dates.parse(it) } ?: return prev
    val end = from.plusDays((days - 1).toLong())
    return DateRange(prev.from, Dates.iso(if (end.isAfter(to)) to else end))
}

private fun categoryOrder(name: String): Int {
    val normalized = Categories.normalize(name)
    val index = Categories.ALL.indexOf(normalized)
    return if (index >= 0) index else Categories.ALL.size
}

/**
 * 一条能量流：次数 / 量 / 花费 / 加权单价 / 满箱（满电）法区间。
 *
 * 账本动力类型不支持的流返回 null（燃油账本没有电耗，纯电账本没有油耗）。
 */
private fun energyLine(
    ledger: Ledger,
    records: List<Record>,
    kind: EnergyKind,
    energy: EnergyRate?,
    previous: ReportEnergyLine?,
): ReportEnergyLine? {
    val profile = ledger.energyProfile
    if (kind == EnergyKind.FUEL && profile == EnergyProfile.ELECTRIC_ONLY) return null
    if (kind == EnergyKind.ELECTRIC && profile == EnergyProfile.FUEL_ONLY) return null
    val amountOf: (Record) -> Double? =
        if (kind == EnergyKind.ELECTRIC) { r -> r.kwh } else { r -> r.liters }
    val counted = records.filter { record ->
        Categories.isEnergy(record.category) && record.kind != "manual" && (amountOf(record) ?: 0.0) > 0.0
    }
    val amount = counted.sumOf { amountOf(it) ?: 0.0 }
    val cost = counted.sumOf { costOf(it, ledger) }
    val rate = energy?.rate
    // stats() 只为混动补累计估算（燃油车「无区间就是无区间」）；报告的能源卡需要一条兜底，
    // 这里在没满箱区间时自己算，界面标「估算」。
    val estimated = energy?.estimated ?: if (rate == null) {
        estimateEnergyRate(records, kind)
    } else {
        null
    }
    // 这一期没有任何能源记录（金额、量、区间、估算都没有）时不返回值：报告页整张能源卡不画，
    // 而不是显示「加油 0 次 / 油耗 — / 本期没有加满记录」。
    if (counted.isEmpty() && rate == null && estimated == null && previous == null) return null
    return ReportEnergyLine(
        count = counted.size,
        amount = amount,
        cost = cost,
        unitPrice = if (amount > 0.0) cost / amount else null,
        rate = rate,
        intervals = energy?.intervals?.size ?: 0,
        estimated = estimated,
        previous = previous,
    )
}

/** 单遍汇总：既能给月份点也能给年份点，避免按 12 个月反复扫账本。 */
private fun buildTrends(
    ledger: Ledger,
    period: ReportPeriod,
    firstRecordDate: LocalDate?,
    today: LocalDate,
    spanMonths: Int?,
    filterRange: DateRange?,
): Pair<List<MonthlyTotal>, List<YearlyTotal>> {
    val filterFrom = filterRange?.let { Dates.parse(it.from) }
    val filterTo = filterRange?.let { Dates.parse(it.to) }
    val totals = linkedMapOf<YearMonth, Double>()
    ledger.records.forEach { record ->
        val date = Dates.parse(record.date) ?: return@forEach
        if (filterFrom != null && date < filterFrom) return@forEach
        if (filterTo != null && date > filterTo) return@forEach
        val key = YearMonth.of(date.year, date.monthValue)
        totals[key] = (totals[key] ?: 0.0) + costOf(record, ledger)
    }
    fun monthTotal(ym: YearMonth): Double = totals[ym] ?: 0.0

    return when (period.kind) {
        ReportKind.MONTH -> {
            val end = YearMonth.of(period.year, period.month)
            val months = (11 downTo 0).map { end.minusMonths(it.toLong()) }
            months.map { MonthlyTotal(it.year, it.monthValue, monthTotal(it)) } to emptyList()
        }
        ReportKind.YEAR -> {
            val months = (1..12).map { YearMonth.of(period.year, it) }
            months.map { MonthlyTotal(it.year, it.monthValue, monthTotal(it)) } to emptyList()
        }
        ReportKind.ALL -> {
            val start = firstRecordDate
            if (start == null) {
                // 空账本：给近 12 个月的零柱，页面直接走空态。
                val months = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
                months.map { MonthlyTotal(it.year, it.monthValue, 0.0) } to emptyList()
            } else if ((spanMonths ?: 0) >= 12) {
                val years = (start.year..today.year).map { year ->
                    YearlyTotal(year, totals.filterKeys { it.year == year }.values.sum())
                }
                emptyList<MonthlyTotal>() to years
            } else {
                val end = YearMonth.from(today)
                val months = (11 downTo 0).map { end.minusMonths(it.toLong()) }
                months.map { MonthlyTotal(it.year, it.monthValue, monthTotal(it)) } to emptyList()
            }
        }
    }
}

/** 三条「之最」：最大一笔、最贵一次能源、最常去的站点（≥2 次才算）。 */
private fun highlightsOf(ledger: Ledger, records: List<Record>): ReportHighlights {
    val topExpense = records
        .filter { costOf(it, ledger) > 0.0 }
        .sortedWith(compareByDescending<Record> { costOf(it, ledger) }.thenBy { it.date }.thenBy { it.id })
        .firstOrNull()

    val energyRecords = records.filter { Categories.isEnergy(it.category) && it.kind != "manual" }
    fun priceOf(record: Record): Double? {
        val volume = record.liters ?: record.kwh ?: return null
        if (volume <= 0.0) return null
        return record.unitPrice?.takeIf { it > 0.0 } ?: (record.amount / volume).takeIf { it > 0.0 }
    }
    val priciestEnergy = energyRecords
        .mapNotNull { record -> priceOf(record)?.let { record to it } }
        .sortedWith(
            compareByDescending<Pair<Record, Double>> { it.second }
                .thenBy { it.first.date },
        )
        .firstOrNull()
        ?.first

    val topStation = energyRecords
        .filter { !it.location.isNullOrBlank() }
        .groupBy { it.location!!.trim() }
        .map { (station, rows) ->
            StationCount(station, rows.size, rows.sumOf { costOf(it, ledger) })
        }
        .filter { it.count >= 2 }
        .sortedWith(
            compareByDescending<StationCount> { it.count }
                .thenByDescending { it.cost }
                .thenBy { it.station },
        )
        .firstOrNull()

    return ReportHighlights(topExpense = topExpense, priciestEnergy = priciestEnergy, topStation = topStation)
}
