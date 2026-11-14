package com.milenote.android.domain.demo

import com.milenote.android.domain.NINE_CATEGORIES
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.domain.calc.ReportPeriod
import com.milenote.android.domain.calc.completeImportedServices
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.fuelImportConflicts
import com.milenote.android.domain.calc.groupReminders
import com.milenote.android.domain.calc.mergeWithFuelChoices
import com.milenote.android.domain.calc.report
import com.milenote.android.domain.calc.servicePlanRows
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.d
import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.normalizeImport
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.hasCharging
import com.milenote.android.domain.model.hasFuel
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.powertrainKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 演示账本：结构完整性、数值真实化（T018）、用车报告口径、体验钱迹导入链路、数据只存内存。
 *
 * 数值断言不写死到分：演示账本的验重点是「量级合理 + 口径自洽」，
 * 例如满箱法算出的油耗必须等于设定值、月报里程差不出现 −22,350 这种值。
 */
class DemoDataTest {

    @Test
    fun `demo garage covers three cars nine categories and plan states`() {
        for (dateText in listOf("2026-11-01", "2026-11-30", "2027-01-01")) {
            val date = d(dateText)
            val garage = DemoData.createDemoGarage(date)
            assertEquals(3, GarageOps.normalizeGarage(garage).cars.size)
            assertEquals("blue", garage.theme)
            assertTrue(garage.cars[0].id != garage.cars[1].id)
            for (car in garage.cars) {
                assertTrue(car.records.all { it.date <= dateText })
                assertEquals(NINE_CATEGORIES, car.records.map { it.category }.toSet())
                assertTrue(stats(car).energyRate!! > 0)
                assertTrue(car.records.any { it.full == false })
                val rows = servicePlanRows(car, date, currentKm(car))
                assertTrue(rows.any { it.status == "overdue" })
                assertTrue(rows.any { it.status == "pending" })
                assertTrue(rows.any { it.status == "done" })
                assertTrue(groupReminders(car.reminders, date).near.isNotEmpty())
                assertTrue(car.serviceProjectsByCategory!!["维修"]!!.contains("车窗异响处理"))
            }
        }
    }

    @Test
    fun `demo cars have expected sizes and service plan override`() {
        val garage = DemoData.createDemoGarage(d("2026-11-01"))
        val weekend = garage.cars[0]
        val city = garage.cars[1]
        assertEquals("demo-weekend", weekend.id)
        assertEquals("demo-city", city.id)
        // 燃油车从 0 开始记账，纯电是 8,000 km 接手的二手车；两者都记满「去年 1 月至今」。
        assertEquals(0.0, weekend.vehicle.startKm!!, 1e-9)
        assertEquals(8000.0, city.vehicle.startKm!!, 1e-9)
        assertTrue(weekend.vehicle.currentKm in 22_000.0..27_000.0)
        assertTrue(city.vehicle.currentKm in 26_000.0..31_000.0)
        assertEquals("周末出逃计划", weekend.vehicle.name)
        assertEquals("城市通勤", city.vehicle.name)
        assertEquals("Volkswagen · Golf", weekend.vehicle.model)
        assertEquals("BYD · Dolphin", city.vehicle.model)
        // 燃油车继续用默认 12 项方案
        assertEquals(null, weekend.vehicle.powertrain)
        assertEquals(12, weekend.servicePlan!!.size)
        assertTrue(weekend.reminders.any { it.title == "机油机滤" })
        assertTrue(weekend.records.size > 100)
        assertEquals("95#", weekend.vehicle.fuelGrade)
    }

    @Test
    fun `demo city car is a bev with charge records and ev plan`() {
        val garage = DemoData.createDemoGarage(d("2026-11-01"))
        val weekend = garage.cars[0]
        val city = garage.cars[1]

        // 车辆身份
        assertEquals(POWERTRAIN_EV, city.vehicle.powertrain)
        assertEquals("ev", city.vehicle.powertrainKey)
        assertTrue(city.isEv)
        assertNull(city.vehicle.fuelGrade)
        assertNull(city.vehicle.fuelCostSource)

        // 能源记录：只有 kwh，没有 liters / 燃油标号
        val energy = city.records.filter { Categories.isEnergy(it.category) }
        assertTrue(energy.size > 60)
        assertTrue(energy.all { it.kwh != null && it.kwh!! > 0 })
        assertTrue(energy.all { it.liters == null })
        assertTrue(energy.all { it.fuelGrade == null })
        assertTrue(energy.none { it.source == "小熊油耗" })
        assertTrue(energy.any { it.missed })
        assertTrue(energy.any { !it.full })
        assertTrue(energy.any { it.odometer == null })

        // 纯电 6 项方案
        val plan = city.servicePlan!!
        assertEquals(6, plan.size)
        assertEquals(
            listOf("first", "ev-diag", "cabin", "brake", "coolant", "tires"),
            plan.map { it.key },
        )
        assertEquals("刹车油", plan.first { it.key == "brake" }.title)
        assertEquals(60000.0, plan.first { it.key == "coolant" }.intervalKm!!, 1e-9)
        assertTrue(city.reminders.any { it.title == "三电诊断" })
        assertTrue(city.reminders.none { it.title == "机油机滤" || it.title == "火花塞" || it.title == "皮带" })

        // 燃油车不受影响
        assertTrue(weekend.records.filter { Categories.isEnergy(it.category) }.all { it.liters != null && it.kwh == null })
        assertTrue(weekend.isEv.not())
        // 燃油车没有「缺里程」的能源记录（只有纯电与插混演示这一项）
        assertTrue(weekend.records.filter { Categories.isEnergy(it.category) }.all { it.odometer != null })
    }

    @Test
    fun `demo phev car mixes fuel and charging with thirteen item plan`() {
        val date = d("2026-11-01")
        val phev = DemoData.createDemoGarage(date).cars[2]

        // 车辆身份：插混两类能量都有
        assertEquals("demo-phev", phev.id)
        assertEquals(POWERTRAIN_PHEV, phev.vehicle.powertrain)
        assertEquals("phev", phev.vehicle.powertrainKey)
        assertEquals("BYD · Qin PLUS DM-i", phev.vehicle.model)
        assertTrue(phev.vehicle.hasFuel)
        assertTrue(phev.vehicle.hasCharging)
        assertTrue(phev.isEv.not())

        // 13 项方案 = 燃油 12 项 + 三电诊断（10000 km / 12 月）
        val plan = phev.servicePlan!!
        assertEquals(13, plan.size)
        assertEquals(12, plan.count { it.firstOnly != true })
        val evDiag = plan.first { it.key == "ev-diag" }
        assertEquals("三电诊断", evDiag.title)
        assertEquals(10000.0, evDiag.intervalKm!!, 1e-9)
        assertEquals(12, evDiag.intervalMonths)
        assertEquals(listOf("机油机滤", "三电诊断"), plan.first { it.key == "first" }.projects)
        assertTrue(phev.reminders.any { it.presetKey == "ev-diag" })

        // 双能源：每月一次加油 + 两次充电，一条记录只带一种能量字段
        val energy = phev.records.filter { Categories.isEnergy(it.category) }
        val fuel = energy.filter { it.liters != null }
        val charging = energy.filter { it.kwh != null }
        assertEquals(energy.size, fuel.size + charging.size)
        assertTrue(fuel.size >= 20)
        assertTrue(charging.size >= 40)
        assertTrue(energy.all { (it.liters == null) != (it.kwh == null) })
        assertTrue(charging.all { it.fuelGrade == null })
        // 一次未加满、一次漏记、一次缺里程
        assertTrue(charging.any { !it.full })
        assertTrue(energy.any { it.missed })
        assertTrue(energy.any { it.odometer == null })

        // 双流统计：油耗与电耗各自算得出来
        val result = stats(phev)
        assertEquals(phev.vehicle.currentKm, result.distance, 1e-9)
        assertTrue(result.energyRate!! > 0)
        assertTrue(result.fuelEnergy!!.rate!! > 0)
        assertTrue(result.electricEnergy!!.rate!! > 0)

        // 计划状态齐备：已到期 / 计划中 / 已完成
        val rows = servicePlanRows(phev, date, currentKm(phev))
        assertTrue(rows.any { it.status == "overdue" })
        assertTrue(rows.any { it.status == "pending" })
        assertTrue(rows.any { it.status == "done" })
        assertTrue(phev.records.all { it.date <= "2026-11-01" })
    }

    @Test
    fun `demo totals and unit costs are realistic`() {
        val garage = DemoData.createDemoGarage(d("2026-11-01"))
        val weekend = garage.cars[0]
        val city = garage.cars[1]
        val phev = garage.cars[2]

        // 燃油车：约 1,100 km/月 × 23 个月，95# 7.5 元/L 左右，6.6 L/100km。
        val fuelStats = stats(weekend)
        assertEquals(weekend.vehicle.currentKm, fuelStats.distance, 1e-9)
        assertTrue("燃油总支出 ${fuelStats.total}", fuelStats.total in 24_000.0..34_000.0)
        assertTrue("每公里 ${fuelStats.perKm}", fuelStats.perKm!! in 1.0..1.4)
        assertEquals(6.6, fuelStats.energyRate!!, 0.05)
        assertTrue("满箱法区间 ${fuelStats.intervals.size}", fuelStats.intervals.size >= 30)
        assertEquals(fuelStats.total, fuelStats.categories.sumOf { it.value }, 0.01)
        assertTrue(fuelStats.categories.first { it.name == "油费" }.value > 10_000.0)
        assertTrue(fuelStats.categories.first { it.name == "保险" }.value > 5_000.0)

        // 纯电：约 900 km/月，13 kWh/100km，家充 0.62 元/度为主。
        val evStats = stats(city)
        assertEquals(city.vehicle.currentKm - 8_000.0, evStats.distance, 1e-9)
        assertTrue("纯电每公里 ${evStats.perKm}", evStats.perKm!! in 0.5..1.1)
        assertEquals(13.0, evStats.energyRate!!, 0.1)
        assertNull(evStats.estimatedEnergyRate)
        assertNull(evStats.fuelEnergy)
        val evEnergyCost = evStats.categories.first { it.name == "油费" }.value
        assertTrue("纯电油费桶 $evEnergyCost", evEnergyCost in 800.0..2_500.0)
        assertTrue(evStats.categories.first { it.name == "保险" }.value > 4_000.0)
        // 9 类契约：纯电不新增分类，能源费仍记在「油费」桶里
        assertEquals(9, evStats.categories.size)

        // 插混：油电双流各自成率，合计约 0.9 元/km。
        val phevStats = stats(phev)
        assertEquals(3.4, phevStats.fuelEnergy!!.rate!!, 0.1)
        assertEquals(2.9, phevStats.electricEnergy!!.rate!!, 0.1)
        assertTrue("插混每公里 ${phevStats.perKm}", phevStats.perKm!! in 0.6..1.2)
    }

    @Test
    fun `demo report months and years stay realistic`() {
        val today = d("2026-11-14")
        for (car in DemoData.createDemoGarage(today).cars) {
            // 累计：里程 = 当前里程 − 起始里程，每公里成本在真实区间
            val all = report(car, ReportPeriod(ReportKind.ALL), today)
            assertEquals(currentKm(car) - (car.vehicle.startKm ?: 0.0), all.distance!!, 1e-9)
            assertTrue("${car.id} 累计每公里 ${all.perKm}", all.perKm!! in 0.5..1.4)
            // 趋势卡：账本每个月都有支出，不留空柱
            assertTrue("${car.id} 趋势有空月", all.monthlyTrend.all { it.total > 0.0 })

            for (month in 1..10) {
                val r = report(car, ReportPeriod(ReportKind.MONTH, 2026, month), today)
                assertTrue("${car.id} 2026-$month 里程 ${r.distance}", r.distance!! in 600.0..1_400.0)
                // 9 月有年度保险（一笔三千多），单月每公里天然偏高；其余月份含维修 / 用品也不该失控。
                val perKmCeiling = if (month == 9) 5.0 else 2.8
                assertTrue("${car.id} 2026-$month 每公里 ${r.perKm}", r.perKm!! in 0.3..perKmCeiling)
                // 里程环比：旧演示数据在这里会算出 −22,350 公里
                val monthOverMonth = r.metrics.distance.monthOverMonth
                assertNotNull("${car.id} 2026-$month 缺环比", monthOverMonth)
                assertTrue("${car.id} 2026-$month 里程环比 ${monthOverMonth!!.diff}", abs(monthOverMonth.diff) < 600.0)
            }

            // 本月 vs 去年同月同期：有基期且差距正常（不是几十倍）
            val november = report(car, ReportPeriod(ReportKind.MONTH, 2026, 11), today)
            val yearOverYear = november.metrics.distance.yearOverYear
            assertNotNull("${car.id} 缺去年同月基期", yearOverYear)
            assertTrue("${car.id} 本月里程同比 ${yearOverYear!!.diff}", abs(yearOverYear.diff) < 600.0)
            val perKmYoy = november.metrics.perKm.yearOverYear
            assertNotNull("${car.id} 缺每公里同比", perKmYoy)
            assertTrue("${car.id} 每公里同比 ${perKmYoy!!.percent}", abs(perKmYoy.percent!!) < 100.0)

            // 年报：今年与去年同长度对比，里程差在 10% 以内；去年整年也必须真有数据
            val thisYear = report(car, ReportPeriod(ReportKind.YEAR, 2026), today)
            val lastYear = report(car, ReportPeriod(ReportKind.YEAR, 2025), today)
            assertTrue("${car.id} 2025 年报没有数据", lastYear.hasData)
            assertTrue("${car.id} 2026 年报没有数据", thisYear.hasData)
            val yearDiff = thisYear.metrics.distance.yearOverYear!!.diff
            assertTrue("${car.id} 年报里程同比 $yearDiff", abs(yearDiff) < 1_600.0)
        }
    }

    @Test
    fun `demo odometer readings never go backwards`() {
        for (car in DemoData.createDemoGarage(d("2026-11-14")).cars) {
            val byDate = car.records.filter { it.odometer != null }.groupBy { it.date }.toSortedMap()
            var previousMax = -1.0
            for ((date, list) in byDate) {
                val min = list.minOf { it.odometer!! }
                assertTrue("${car.id} $date 的里程读数比上一条小（$min < $previousMax）", min >= previousMax)
                previousMax = list.maxOf { it.odometer!! }
            }
        }
    }

    @Test
    fun `demo energy records carry stations and mixed samples`() {
        // 报告页「最常去」与编辑页「加油站 / 充电站」都要有数据；三台车至少三种站点。
        for (car in DemoData.createDemoGarage(d("2026-11-14")).cars) {
            val energy = car.records.filter { Categories.isEnergy(it.category) }
            assertTrue("${car.id} 能源记录缺地点", energy.all { !it.location.isNullOrBlank() })
            assertTrue("${car.id} 能源站点少于 2 个", energy.mapNotNull { it.location }.toSet().size >= 2)
            assertTrue("${car.id} 没有单价", energy.all { it.unitPrice != null && it.unitPrice!! > 0 })
        }
        val top = report(DemoData.createDemoGarage(d("2026-11-14")).cars[0], ReportPeriod(ReportKind.ALL), d("2026-11-14"))
        assertNotNull("报告页「最常去」为空", top.highlights.topStation)
    }

    @Test
    fun `demo qianji sample wires inference conflicts and auto completion`() {
        val date = d("2026-11-01")
        val car = DemoData.createDemoGarage(date).cars[0]
        val rows = DemoData.demoImportRows(car, date)
        val headers = rows[0].map { it.toString() }
        val items = normalizeImport(rows.drop(1), detectMap(headers), "qianji")
        assertTrue(items.all { it.error.isEmpty() })

        val service = items.first { it.record.category == "保养" }.record
        assertEquals(car.vehicle.currentKm, service.odometer!!, 1e-9)
        assertEquals("机油机滤、空气滤芯", service.projects)

        val conflicts = fuelImportConflicts(car.records, items)
        assertEquals(1, conflicts.size)
        val result = mergeWithFuelChoices(car.records, items, mapOf(conflicts[0].date to "bear"))
        val existingIds = car.records.map { it.id }.toSet()
        val added = result.records.filter { it.id !in existingIds }.map { it.id }
        assertEquals(4, added.size)
        val completed = completeImportedServices(car.copy(records = result.records), added)
        assertEquals(2, completed.completed)
    }

    @Test
    fun `demo ev import rows use charging and ev service items`() {
        val date = d("2026-11-01")
        val car = DemoData.createDemoGarage(date).cars[1]
        val rows = DemoData.demoImportRows(car, date)
        assertEquals(5, rows.size)
        assertEquals(listOf("日期", "分类", "金额", "备注", "key"), rows[0])
        // 纯电车给出两笔「电费」，没有小熊油费行
        assertEquals("电费", rows[1][1])
        assertEquals("电费", rows[2][1])
        assertEquals("demo-q-charge-1", rows[1][4])
        assertEquals("demo-q-charge-2", rows[2][4])
        assertTrue((rows[3][3] as String).contains("三电诊断"))
        assertEquals("demo-q-service", rows[3][4])

        val items = normalizeImport(rows.drop(1), detectMap(rows[0].map { it.toString() }), "qianji", powertrain = "ev")
        assertTrue(items.all { it.error.isEmpty() })
        // 「电费」归类到能源分类（存储值仍是「油费」）
        assertEquals(2, items.count { Categories.isEnergy(it.record.category) })
        val service = items.first { it.record.category == "保养" }.record
        assertTrue(service.projects!!.contains("三电诊断"))

        // 纯电车没有小熊/钱迹油费冲突，导入直接合并
        assertTrue(fuelImportConflicts(car.records, items).isEmpty())
        val result = mergeWithFuelChoices(car.records, items)
        val existingIds = car.records.map { it.id }.toSet()
        assertEquals(4, result.records.filter { it.id !in existingIds }.size)
    }

    @Test
    fun `demo rows match web shape`() {
        val date = d("2026-11-01")
        val car = DemoData.createDemoGarage(date).cars[0]
        val rows = DemoData.demoImportRows(car, date)
        assertEquals(5, rows.size)
        assertEquals(listOf("日期", "分类", "金额", "备注", "key"), rows[0])
        assertEquals("demo-q-service", rows[3][4])
        assertEquals("demo-q-beauty", rows[4][4])
        assertTrue((rows[3][3] as String).contains("总里程"))
        assertTrue((rows[3][3] as String).contains("km"))
        assertEquals("油费", rows[1][1])
    }

    @Test
    fun `demo data is regenerated fresh in memory`() {
        val a = DemoData.createDemoGarage(d("2026-11-01"))
        val b = DemoData.createDemoGarage(d("2026-11-01"))
        assertNotNull(a)
        // 每次生成新的 id，不共享可变状态
        assertTrue(a.cars[0].records.first().id != b.cars[0].records.first().id)
        assertEquals(a.cars[0].records.size, b.cars[0].records.size)
        assertEquals(stats(a.cars[0]).total, stats(b.cars[0]).total, 1e-9)
        assertEquals(stats(a.cars[1]).total, stats(b.cars[1]).total, 1e-9)
    }
}
