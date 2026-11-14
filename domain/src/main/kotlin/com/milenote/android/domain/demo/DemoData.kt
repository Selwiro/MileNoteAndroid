package com.milenote.android.domain.demo

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.completeService
import com.milenote.android.domain.calc.syncServiceHistory
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.model.isEv
import java.time.LocalDate
import java.time.YearMonth

/**
 * 演示账本数据：只生成内存对象，不落盘；车牌 / 加油站 / 门店 / 订单号全部合成。
 *
 * 2026-11 重做（T018「数值真实化」）：旧版只有最近 6 个月能源记录、其余月份稀疏，
 * 用车报告（0.41.0）的月报 / 年报算出来的里程差与环比同比会出现 −22,350 公里、125.9× 这种
 * 数字，平均用车成本也会低到 0.05 元/公里。现在改成**从去年 1 月至今、每月都有记录**的台账：
 *
 * - 里程读数由「起始里程 + 逐月行驶里程 + 月内按日插值」同一个单调函数生成，
 *   月报 / 年报的里程差、环比同比都有真实基期，不会再出现负数里程或几十倍的涨幅；
 * - 加油量 / 充电量按「区间里程 × 百公里能耗」反推，满箱法 / 满电法算出来的油耗电耗
 *   就是设定值（燃油 6.6 L/100km、纯电 13 kWh/100km、插混 3.4 L + 2.9 kWh）；
 * - 金额贴真实：95# 7.5–7.9 元/L、家充 0.62 元/度、公共快充 1.2–1.4 元/度；
 *   平均用车成本燃油车约 1.0 元/km、纯电约 0.65 元/km（含保险与停车）；
 * - 去年整年有完整数据，年报可看 2025 / 2026 两年、月报有「去年同月」可比；
 * - 覆盖 9 类费用、未加满 / 未充满 / 漏记 / 缺里程、跨年保险、完成 / 待办 / 逾期保养状态；
 *   加油与充电带加油站 / 充电站地点（报告页「最常去」），另有自定义项目与自定义提醒。
 *
 * 三台车的定位不变：`demo-weekend` 燃油（12 项方案）、`demo-city` 纯电（6 项）、
 * `demo-phev` 插混（13 项，油电双流）。
 */
object DemoData {

    /**
     * 生成三辆车的演示车库。
     *
     * @param date 演示基准日（默认今天），所有记录都不晚于该日
     */
    fun createDemoGarage(date: LocalDate = Dates.today()): Garage {
        val cars = listOf(
            build(date, weekendSpec()),
            build(date, citySpec()),
            build(date, phevSpec()),
        )
        return Garage(version = 2, activeId = cars[0].id, theme = "blue", cars = cars)
    }

    // ------------------------------------------------------------------ 口径

    /** 一年公里数的确定性波动（0.82–1.18，连续 7 个月平均正好 1.0）。 */
    private fun monthlyFactor(index: Int): Double = 0.82 + 0.36 * ((index * 5) % 7) / 6.0

    private fun money(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    /** 一次补能的记法：加满 / 未加满 / 漏记后的基准 / 缺里程（补记）。 */
    private enum class Fill { FULL, PARTIAL, MISSED, NO_ODOMETER }

    /**
     * 一条能源流（燃油或电荷）的口径。
     *
     * @property per100 百公里能耗（燃油 L / 纯电 kWh），按**总行驶里程**折算，
     *   因此满箱 / 满电法算出来的区间能耗就等于它
     * @property days 每月固定补能日（≤ 28）
     * @property homePrice 家充单价；为 null 表示这条流没有「家充」概念（燃油流）
     * @property partialAt / missedAt / noOdometerAt 第几次补能做特殊样本（-1 表示不做）
     */
    private class Stream(
        val electric: Boolean,
        val per100: Double,
        val days: List<Int>,
        val homePrice: Double?,
        val basePrice: Double,
        val priceStep: Double,
        val source: String,
        val idPrefix: String,
        val partialAt: Int = -1,
        val missedAt: Int = -1,
        val noOdometerAt: Int = -1,
    )

    /**
     * 一条已完成的保养记录。
     *
     * @property month 相对账本首月的偏移（[ago] 非空时忽略）
     * @property ago 相对「上个月」的偏移（0 = 上个月）；用「多久以前」定位的项目
     *   在任何基准日下都能保证「已逾期」或「待办」状态一致（例如故意晚换的空调滤芯）
     */
    private class Service(
        val month: Int,
        val day: Int,
        val amount: Double,
        /** 记录上的「项目」，数据值（不翻译）。 */
        val projects: String,
        /** 需要标记完成的提醒标题，数据值。 */
        val completes: List<String>,
        val ago: Int? = null,
    )

    /** 一台演示车的全部口径。 */
    private class CarSpec(
        val id: String,
        val name: String,
        val model: String,
        val plate: String,
        val powertrain: String?,
        val startKm: Double,
        val monthlyKm: Double,
        val fuelCostSource: String?,
        val fuelGrade: String?,
        val streams: List<Stream>,
        val services: List<Service>,
        val insurance: Double,
        val parking: Double,
        val toll: Double,
        val wash: Double,
        val gear: Double,
    )

    /** 按「每次补能的区间里程」累计换算量：未加满的量滚到下次加满，漏记 / 缺里程会重设基准。 */
    private class Feeder(private val per100: Double) {
        private var anchor: Double? = null
        private var pending = 0.0

        fun take(odometer: Double?, fallback: Double, mode: Fill): Double = when (mode) {
            Fill.NO_ODOMETER -> {
                anchor = null
                pending = 0.0
                fallback
            }
            Fill.MISSED -> {
                anchor = odometer
                pending = 0.0
                fallback
            }
            Fill.PARTIAL -> {
                val need = need(odometer, fallback)
                val amount = (need * 0.4).coerceIn(1.0, (need - 1.0).coerceAtLeast(1.0))
                pending += amount
                amount
            }
            Fill.FULL -> {
                val need = need(odometer, fallback)
                val amount = (need - pending).coerceAtLeast(1.0)
                anchor = odometer
                pending = 0.0
                amount
            }
        }

        private fun need(odometer: Double?, fallback: Double): Double {
            val prev = anchor ?: return fallback
            if (odometer == null) return fallback
            return (odometer - prev).coerceAtLeast(0.0) * per100 / 100.0
        }
    }

    /** 油价 / 电价：按月份小幅波动，2025 年油价整体高约 0.16 元。 */
    private fun priceOf(stream: Stream, month: YearMonth, home: Boolean): Double {
        val homePrice = stream.homePrice
        if (home && homePrice != null) return homePrice
        val yearBump = if (!stream.electric && month.year <= 2025) 0.16 else 0.0
        return stream.basePrice + yearBump + stream.priceStep * ((month.monthValue % 4) - 1.5)
    }

    // ------------------------------------------------------------------ 三台车

    private fun fuelStations(): List<String> = listOf(
        tr("中石化 · 建设路站", "Sinopec · Jianshe Road"),
        tr("中石油 · 环城东路站", "PetroChina · Huancheng East"),
        tr("壳牌 · 滨江大道站", "Shell · Binjiang Avenue"),
    )

    private fun evStations(): List<String> = listOf(
        tr("特来电 · 万象城站", "TELD · MixC station"),
        tr("国家电网 · 高速服务区站", "State Grid · Expressway stop"),
        tr("星星充电 · 科技园站", "Star Charge · Tech Park"),
    )

    private fun shopCare(): String = tr("安心养车服务站", "Trusty car care station")

    private fun shopBrand(): String = tr("品牌服务中心", "Authorized service center")

    private fun shopWash(): String = tr("街角洗车店", "Corner car wash")

    private fun shopDetail(): String = tr("亮洁汽车美容", "Bright & clean detailing")

    private fun shopTire(): String = tr("轮胎服务中心", "Tire service center")

    /** 燃油车：Golf，24 个月 × 约 1,100 km，95# 6.6 L/100km。 */
    private fun weekendSpec(): CarSpec = CarSpec(
        id = "demo-weekend",
        name = tr("周末出逃计划", "Weekend escape plan"),
        model = "Volkswagen · Golf",
        plate = tr("A 号车", "Car A"),
        powertrain = null,
        startKm = 0.0,
        monthlyKm = 1100.0,
        fuelCostSource = "combined",
        fuelGrade = "95#",
        streams = listOf(
            Stream(
                electric = false,
                per100 = 6.6,
                days = listOf(5, 20),
                homePrice = null,
                basePrice = 7.62,
                priceStep = 0.06,
                source = "小熊油耗",
                idPrefix = "demo-bear-demo-weekend",
                partialAt = 9,
                missedAt = 15,
            ),
        ),
        services = listOf(
            Service(4, 20, 0.0, "机油机滤", listOf("首保")),
            Service(9, 12, 570.0, "机油机滤、空气滤芯", listOf("机油机滤", "空气滤芯")),
            Service(18, 12, 620.0, "机油机滤、空气滤芯", listOf("机油机滤", "空气滤芯")),
            // 上次换空调滤芯后拖了两个多月没去，演示「已逾期」状态。
            Service(0, 16, 130.0, "空调滤芯", listOf("空调滤芯"), ago = 10),
        ),
        insurance = 3280.0,
        parking = 190.0,
        toll = 52.0,
        wash = 45.0,
        gear = 168.0,
    )

    /** 纯电：Dolphin（接手时 8,000 km），24 个月 × 约 900 km，13 kWh/100km。 */
    private fun citySpec(): CarSpec = CarSpec(
        id = "demo-city",
        name = tr("城市通勤", "City commute"),
        model = "BYD · Dolphin",
        plate = tr("B 号车", "Car B"),
        powertrain = POWERTRAIN_EV,
        startKm = 8000.0,
        monthlyKm = 900.0,
        fuelCostSource = null,
        fuelGrade = null,
        streams = listOf(
            Stream(
                electric = true,
                per100 = 13.0,
                days = listOf(4, 13, 24),
                homePrice = 0.62,
                basePrice = 1.28,
                priceStep = 0.05,
                source = "手动记录",
                idPrefix = "demo-ev-demo-city",
                partialAt = 5,
                missedAt = 11,
                noOdometerAt = 17,
            ),
        ),
        services = listOf(
            Service(5, 12, 0.0, "三电诊断", listOf("首保")),
            Service(16, 12, 330.0, "三电诊断", listOf("三电诊断")),
            Service(0, 16, 120.0, "空调滤芯", listOf("空调滤芯"), ago = 10),
        ),
        insurance = 2650.0,
        parking = 200.0,
        toll = 28.0,
        wash = 38.0,
        gear = 168.0,
    )

    /** 插混：Qin PLUS DM-i，24 个月 × 约 1,050 km，3.4 L + 2.9 kWh /100km。 */
    private fun phevSpec(): CarSpec = CarSpec(
        id = "demo-phev",
        name = tr("插混通勤", "Plug-in commute"),
        model = "BYD · Qin PLUS DM-i",
        plate = tr("C 号车", "Car C"),
        powertrain = POWERTRAIN_PHEV,
        startKm = 0.0,
        monthlyKm = 1050.0,
        fuelCostSource = "combined",
        fuelGrade = "92#",
        streams = listOf(
            Stream(
                electric = false,
                per100 = 3.4,
                days = listOf(9),
                homePrice = null,
                basePrice = 7.46,
                priceStep = 0.05,
                source = "小熊油耗",
                idPrefix = "demo-phev-fuel",
                partialAt = 7,
                missedAt = 15,
            ),
            Stream(
                electric = true,
                per100 = 2.9,
                days = listOf(3, 20),
                homePrice = 0.62,
                basePrice = 1.22,
                priceStep = 0.05,
                source = "手动记录",
                idPrefix = "demo-phev-charge",
                partialAt = 10,
                noOdometerAt = 20,
            ),
        ),
        services = listOf(
            Service(5, 20, 0.0, "机油机滤、三电诊断", listOf("首保")),
            Service(9, 12, 620.0, "机油机滤、三电诊断", listOf("机油机滤", "三电诊断")),
            Service(18, 12, 660.0, "机油机滤、三电诊断", listOf("机油机滤", "三电诊断")),
            Service(0, 12, 120.0, "空调滤芯", listOf("空调滤芯"), ago = 10),
        ),
        insurance = 2980.0,
        parking = 170.0,
        toll = 45.0,
        wash = 40.0,
        gear = 128.0,
    )

    // ------------------------------------------------------------------ 生成

    private fun build(date: LocalDate, spec: CarSpec): Ledger {
        val todayText = Dates.iso(date)
        val today = date.dayOfMonth
        // 账本从「上一个自然年 1 月」开始：上一整年数据完整（年报 / 同比都有基期），
        // 而今年是账本的第一年之外的第二年，不会出现只有一个月数据的「上一年」。
        val firstMonth = YearMonth.of(date.year - 1, 1)
        val months = 12 + date.monthValue

        // 逐月行驶里程 + 月首累计里程：里程读数由同一个单调函数生成，任何两条记录都不会倒挂。
        val monthly = DoubleArray(months) { spec.monthlyKm * monthlyFactor(it) }
        val before = DoubleArray(months)
        for (i in 1 until months) before[i] = before[i - 1] + monthly[i - 1]
        val lastMonth = firstMonth.plusMonths((months - 1).toLong())
        val lastDays = lastMonth.lengthOfMonth()
        val totalKm = before[months - 1] + monthly[months - 1] * (today - 1).toDouble() / lastDays

        fun odo(monthIndex: Int, day: Int): Double {
            val dim = firstMonth.plusMonths(monthIndex.toLong()).lengthOfMonth()
            val fraction = (day - 1).coerceIn(0, dim) / dim.toDouble()
            return Math.round(spec.startKm + before[monthIndex] + monthly[monthIndex] * fraction).toDouble()
        }

        var ledger = blankLedger(spec.powertrain).copy(
            id = spec.id,
            vehicle = Vehicle(
                name = spec.name,
                model = spec.model,
                plate = spec.plate,
                companionDate = Dates.iso(firstMonth.atDay(1)),
                startKm = spec.startKm,
                currentKm = Math.round(spec.startKm + totalKm).toDouble(),
                fuelCostSource = spec.fuelCostSource,
                fuelGrade = spec.fuelGrade,
                powertrain = spec.powertrain,
            ),
        )
        // 燃油车 12 项方案 / 纯电 6 项 / 插混 13 项，基准日与基准里程都取账本起点。
        ledger = applyServicePlan(ledger, emptyList(), Dates.iso(firstMonth.atDay(1)), spec.startKm, true)

        val records = mutableListOf<Record>()
        var reminders = ledger.reminders

        fun add(
            category: String,
            amount: Double,
            day: String,
            odometer: Double? = null,
            liters: Double? = null,
            kwh: Double? = null,
            full: Boolean = false,
            missed: Boolean = false,
            source: String = "演示数据",
            sourceId: String? = null,
            fuelCounted: Boolean? = null,
            note: String = "",
            projects: String? = null,
            location: String? = null,
            unitPrice: Double? = null,
            fuelGrade: String? = null,
        ): Record {
            val record = Record(
                id = Ids.newId(),
                date = day,
                category = category,
                amount = amount,
                odometer = odometer,
                liters = liters,
                kwh = kwh,
                full = full,
                missed = missed,
                note = note,
                location = location,
                projects = projects,
                source = source,
                sourceId = sourceId,
                kind = "expense",
                fuelCounted = fuelCounted,
                unitPrice = unitPrice,
                fuelGrade = fuelGrade,
            )
            records.add(record)
            return record
        }

        // 保养记录先按时间顺序落账，completeService 才能把「下一轮」按顺序串起来。
        data class DatedService(val index: Int, val day: Int, val service: Service)
        val completions = mutableListOf<Pair<Service, Record>>()
        val dated = spec.services
            .map { DatedService(it.ago?.let { ago -> months - 1 - ago } ?: it.month, it.day, it) }
            .filter { it.index in 0 until months }
            .sortedWith(compareBy({ it.index }, { it.day }))
        for (entry in dated) {
            val service = entry.service
            val month = firstMonth.plusMonths(entry.index.toLong())
            val day = entry.day.coerceAtMost(month.lengthOfMonth())
            val dateText = Dates.iso(month.atDay(day))
            if (dateText > todayText) continue
            val record = add(
                category = "保养",
                amount = service.amount,
                day = dateText,
                odometer = odo(entry.index, day),
                projects = service.projects,
                location = if (service.amount == 0.0) shopBrand() else shopCare(),
                note = if (service.amount == 0.0) {
                    tr("首保免费 · 已完成 %s", "Free first service · completed %s", service.projects)
                } else {
                    tr("已完成 %s", "Completed %s", service.projects)
                },
            )
            completions += service to record
        }
        for ((service, record) in completions) {
            for (title in service.completes) {
                val target = if (title == "首保") {
                    reminders.firstOrNull { it.firstOnly == true && !it.done }
                } else {
                    reminders.firstOrNull { it.title == title && !it.done }
                }
                if (target != null) reminders = completeService(reminders, target, record)
            }
        }

        val feeders = spec.streams.map { Feeder(it.per100) }
        val counters = IntArray(spec.streams.size)

        for (i in 0 until months) {
            val month = firstMonth.plusMonths(i.toLong())
            val days = month.lengthOfMonth()
            val key = "%04d-%02d".format(month.year, month.monthValue)
            fun dayText(day: Int): String = "$key-${day.toString().padStart(2, '0')}"
            fun past(day: Int): String? = dayText(day).takeIf { it <= todayText }

            // 能源记录：按「区间里程 × 百公里能耗」反推量，金额 = 量 × 当月单价。
            for ((index, stream) in spec.streams.withIndex()) {
                val stations = if (stream.electric) evStations() else fuelStations()
                for (dayOfMonth in stream.days) {
                    if (dayOfMonth > days) continue
                    val dateText = dayText(dayOfMonth)
                    if (dateText > todayText) continue
                    val turn = counters[index]
                    counters[index] = turn + 1
                    val mode = when {
                        turn == stream.partialAt -> Fill.PARTIAL
                        turn == stream.missedAt -> Fill.MISSED
                        turn == stream.noOdometerAt -> Fill.NO_ODOMETER
                        else -> Fill.FULL
                    }
                    val home = stream.homePrice != null && turn % 3 != 2
                    val odometer = if (mode == Fill.NO_ODOMETER) null else odo(i, dayOfMonth)
                    val fallback = monthly[i] / stream.days.size * stream.per100 / 100.0
                    val quantity = feeders[index].take(odometer, fallback, mode)
                    val price = priceOf(stream, month, home)
                    add(
                        category = "油费",
                        amount = money(quantity * price),
                        day = dateText,
                        odometer = odometer,
                        liters = if (stream.electric) null else round2(quantity),
                        kwh = if (stream.electric) round1(quantity) else null,
                        full = mode != Fill.PARTIAL,
                        missed = mode == Fill.MISSED,
                        source = stream.source,
                        sourceId = "${stream.idPrefix}-$turn",
                        fuelCounted = true,
                        unitPrice = round2(price),
                        fuelGrade = if (stream.electric) null else spec.fuelGrade,
                        location = if (stream.electric && home) {
                            tr("小区地库充电桩", "Home garage charger")
                        } else {
                            stations[turn % stations.size]
                        },
                        note = energyNote(spec, stream, mode, home),
                    )
                }
            }

            // 每月固定杂费（停车 / 过路 / 洗车）。
            add("停车费", money(spec.parking + (i % 3) * 20), dayText(1), note = parkingNote(i))
            add("过路费", money(spec.toll + (i % 4) * 12), dayText(1), note = tollNote(i))
            past(6)?.let {
                add(
                    category = "汽车美容",
                    amount = spec.wash,
                    day = it,
                    projects = "洗车",
                    location = shopWash(),
                    note = tr("外观清洗", "Exterior wash"),
                )
            }

            // 保险：每年 9 月续保，跨两个自然年各一笔。
            if (month.monthValue == 9) {
                past(15)?.let {
                    add(
                        category = "保险",
                        amount = spec.insurance,
                        day = it,
                        note = tr("交强险及商业险", "Compulsory insurance & commercial insurance"),
                    )
                }
            }

            // 零散事件：维修 / 美容 / 罚单 / 用品，让明细有得挑、有得筛。
            //
            // 维修 / 美容记录一律不带里程：月报的里程读「本期最后一条读数 − 期前最后一条读数」，
            // 给这类记录补一个当月晚些的读数会让「本月至今」凭空多算半个月（上期同理）。
            when (i) {
                months - 1 -> {
                    past(8)?.let { add("过路费", 35.0, it, note = tr("ETC 充值", "ETC top-up")) }
                    past(12)?.let {
                        add(
                            category = "汽车美容",
                            amount = spec.wash,
                            day = it,
                            projects = "洗车",
                            location = shopWash(),
                            note = tr("内饰吸尘", "Interior vacuuming"),
                        )
                    }
                    add("汽车用品", spec.gear, todayText, note = tr("后备箱收纳箱", "Trunk organizer"))
                }
                months - 2 -> {
                    past(11)?.let {
                        add(
                            category = "维修",
                            amount = 80.0,
                            day = it,
                            odometer = odo(i, 11),
                            projects = "补胎",
                            location = shopTire(),
                            note = tr("右后轮补胎", "Rear-right tire repair"),
                        )
                    }
                    past(24)?.let {
                        add(
                            category = "维修",
                            amount = 240.0,
                            day = it,
                            projects = "车窗异响处理",
                            location = shopCare(),
                            note = tr("自定义维修项目", "Custom repair item"),
                        )
                    }
                }
                months - 3 -> past(19)?.let {
                    add(
                        category = "汽车美容",
                        amount = 180.0,
                        day = it,
                        projects = "打蜡",
                        location = shopDetail(),
                        note = tr("车身打蜡", "Body waxing"),
                    )
                }
                months - 4 -> past(12)?.let {
                    add(
                        category = "保养",
                        amount = 150.0,
                        day = it,
                        odometer = odo(i, 12),
                        projects = "天窗轨道润滑",
                        location = shopCare(),
                        note = tr("自定义保养项目", "Custom service item"),
                    )
                }
                months - 5 -> past(14)?.let {
                    add(
                        category = "汽车美容",
                        amount = 360.0,
                        day = it,
                        projects = "打蜡、内饰清洁",
                        location = shopDetail(),
                        note = tr("车身打蜡与内饰清洁", "Body waxing & interior cleaning"),
                    )
                }
                months - 6 -> past(9)?.let {
                    add(
                        category = "汽车美容",
                        amount = 120.0,
                        day = it,
                        projects = "玻璃护理",
                        location = shopDetail(),
                        note = tr("前挡玻璃镀膜", "Windshield glass coating"),
                    )
                }
                months - 7 -> past(20)?.let {
                    add("罚单", 100.0, it, note = tr("停车罚单（虚构示例）", "Parking ticket (fictional sample)"))
                }
                months - 10 -> past(16)?.let {
                    add("汽车用品", 90.0, it, note = tr("玻璃水与脚垫", "Washer fluid & floor mats"))
                }
                months - 13 -> past(3)?.let {
                    add("罚单", 50.0, it, note = tr("违停罚单（虚构示例）", "Parking ticket (fictional sample)"))
                }
            }
        }

        // 自定义提醒：一条按日期（下个月洗车）、一条只按里程（检查轮胎）。
        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "洗车",
            projects = listOf("洗车"),
            dueDate = Dates.addMonths(todayText, 1),
            intervalMonths = 1,
            baseDate = todayText,
            done = false,
            note = tr("自定义汽车美容提醒", "Custom detailing reminder"),
        )
        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "检查轮胎",
            dueKm = ledger.vehicle.currentKm + 2500.0,
            done = false,
            note = tr("仅按里程管理", "Mileage-based only"),
        )

        ledger = ledger.copy(
            records = records.sortedWith(compareBy({ it.date }, { it.odometer ?: 0.0 })),
            reminders = reminders,
            serviceProjectsByCategory = mapOf(
                "维修" to listOf("车窗异响处理"),
                "汽车美容" to listOf("玻璃护理"),
                "保养" to listOf("天窗轨道润滑"),
            ),
        )
        return syncServiceHistory(ledger)
    }

    private fun parkingNote(index: Int): String =
        if (index % 2 == 0) tr("月度停车", "Monthly parking") else tr("公司停车月卡", "Office parking pass")

    private fun tollNote(index: Int): String =
        if (index % 3 == 0) tr("通勤出行", "Commute tolls") else tr("周末出行", "Weekend trip")

    private fun energyNote(spec: CarSpec, stream: Stream, mode: Fill, home: Boolean): String =
        if (stream.electric) {
            when (mode) {
                Fill.PARTIAL -> tr(
                    "快充至 80%，未充满，累计到下次充满",
                    "Fast charge to 80% — carried over to the next full charge",
                )
                Fill.MISSED -> tr(
                    "此前漏记，本次充满重新建立能耗基准",
                    "Previously missed — this full charge re-establishes the energy baseline",
                )
                Fill.NO_ODOMETER -> tr(
                    "补记充电，当时未记总里程",
                    "Charging logged later — odometer was not recorded",
                )
                Fill.FULL -> if (home) tr("夜间家充", "Overnight home charging") else tr("公共快充", "Public fast charging")
            }
        } else {
            when (mode) {
                Fill.PARTIAL -> tr(
                    "中途未加满，累计到下次满箱计算",
                    "Partial fill — carried over to the next full tank",
                )
                Fill.MISSED -> tr(
                    "此前漏记，本次满箱重新建立油耗基准",
                    "Previously missed — this full tank re-establishes the fuel-economy baseline",
                )
                else -> spec.fuelGrade.orEmpty() + " " + tr("汽油 · 加满", "gasoline · full tank")
            }
        }

    // ------------------------------------------------------------------ 体验导入

    /**
     * 演示「体验钱迹导入」的原始行（Web `demoImportRows`）：
     * 燃油车给与最新小熊油费同日的两笔钱迹油费（含拆分付款）+ 保养 + 美容；
     * 纯电车给同日的两笔充电付款 + 三电诊断 / 空调滤芯保养 + 美容（没有小熊行）。
     */
    fun demoImportRows(ledger: Ledger, date: LocalDate = Dates.today()): List<List<Any?>> {
        val energy = ledger.records
            .filter { Categories.isEnergy(it.category) }
            .sortedByDescending { it.date }
            .firstOrNull()
            ?: throw IllegalStateException("演示账本缺少能源费记录")
        val dateText = Dates.iso(date)
        if (ledger.isEv) {
            return listOf(
                listOf("日期", "分类", "金额", "备注", "key"),
                listOf(
                    energy.date,
                    "电费",
                    36,
                    tr("充电付款 · 与已有充电同日金额冲突", "Charging payment · same-day amount conflict"),
                    "demo-q-charge-1",
                ),
                listOf(energy.date, "电费", 18, tr("充电拆分付款", "Split charging payment"), "demo-q-charge-2"),
                listOf(
                    dateText,
                    "维修保养",
                    260,
                    tr(
                        "总里程%skm，三电诊断、空调滤芯",
                        "Odometer %s km — EV system check, cabin filter",
                        Numbers.jsString(ledger.vehicle.currentKm),
                    ),
                    "demo-q-service",
                ),
                listOf(dateText, "汽车美容", 68, tr("洗车、打蜡", "Car wash, waxing"), "demo-q-beauty"),
            )
        }
        val fuel = ledger.records
            .filter { it.source == "小熊油耗" }
            .sortedByDescending { it.date }
            .firstOrNull()
            ?: throw IllegalStateException("演示账本缺少小熊油耗记录")
        return listOf(
            listOf("日期", "分类", "金额", "备注", "key"),
            listOf(fuel.date, "油费", 200, tr("加油付款 · 与小熊同日金额冲突", "Fuel payment · same-day amount conflict with Bear"), "demo-q-fuel-1"),
            listOf(fuel.date, "油费", 100, tr("加油拆分付款", "Split fuel payment"), "demo-q-fuel-2"),
            listOf(
                dateText,
                "维修保养",
                520,
                tr(
                    "总里程%skm，更换机油机滤、空气滤芯",
                    "Odometer %s km — replaced oil & filter, air filter",
                    Numbers.jsString(ledger.vehicle.currentKm),
                ),
                "demo-q-service",
            ),
            listOf(dateText, "汽车美容", 68, tr("洗车、打蜡", "Car wash, waxing"), "demo-q-beauty"),
        )
    }
}
