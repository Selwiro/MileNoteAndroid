package com.milenote.android.domain.demo

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.completeService
import com.milenote.android.domain.calc.syncServiceHistory
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import java.time.LocalDate

/** 演示账本数据（移植 Web `demo.mjs`），只生成内存对象，不落盘。 */
object DemoData {

    /**
     * 生成两辆车的演示车库（Web `createDemoGarage`）。
     *
     * 两台车都覆盖 9 类费用、6 个月油费序列（含未加满、漏记）、
     * 已完成的保养记录与默认保养方案、自定义提醒，合计多种待办状态。
     *
     * @param date 演示基准日（默认今天），确保生成的数据不晚于该日
     */
    fun createDemoGarage(date: LocalDate = Dates.today()): Garage {
        val cars = listOf(build(date, secondary = false), build(date, secondary = true))
        return Garage(version = 2, activeId = cars[0].id, theme = "blue", cars = cars)
    }

    private class ServiceCompletion(
        val title: String,
        val titleEn: String,
        val months: Int,
        val odometer: Int,
        val amount: Double,
    )

    private fun build(date: LocalDate, secondary: Boolean): Ledger {
        val offset = if (secondary) 8000.0 else 0.0
        fun km(value: Int): Double = value + offset
        val dateText = Dates.iso(date)
        val companionDate = Dates.addMonths(date, -25)

        var ledger = blankLedger().copy(
            id = if (secondary) "demo-city" else "demo-weekend",
            vehicle = Vehicle(
                name = if (secondary) tr("城市通勤 · 演示", "City commute · Demo") else tr("周末出逃 · 演示", "Weekend escape · Demo"),
                model = if (secondary) "Toyota · Corolla" else "Volkswagen · Golf",
                plate = if (secondary) tr("演示 B 号车", "Demo car B") else tr("演示 A 号车", "Demo car A"),
                companionDate = Dates.iso(companionDate),
                startKm = offset,
                currentKm = km(42000),
                fuelCostSource = "combined",
            ),
        )

        val plan = if (secondary) {
            listOf(PlanEntry(key = "oil", intervalKm = 7500.0, intervalMonths = 9))
        } else {
            emptyList()
        }
        ledger = applyServicePlan(ledger, plan, Dates.iso(companionDate), offset, true)

        val records = mutableListOf<Record>()
        var reminders = ledger.reminders

        fun add(
            category: String,
            amount: Double,
            day: String,
            odometer: Double? = null,
            liters: Double? = null,
            full: Boolean = false,
            missed: Boolean = false,
            source: String = "演示数据",
            sourceId: String? = null,
            fuelCounted: Boolean? = null,
            note: String = "",
            projects: String? = null,
            location: String? = null,
        ): Record {
            val record = Record(
                id = Ids.newId(),
                date = day,
                category = category,
                amount = amount,
                odometer = odometer,
                liters = liters,
                full = full,
                missed = missed,
                note = note,
                location = location,
                projects = projects,
                source = source,
                sourceId = sourceId,
                kind = "expense",
                fuelCounted = fuelCounted,
            )
            records.add(record)
            return record
        }

        // 首保（免费）
        val first = add(
            "保养",
            0.0,
            Dates.addMonths(dateText, -23),
            odometer = km(5000),
            projects = "机油机滤",
            location = tr("品牌服务中心", "Authorized service center"),
            note = tr("首保免费 · 机油机滤", "Free first service · Oil & filter"),
        )
        ledger.reminders.firstOrNull { it.firstOnly == true }?.let {
            reminders = completeService(reminders, it, first)
        }

        // 4 条已完成的常规保养
        val completions = listOf(
            ServiceCompletion("机油机滤", "Oil & filter", -8, 33000, 480.0),
            ServiceCompletion("空气滤芯", "Air filter", -11, 30000, 90.0),
            ServiceCompletion("空调滤芯", "Cabin filter", -6, 34000, 120.0),
            ServiceCompletion("制动液", "Brake fluid", -12, 28000, 280.0),
        )
        for (spec in completions) {
            val record = add(
                "保养",
                spec.amount + if (secondary) 30.0 else 0.0,
                Dates.addMonths(dateText, spec.months),
                odometer = km(spec.odometer),
                projects = spec.title,
                location = tr("安心养车服务站", "Trusty car care station"),
                note = tr("已完成 ${spec.title}", "Completed ${spec.titleEn}"),
            )
            reminders.firstOrNull { it.title == spec.title && !it.done }?.let {
                reminders = completeService(reminders, it, record)
            }
        }

        // 6 个月油费序列：含未加满、漏记
        var odometer = km(34000)
        var fuelIndex = 0
        val currentMonth = date.withDayOfMonth(1)
        for (m in 5 downTo 0) {
            val month = currentMonth.minusMonths(m.toLong())
            val key = "%04d-%02d".format(month.year, month.monthValue)
            for (day in listOf(1, 18)) {
                val whenText = "$key-${day.toString().padStart(2, '0')}"
                if (whenText > dateText) continue
                val partial = fuelIndex == 3
                val liters = if (partial) 20.0 else 38.0 + (fuelIndex % 3) * 2
                val price = 7.35 + (fuelIndex % 3) * 0.08
                add(
                    "油费",
                    Math.round(liters * price).toDouble(),
                    whenText,
                    odometer = odometer,
                    liters = liters,
                    full = !partial,
                    missed = fuelIndex == 6,
                    source = "小熊油耗",
                    sourceId = "demo-bear-${ledger.id}-$fuelIndex",
                    fuelCounted = true,
                    note = when {
                        partial -> tr("中途未加满，累计到下次满箱计算", "Partial fill — carried over to the next full tank")
                        fuelIndex == 6 -> tr("此前漏记，本次满箱重新建立油耗基准", "Previously missed — this full tank re-establishes the fuel-economy baseline")
                        else -> tr("92# 汽油 · 实付金额", "92# gasoline · paid amount")
                    },
                )
                odometer += if (partial) 300 else 650
                fuelIndex++
            }
            add("停车费", if (secondary) 220.0 else 180.0 + (m % 3) * 20, "$key-01", note = tr("月度停车", "Monthly parking"))
            add("过路费", if (secondary) 35.0 else 66.0 + m * 12, "$key-01", note = tr("周末出行", "Weekend trip"))
            add(
                "汽车美容",
                if (secondary) 35.0 else 45.0,
                "$key-01",
                projects = "洗车",
                location = tr("街角洗车店", "Corner car wash"),
                note = tr("外观清洗", "Exterior wash"),
            )
        }

        add(
            "保险",
            if (secondary) 2650.0 else 3280.0,
            Dates.addMonths(dateText, -2),
            note = tr("交强险及商业险", "Compulsory insurance & commercial insurance"),
        )
        add(
            "维修",
            80.0,
            Dates.addMonths(dateText, -1),
            projects = "补胎",
            odometer = km(40200),
            location = tr("轮胎服务中心", "Tire service center"),
            note = tr("右后轮补胎", "Rear-right tire repair"),
        )
        add(
            "汽车美容",
            360.0,
            Dates.addMonths(dateText, -1),
            projects = "打蜡、内饰清洁",
            location = tr("亮洁汽车美容", "Bright & clean detailing"),
            note = tr("车身打蜡与内饰清洁", "Body waxing & interior cleaning"),
        )
        add(
            "保养",
            150.0,
            Dates.addMonths(dateText, -2),
            projects = "天窗轨道润滑",
            odometer = km(39000),
            location = tr("安心养车服务站", "Trusty car care station"),
            note = tr("自定义保养项目", "Custom service item"),
        )
        add("罚单", 100.0, Dates.addMonths(dateText, -3), note = tr("停车罚单（虚构示例）", "Parking ticket (fictional sample)"))
        add("汽车用品", 168.0, dateText, note = tr("后备箱收纳箱", "Trunk organizer"))
        add(
            "维修",
            240.0,
            dateText,
            projects = "车窗异响处理",
            location = tr("安心养车服务站", "Trusty car care station"),
            note = tr("自定义维修项目", "Custom repair item"),
        )

        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "洗车",
            projects = listOf("洗车"),
            dueDate = Dates.addMonths(dateText, 1),
            intervalMonths = 1,
            baseDate = dateText,
            done = false,
            note = tr("自定义汽车美容提醒", "Custom detailing reminder"),
        )
        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "检查轮胎",
            dueKm = km(45000),
            done = false,
            note = tr("仅按里程管理", "Mileage-based only"),
        )

        ledger = ledger.copy(
            records = records.toList(),
            reminders = reminders,
            serviceProjectsByCategory = mapOf(
                "维修" to listOf("车窗异响处理"),
                "汽车美容" to listOf("玻璃护理"),
                "保养" to listOf("天窗轨道润滑"),
            ),
        )
        return syncServiceHistory(ledger)
    }

    /**
     * 演示「体验钱迹导入」的原始行（Web `demoImportRows`）：
     * 与小熊同日的两笔钱迹油费（含拆分付款）、含备注里程与项目的保养、美容。
     */
    fun demoImportRows(ledger: Ledger, date: LocalDate = Dates.today()): List<List<Any?>> {
        val fuel = ledger.records
            .filter { it.source == "小熊油耗" }
            .sortedByDescending { it.date }
            .firstOrNull()
            ?: throw IllegalStateException("演示账本缺少小熊油耗记录")
        val dateText = Dates.iso(date)
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
