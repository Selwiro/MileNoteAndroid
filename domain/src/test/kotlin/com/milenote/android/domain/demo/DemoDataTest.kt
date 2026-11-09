package com.milenote.android.domain.demo

import com.milenote.android.domain.NINE_CATEGORIES
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.completeImportedServices
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.fuelImportConflicts
import com.milenote.android.domain.calc.groupReminders
import com.milenote.android.domain.calc.mergeWithFuelChoices
import com.milenote.android.domain.calc.servicePlanRows
import com.milenote.android.domain.calc.stats
import com.milenote.android.domain.d
import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.normalizeImport
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.powertrainKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web demo.test：演示车库完整性、体验钱迹导入链路、数据只存内存。 */
class DemoDataTest {

    @Test
    fun `demo garage covers two cars nine categories and plan states`() {
        for (dateText in listOf("2026-11-01", "2026-11-30", "2027-01-01")) {
            val date = d(dateText)
            val garage = DemoData.createDemoGarage(date)
            assertEquals(2, GarageOps.normalizeGarage(garage).cars.size)
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
        assertEquals(42000.0, weekend.vehicle.currentKm, 1e-9)
        assertEquals(50000.0, city.vehicle.currentKm, 1e-9)
        assertEquals(8000.0, city.vehicle.startKm!!, 1e-9)
        assertEquals("周末出逃计划", weekend.vehicle.name)
        assertEquals("城市通勤", city.vehicle.name)
        assertEquals("Volkswagen · Golf", weekend.vehicle.model)
        assertEquals("BYD · Dolphin", city.vehicle.model)
        // 燃油车继续用默认 12 项方案
        assertEquals(null, weekend.vehicle.powertrain)
        assertEquals(12, weekend.servicePlan!!.size)
        assertTrue(weekend.reminders.any { it.title == "机油机滤" })
        assertTrue(weekend.records.sumOf { it.amount } > 0)
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
        assertTrue(energy.isNotEmpty())
        assertTrue(energy.all { it.kwh != null && it.kwh!! > 0 })
        assertTrue(energy.all { it.liters == null })
        assertTrue(energy.all { it.fuelGrade == null })
        assertTrue(energy.none { it.source == "小熊油耗" })
        assertTrue(energy.any { it.missed })
        assertTrue(energy.any { !it.full })

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
    }

    @Test
    fun `demo values match web totals`() {
        // 燃油车与 Web `createDemoGarage('2026-11-01')` 逐字一致：¥10,511 / 42,000 km / 6.25 L/100km
        val garage = DemoData.createDemoGarage(d("2026-11-01"))
        val weekend = garage.cars[0]
        val city = garage.cars[1]
        assertEquals(41, weekend.records.size)
        assertEquals(18, weekend.reminders.size)
        assertEquals(10511.0, stats(weekend).total, 1e-9)
        assertEquals(42000.0, stats(weekend).distance, 1e-9)
        assertEquals(6.254545454545454, stats(weekend).energyRate!!, 1e-9)
        assertEquals(8, stats(weekend).intervals.size)
        assertEquals(3117.0, stats(weekend).categories.first { it.name == "油费" }.value, 1e-9)
        assertEquals(3280.0, stats(weekend).categories.first { it.name == "保险" }.value, 1e-9)

        // 纯电城市车：18 条充电记录替换了原来的加油序列，总费用/能耗按 kWh 口径重算
        assertEquals(41, city.records.size)
        assertEquals(12, city.reminders.size)
        assertEquals(7090.9, stats(city).total, 1e-9)
        assertEquals(42000.0, stats(city).distance, 1e-9)
        assertEquals(6.8821292775665395, stats(city).energyRate!!, 1e-9)
        assertEquals(8, stats(city).intervals.size)
        assertNull(stats(city).estimatedEnergyRate)
        assertEquals(402.9, stats(city).categories.first { it.name == "油费" }.value, 1e-9)
        assertEquals(2650.0, stats(city).categories.first { it.name == "保险" }.value, 1e-9)
        // 9 类契约：纯电不新增分类，能源费仍记在「油费」桶里
        assertEquals(9, stats(city).categories.size)
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
        assertEquals(42000.0, service.odometer!!, 1e-9)
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

        val items = normalizeImport(rows.drop(1), detectMap(rows[0].map { it.toString() }), "qianji", ev = true)
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
        assertTrue((rows[3][3] as String).contains("总里程42000km"))
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
