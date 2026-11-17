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
 * - 加油量 / 充电量按「区间里程 × 百公里能耗」反推，标定值是燃油 6.6 L/100km、
 *   纯电 13 kWh/100km、插混 3.4 L + 2.9 kWh；每个区间再乘季节系数与单次抖动
 *   （见 [consumptionFactor]），所以单次油耗有 6.0–7.3 这种正常浮动，年均仍回到标定值；
 * - 金额贴真实：95# 7.5–7.9 元/L、家充 0.62 元/度、公共快充 1.2–1.4 元/度；
 *   平均用车成本燃油车约 1.0 元/km、纯电约 0.65 元/km（含保险与停车）；
 * - 去年整年有完整数据，年报可看 2025 / 2026 两年、月报有「去年同月」可比；
 * - 覆盖 9 类费用、未加满 / 未充满 / 漏记 / 缺里程、跨年保险、完成 / 待办 / 逾期保养状态；
 *   加油与充电带加油站 / 充电站地点（编辑页地点、明细页按站点比价用），另有自定义项目与自定义提醒。
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

    /**
     * 按「每次补能的区间里程」累计换算量：未加满的量滚到下次加满，漏记 / 缺里程会重设基准。
     *
     * [take] 的 factor 是本区间的能耗系数（季节 + 单次抖动），只影响这一次反推出来的量，
     * 不会改变标定值本身；未加满的那部分用当时的系数，下一次加满时按自己的系数补足。
     */
    private class Feeder(private val per100: Double) {
        private var anchor: Double? = null
        private var pending = 0.0

        fun take(odometer: Double?, fallback: Double, mode: Fill, factor: Double = 1.0): Double = when (mode) {
            Fill.NO_ODOMETER -> {
                anchor = null
                pending = 0.0
                fallback * factor
            }
            Fill.MISSED -> {
                anchor = odometer
                pending = 0.0
                fallback * factor
            }
            Fill.PARTIAL -> {
                val need = need(odometer, fallback, factor)
                val amount = (need * 0.4).coerceIn(1.0, (need - 1.0).coerceAtLeast(1.0))
                pending += amount
                amount
            }
            Fill.FULL -> {
                val need = need(odometer, fallback, factor)
                val amount = (need - pending).coerceAtLeast(1.0)
                anchor = odometer
                pending = 0.0
                amount
            }
        }

        private fun need(odometer: Double?, fallback: Double, factor: Double): Double {
            val scaled = fallback * factor
            val prev = anchor ?: return scaled
            if (odometer == null) return scaled
            return (odometer - prev).coerceAtLeast(0.0) * per100 * factor / 100.0
        }
    }

    /**
     * 国内成品油「十个工作日一调」的确定性模拟（2026-11-16 用户反馈：演示油价太稳定）。
     *
     * 旧版按 `month % 4` 只做 ±0.09 的月度波动，整月每次加油同一个价，看起来像油价永远不变。
     * 现在改成「调价窗口均值回归 + 站点差异」：
     * - 每 10 天一个调价窗口，稳态波动约 ±0.37 元（95# 两年跨度约 7.0–8.2 元）；
     * - 2025 年整体高 0.16 元（与旧版一致，年报仍有跨年价差）；
     * - 同一窗口内不同站点差 ±0.02 元（燃油）/ ±0.05 元（公共充电），保留会员价 / 优惠的感觉；
     * - 家充仍固定 0.62 元/度（谷电价格本来就长期不变）。
     *
     * 随机步长由整数哈希生成（不用平台随机源），**同一天永远得到同一个价**，演示账本可重复、可断言。
     */
    private fun priceOf(stream: Stream, date: LocalDate, home: Boolean, stationIndex: Int): Double {
        val homePrice = stream.homePrice
        if (home && homePrice != null) return homePrice
        val walk = priceWalk(date.year * 36 + date.dayOfYear / 10) * if (stream.electric) 0.5 else 1.0
        val yearBump = if (!stream.electric && date.year <= 2025) 0.16 else 0.0
        val stationDelta = if (stream.electric) {
            0.05 * ((stationIndex % 3) - 1)
        } else {
            0.02 * ((stationIndex % 3) - 1)
        }
        return stream.basePrice + yearBump + walk + stationDelta
    }

    /**
     * 调价窗口的均值回归游走：`walk = 0.90 × walk + 0.16 × noise`。
     *
     * 纯累加随机游走会很快撞到上限并一直贴边（又是一条水平线）；均值回归的稳态波动约 ±0.37 元，
     * 偶尔到 ±0.7，接近真实油价「涨两周、跌一个月」的形态（95# 两年跨度约 7.0–8.4 元）。
     */
    private fun priceWalk(period: Int): Double {
        var walk = 0.0
        for (index in PriceWalkEpochPeriod until period) {
            walk = (walk * 0.90 + 0.16 * priceNoise(index)).coerceIn(-0.90, 0.90)
        }
        return walk
    }

    /** 整数 → [-1, 1] 的确定性伪随机（整数哈希，纯函数，不依赖平台随机源）。 */
    private fun priceNoise(index: Int): Double {
        var mixed = index * -0x61c88647
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7feb352d
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846ca68b.toInt()
        mixed = mixed xor (mixed ushr 16)
        return (mixed and 0xFFFF) / 65535.0 * 2.0 - 1.0
    }

    /** 游走起点：2024-01，演示账本最多回溯两年，循环次数可控。 */
    private const val PriceWalkEpochPeriod = 2024 * 36

    // ------------------------------------------------------------------ 能耗

    /**
     * 燃油车 / 插混的月度能耗系数（1 月…12 月，均值归一到 1.0）。
     *
     * 冬天冷启动、暖风与短途多，油耗最高；夏天空调次之；4–5 月与 9–10 月最省。
     * 数组在 [consumptionFactor] 里除以自身均值，任何长度账本的年均油耗都不会偏离标定值。
     */
    private val FuelSeason = doubleArrayOf(
        1.08, 1.07, 1.02, 0.96, 0.94, 0.99, 1.03, 1.02, 0.96, 0.94, 1.02, 1.08,
    )

    /** 纯电车的月度电耗系数：低温掉电比燃油明显，冬天高 18–20%，夏天开空调约 +7%。 */
    private val ElectricSeason = doubleArrayOf(
        1.20, 1.18, 1.06, 0.96, 0.92, 1.00, 1.07, 1.06, 0.95, 0.93, 1.06, 1.19,
    )

    /**
     * 单次补能的能耗系数（2026-11-17 用户反馈：演示油耗每次都是 6.6，像写死的）。
     *
     * 真实车的区间油耗随季节、路况、载重、油品/充电桩浮动，不会是一条水平线：
     * - 季节项按月取值并归一（12 个月均值 = 1），年均能耗仍是标定值；
     * - 单次项是 ±2.5%（燃油）/ ±4%（电）的确定性抖动，同一天同一箱永远得到同一个值（演示可重复）；
     * - 8 次里有一次偏高（堵车 / 长途载满）或偏低（高速巡航），幅度再 +/−3%，
     *   但结果钳在 0.88–1.24，不会出现 9 L / 20 kWh 这种夸张数字。
     */
    private fun consumptionFactor(stream: Stream, date: LocalDate, turn: Int): Double {
        val season = if (stream.electric) ElectricSeason else FuelSeason
        val seasonMean = season.average()
        val monthly = season[(date.monthValue - 1).coerceIn(0, 11)] / seasonMean
        val jitter = if (stream.electric) 0.04 else 0.025
        val noise = pathNoise(turn * 37 + date.dayOfYear) * jitter
        val trip = pathNoise(turn * 53 + date.monthValue) * if (turn % 8 == 0) 0.03 else 0.0
        return (monthly + noise + trip).coerceIn(0.88, 1.24)
    }

    /** 整数 → [-1, 1] 的确定性伪随机：与油价用同一套整数哈希，但换一个索引域。 */
    private fun pathNoise(index: Int): Double {
        var mixed = index * 0x2545f491
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x7feb352d
        mixed = mixed xor (mixed ushr 13)
        return (mixed and 0xFFFF) / 65535.0 * 2.0 - 1.0
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

    /** 燃油两厢（虚构品牌，避免出现真实车型），24 个月 × 约 1,100 km，95# 6.6 L/100km。 */
    private fun weekendSpec(): CarSpec = CarSpec(
        id = "demo-weekend",
        name = tr("周末出逃计划", "Weekend escape plan"),
        // 虚构品牌 / 车型：演示数据不出现真实车厂与车型（2026-11-17 用户要求）。
        model = tr("远峰 · T3 1.4T", "Yuanfeng · T3 1.4T"),
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

    /** 纯电两厢（虚构品牌；接手时 8,000 km），24 个月 × 约 900 km，13 kWh/100km。 */
    private fun citySpec(): CarSpec = CarSpec(
        id = "demo-city",
        name = tr("城市通勤", "City commute"),
        model = tr("青禾 · E2", "Qinghe · E2"),
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

    /** 插混三厢（虚构品牌），24 个月 × 约 1,050 km，3.4 L + 2.9 kWh /100km。 */
    private fun phevSpec(): CarSpec = CarSpec(
        id = "demo-phev",
        name = tr("插混通勤", "Plug-in commute"),
        model = tr("景行 · P5", "Jingxing · P5"),
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
                    // 每箱油 / 每次充电按季节与单次路况浮动，年均仍是 spec 里的标定值。
                    val factor = consumptionFactor(stream, month.atDay(dayOfMonth), turn)
                    val quantity = feeders[index].take(odometer, fallback, mode, factor)
                    val stationIndex = turn % stations.size
                    val price = priceOf(stream, month.atDay(dayOfMonth), home, stationIndex)
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
                            stations[stationIndex]
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
            ?: throw IllegalStateException(
                tr("演示数据缺少能源费记录", "Demo data is missing an energy-cost record"),
            )
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
            ?: throw IllegalStateException(
                tr("演示数据缺少小熊油耗记录", "Demo data is missing a Bear-fuel record"),
            )
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
