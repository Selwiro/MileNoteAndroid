package com.milenote.android.domain.demo

import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.completeService
import com.milenote.android.domain.calc.syncServiceHistory
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
                name = if (secondary) "城市通勤 · 演示" else "周末出逃 · 演示",
                model = if (secondary) "Toyota · Corolla" else "Volkswagen · Golf",
                plate = if (secondary) "演示 B 号车" else "演示 A 号车",
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
            location = "品牌服务中心",
            note = "首保免费 · 机油机滤",
        )
        ledger.reminders.firstOrNull { it.firstOnly == true }?.let {
            reminders = completeService(reminders, it, first)
        }

        // 4 条已完成的常规保养
        val completions = listOf(
            ServiceCompletion("机油机滤", -8, 33000, 480.0),
            ServiceCompletion("空气滤芯", -11, 30000, 90.0),
            ServiceCompletion("空调滤芯", -6, 34000, 120.0),
            ServiceCompletion("制动液", -12, 28000, 280.0),
        )
        for (spec in completions) {
            val record = add(
                "保养",
                spec.amount + if (secondary) 30.0 else 0.0,
                Dates.addMonths(dateText, spec.months),
                odometer = km(spec.odometer),
                projects = spec.title,
                location = "安心养车服务站",
                note = "已完成 ${spec.title}",
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
                        partial -> "中途未加满，累计到下次满箱计算"
                        fuelIndex == 6 -> "此前漏记，本次满箱重新建立油耗基准"
                        else -> "92# 汽油 · 实付金额"
                    },
                )
                odometer += if (partial) 300 else 650
                fuelIndex++
            }
            add("停车费", if (secondary) 220.0 else 180.0 + (m % 3) * 20, "$key-01", note = "月度停车")
            add("过路费", if (secondary) 35.0 else 66.0 + m * 12, "$key-01", note = "周末出行")
            add("汽车美容", if (secondary) 35.0 else 45.0, "$key-01", projects = "洗车", location = "街角洗车店", note = "外观清洗")
        }

        add("保险", if (secondary) 2650.0 else 3280.0, Dates.addMonths(dateText, -2), note = "交强险及商业险")
        add(
            "维修",
            80.0,
            Dates.addMonths(dateText, -1),
            projects = "补胎",
            odometer = km(40200),
            location = "轮胎服务中心",
            note = "右后轮补胎",
        )
        add(
            "汽车美容",
            360.0,
            Dates.addMonths(dateText, -1),
            projects = "打蜡、内饰清洁",
            location = "亮洁汽车美容",
            note = "车身打蜡与内饰清洁",
        )
        add(
            "保养",
            150.0,
            Dates.addMonths(dateText, -2),
            projects = "天窗轨道润滑",
            odometer = km(39000),
            location = "安心养车服务站",
            note = "自定义保养项目",
        )
        add("罚单", 100.0, Dates.addMonths(dateText, -3), note = "停车罚单（虚构示例）")
        add("汽车用品", 168.0, dateText, note = "后备箱收纳箱")
        add(
            "维修",
            240.0,
            dateText,
            projects = "车窗异响处理",
            location = "安心养车服务站",
            note = "自定义维修项目",
        )

        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "洗车",
            projects = listOf("洗车"),
            dueDate = Dates.addMonths(dateText, 1),
            intervalMonths = 1,
            baseDate = dateText,
            done = false,
            note = "自定义汽车美容提醒",
        )
        reminders = reminders + Reminder(
            id = Ids.newId(),
            title = "检查轮胎",
            dueKm = km(45000),
            done = false,
            note = "仅按里程管理",
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
            listOf(fuel.date, "油费", 200, "加油付款 · 与小熊同日金额冲突", "demo-q-fuel-1"),
            listOf(fuel.date, "油费", 100, "加油拆分付款", "demo-q-fuel-2"),
            listOf(
                dateText,
                "维修保养",
                520,
                "总里程${Numbers.jsString(ledger.vehicle.currentKm)}km，更换机油机滤、空气滤芯",
                "demo-q-service",
            ),
            listOf(dateText, "汽车美容", 68, "洗车、打蜡", "demo-q-beauty"),
        )
    }
}
