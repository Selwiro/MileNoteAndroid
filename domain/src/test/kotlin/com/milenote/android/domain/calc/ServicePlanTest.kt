package com.milenote.android.domain.calc

import com.milenote.android.domain.d
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web maintenance.test：台账导入、默认方案、周期反算、计划表、进度。 */
class ServicePlanTest {

    @Test
    fun `month end clamp and no cycle next service`() {
        assertEquals("2026-02-28", Dates.addMonths("2026-01-31", 1))
        val next = nextService(
            Reminder(title = "机油", intervalKm = 5000.0, intervalMonths = 6),
            "2026-10-29",
            9000.0,
        )
        assertEquals(14000.0, next!!.dueKm!!, 1e-9)
        assertEquals("2027-04-29", next.dueDate)
        assertNull(nextService(Reminder(title = "机油"), "2026-10-29", 9000.0))
    }

    @Test
    fun `default plan has twelve items and first only once`() {
        assertEquals(12, DEFAULT_SERVICE_PLAN.size)
        val reminders = defaultServiceReminders("2026-01-31", 0.0)
        assertEquals(12, reminders.size)
        assertEquals(5000.0, reminders[0].dueKm!!, 1e-9)
        assertEquals("2026-04-30", reminders[0].dueDate)
        assertNull(nextService(reminders[0], "2026-04-30", 5000.0))
        assertEquals(8000.0, reminders.first { it.title == "空调滤芯" }.dueKm!!, 1e-9)
        assertEquals(9, reminders.first { it.title == "空调滤芯" }.intervalMonths)
        assertEquals(60000.0, reminders.first { it.title == "灰滤" }.intervalKm!!, 1e-9)
        assertNull(reminders.first { it.title == "皮带" }.intervalMonths)
        assertEquals(48, reminders.first { it.title == "变速器油" }.intervalMonths)
        assertEquals(11, defaultServiceReminders("2026-01-01", 0.0, false).size)
        assertEquals(listOf("机油机滤"), reminders[0].projects)
    }

    @Test
    fun `project options dedupe legacy and scope`() {
        val ledger = blankLedger().copy(
            serviceProjects = listOf("洗车", "洗车"),
            records = listOf(
                Record(id = "r", date = "2026-01-01", category = "维修保养", amount = 1.0, projects = "洗车、定制护理"),
            ),
        )
        val options = serviceProjectOptions(ledger)
        assertEquals(1, options.count { it == "洗车" })
        assertTrue(options.contains("定制护理"))
        assertTrue(options.contains("机油机滤"))
        assertTrue(serviceProjectOptions(blankLedger()).contains("洗车"))
        assertTrue(serviceProjectOptions(blankLedger()).contains("补胎"))
        assertTrue(!serviceProjectOptions(blankLedger()).contains("定制护理"))
        assertEquals(listOf("洗车", "机油机滤"), splitProjects("洗车，机油机滤、洗车"))
    }

    @Test
    fun `project options by category`() {
        val ledger = blankLedger().copy(
            serviceProjectsByCategory = mapOf(
                "维修" to listOf("车窗修复"),
                "汽车美容" to listOf("玻璃护理"),
            ),
        )
        val repair = serviceProjectOptions(ledger, "维修")
        val beauty = serviceProjectOptions(ledger, "汽车美容")
        val maintenance = serviceProjectOptions(ledger, "保养")
        assertTrue(repair.contains("补胎") && repair.contains("车窗修复"))
        assertTrue(!repair.contains("机油机滤") && !repair.contains("洗车") && !repair.contains("玻璃护理"))
        assertTrue(beauty.contains("洗车") && beauty.contains("玻璃护理"))
        assertTrue(!beauty.contains("机油机滤") && !beauty.contains("补胎"))
        assertTrue(maintenance.contains("机油机滤"))
        assertTrue(!maintenance.contains("补胎") && !maintenance.contains("洗车"))
    }

    @Test
    fun `reminders grouped within twelve month window`() {
        val reminders = listOf(
            Reminder(id = "today", dueDate = "2026-11-01"),
            Reminder(id = "a", dueDate = "2027-11-01"),
            Reminder(id = "b", dueDate = "2027-11-02"),
            Reminder(id = "c", dueDate = "2028-01-01", dueKm = 100.0),
            Reminder(id = "d", dueKm = 100.0),
            Reminder(id = "e", dueDate = "2026-11-01", done = true),
            Reminder(id = "past", dueDate = "2026-10-31"),
        )
        val groups = groupReminders(reminders, d("2026-11-01"))
        assertEquals(listOf("today", "a"), groups.near.map { it.id })
        assertEquals(listOf("b", "c"), groups.later.map { it.id })
        assertEquals("d", groups.undated[0].id)
        assertEquals("e", groups.done[0].id)
        assertEquals("past", groups.past[0].id)
    }

    @Test
    fun `complete first service resets oil cycle`() {
        val reminders = defaultServiceReminders("2026-01-01", 0.0)
        val result = completeService(
            reminders,
            reminders[0],
            Record(
                id = "first",
                date = "2026-04-01",
                category = "维修保养",
                amount = 0.0,
                odometer = 5000.0,
                projects = "机油机滤、洗车",
            ),
        )
        assertEquals(12, result.size)
        assertEquals(true, result[0].done)
        assertEquals(15000.0, result.first { it.presetKey == "oil" }.dueKm!!, 1e-9)
        assertEquals("2027-04-01", result.first { it.presetKey == "oil" }.dueDate)
        assertEquals(10000.0, reminders[1].dueKm!!, 1e-9)
    }

    @Test
    fun `plan rows distinguish done and pending with overdue`() {
        val record = Record(
            id = "service",
            date = "2026-04-01",
            category = "维修保养",
            amount = 0.0,
            odometer = 5000.0,
            projects = "机油机滤",
        )
        var ledger = testLedger()
        ledger = ledger.copy(reminders = defaultServiceReminders("2026-01-01", 0.0))
        ledger = ledger.copy(records = listOf(record))
        ledger = ledger.copy(reminders = completeService(ledger.reminders, ledger.reminders[0], record))

        val rows = servicePlanRows(ledger, d("2026-11-01"), 9000.0)
        assertEquals(12, rows.size)
        assertEquals("done", rows.first { it.title == "首保" }.status)
        val oil = rows.first { it.title == "机油机滤" }
        assertEquals("pending", oil.status)
        assertEquals(true, oil.hasCompleted)
        assertEquals("2026-04-01", oil.completedDate)
        assertEquals(5000.0, oil.completedKm!!, 1e-9)
        assertEquals(15000.0, oil.current!!.dueKm!!, 1e-9)
        assertEquals("overdue", rows.first { it.title == "空调滤芯" }.status)
        assertEquals("pending", rows.first { it.title == "皮带" }.status)
        assertEquals("overdue", servicePlanRows(ledger, d("2026-11-01"), 100000.0).first { it.title == "皮带" }.status)
        assertTrue(servicePlanRows(blankLedger(), d("2026-11-01"), 0.0).all { it.status == "unconfigured" })
    }

    @Test
    fun `plan rows include custom reminder without leaking other car`() {
        val ledger = testLedger(
            reminders = listOf(
                Reminder(id = "a", title = "洗车", dueDate = "2026-12-01", done = true, completedDate = "2026-11-01"),
            ),
        )
        val rows = servicePlanRows(ledger, d("2026-11-01"), 0.0)
        assertEquals("洗车", rows.last().title)
        assertEquals("done", rows.last().status)
        assertEquals("2026-11-01", rows.last().completedDate)
        assertEquals(12, servicePlanRows(blankLedger(), d("2026-11-01"), 0.0).size)
    }

    @Test
    fun `resolve plan falls back and validates`() {
        val plan = resolveServicePlan(
            listOf(
                PlanEntry(key = "oil", intervalKm = 7500.0, intervalMonths = null),
                PlanEntry(key = "custom-test", title = "洗车", intervalKm = null, intervalMonths = 1),
            ),
        )
        assertEquals(12, plan.first { it.key == "oil" }.intervalMonths)
        assertEquals(7500.0, plan.first { it.key == "oil" }.intervalKm!!, 1e-9)
        assertEquals(1, plan.last().intervalMonths)

        assertThrows(IllegalArgumentException::class.java) {
            resolveServicePlan(listOf(PlanEntry(key = "x", title = "洗车")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveServicePlan(listOf(PlanEntry(key = "x", title = "机油机滤", intervalMonths = 1)))
        }

        val ledger = testLedger()
        val applied = applyServicePlan(ledger, plan, "2026-11-01", 1000.0, true)
        assertEquals(8500.0, applied.reminders.first { it.title == "机油机滤" }.dueKm!!, 1e-9)
        assertEquals("2026-12-01", applied.reminders.first { it.title == "洗车" }.dueDate)

        val updated = applyServicePlan(applied, emptyList(), "2026-11-01", 1000.0, true)
        assertEquals(1, updated.reminders.count { it.title == "机油机滤" })
        assertEquals(11000.0, updated.reminders.first { it.title == "机油机滤" }.dueKm!!, 1e-9)
        assertEquals(0, ledger.reminders.size)
    }

    @Test
    fun `service progress picks higher consumption and resets on next service`() {
        val reminder = Reminder(
            title = "机油机滤",
            intervalKm = 10000.0,
            intervalMonths = 12,
            baseDate = "2026-01-01",
            dueDate = "2027-01-01",
            dueKm = 20000.0,
        )
        assertEquals("km", serviceProgress(reminder, 19000.0, d("2026-02-01"))!!.kind)
        assertEquals("days", serviceProgress(reminder, 11000.0, d("2026-12-01"))!!.kind)
        assertEquals(true, serviceProgress(reminder, 20000.0, d("2026-02-01"))!!.overdue)
        assertEquals(100.0, serviceProgress(reminder, 10000.0, d("2027-01-01"))!!.percent, 1e-9)
        assertNull(serviceProgress(reminder.copy(done = true), 19000.0, d("2026-02-01")))
        assertNull(serviceProgress(Reminder(dueDate = "2027-01-01"), 0.0, d("2026-01-01")))

        val next = nextService(reminder, "2026-11-01", 19000.0)!!
        assertEquals("2026-11-01", next.baseDate)
        assertEquals("2027-11-01", next.dueDate)
        assertEquals(0.0, serviceProgress(next, 19000.0, d("2026-11-01"))!!.percent, 1e-9)
        assertNotNull(next.presetKey ?: "nullable")
    }

    // ---- 纯电保养方案（6 项） ----

    @Test
    fun `ev default plan has six items with ev cycles`() {
        val plan = resolveServicePlan(null, ev = true)
        assertEquals(6, plan.size)
        assertEquals(
            listOf("first", "ev-diag", "cabin", "brake", "coolant", "tires"),
            plan.map { it.key },
        )
        val first = plan.first { it.key == "first" }
        assertEquals("首保", first.title)
        assertEquals(true, first.firstOnly)
        assertEquals(5000.0, first.intervalKm!!, 1e-9)
        assertEquals(3, first.intervalMonths)
        assertEquals(listOf("三电诊断"), first.projects)

        val diag = plan.first { it.key == "ev-diag" }
        assertEquals("三电诊断", diag.title)
        assertEquals(10000.0, diag.intervalKm!!, 1e-9)
        assertEquals(12, diag.intervalMonths)

        assertEquals(8000.0, plan.first { it.key == "cabin" }.intervalKm!!, 1e-9)
        assertEquals(9, plan.first { it.key == "cabin" }.intervalMonths)
        assertEquals("刹车油", plan.first { it.key == "brake" }.title)
        assertEquals(40000.0, plan.first { it.key == "brake" }.intervalKm!!, 1e-9)
        assertEquals(24, plan.first { it.key == "brake" }.intervalMonths)
        assertEquals(60000.0, plan.first { it.key == "coolant" }.intervalKm!!, 1e-9)
        assertEquals(48, plan.first { it.key == "coolant" }.intervalMonths)
        assertEquals(50000.0, plan.first { it.key == "tires" }.intervalKm!!, 1e-9)
        assertNull(plan.first { it.key == "tires" }.intervalMonths)

        // 燃油专属项不得出现在纯电方案
        val titles = plan.map { it.title.orEmpty() }.toSet()
        assertTrue(titles.none { it in FUEL_ONLY_SERVICE_TITLES })
        assertEquals(12, resolveServicePlan(null, ev = false).size)
    }

    @Test
    fun `ev plan keeps user override and drops fuel only keys`() {
        val plan = resolveServicePlan(
            listOf(
                PlanEntry(key = "ev-diag", intervalKm = 15000.0, intervalMonths = 18),
                // 燃油车时代保存的 key：切纯电后应被丢弃，而不是变成自定义项复活
                PlanEntry(key = "oil", intervalKm = 7500.0, intervalMonths = 9),
                PlanEntry(key = "spark", intervalKm = 30000.0, intervalMonths = null),
            ),
            ev = true,
        )
        assertEquals(6, plan.size)
        assertEquals(15000.0, plan.first { it.key == "ev-diag" }.intervalKm!!, 1e-9)
        assertEquals(18, plan.first { it.key == "ev-diag" }.intervalMonths)
        assertTrue(plan.none { it.title == "机油机滤" })
        assertTrue(plan.none { it.title == "火花塞" })
    }

    @Test
    fun `switching powertrain does not resurrect old plan rows`() {
        val fuelLedger = testLedger().copy(
            servicePlan = listOf(
                PlanEntry(key = "oil", intervalKm = 7500.0, intervalMonths = 9),
                PlanEntry(key = "custom-wash", title = "洗车", intervalMonths = 1),
            ),
        )
        assertEquals(13, resolveServicePlan(fuelLedger.servicePlan, ev = false).size)

        val evLedger = fuelLedger.copy(vehicle = fuelLedger.vehicle.copy(powertrain = "ev"))
        val plan = resolveServicePlan(evLedger.servicePlan, ev = evLedger.vehicle.isEv)
        assertEquals(7, plan.size) // 6 项内置 + 1 条自定义
        assertTrue(plan.none { it.key == "oil" })
        assertTrue(plan.any { it.title == "洗车" })

        val rows = servicePlanRows(evLedger, d("2026-11-01"), 20000.0)
        assertTrue(rows.none { it.title == "机油机滤" || it.title == "空气滤芯" || it.title == "火花塞" })
        assertTrue(rows.any { it.title == "三电诊断" })
    }

    @Test
    fun `ev apply plan creates six reminders and ev first note`() {
        val applied = applyServicePlan(blankLedger(POWERTRAIN_EV), emptyList(), "2026-01-01", 0.0, true)
        assertEquals(6, applied.reminders.size)
        assertTrue(applied.reminders.any { it.title == "机油机滤" }.not())

        val first = applied.reminders.first { it.firstOnly == true }
        assertEquals("首保", first.title)
        assertEquals(listOf("三电诊断"), first.projects)
        assertTrue(first.note!!.contains("三电诊断"))
        assertEquals(5000.0, first.dueKm!!, 1e-9)
        assertEquals("2026-04-01", first.dueDate)

        val diag = applied.reminders.first { it.presetKey == "ev-diag" }
        assertEquals(10000.0, diag.dueKm!!, 1e-9)
        assertEquals("2027-01-01", diag.dueDate)
        assertEquals("自定义保养方案", diag.source)

        // 计划表：纯电 6 行，过期/待办状态按公里或日期先到
        val rows = servicePlanRows(applied, d("2026-11-01"), 20000.0)
        assertEquals(6, rows.size)
        assertEquals("overdue", rows.first { it.title == "首保" }.status)
        assertEquals("overdue", rows.first { it.title == "三电诊断" }.status)
        assertEquals("overdue", rows.first { it.title == "空调滤芯" }.status)
        assertEquals("pending", rows.first { it.title == "刹车油" }.status)
        assertEquals("pending", rows.first { it.title == "冷却液" }.status)
        assertEquals("pending", rows.first { it.title == "轮胎" }.status)
    }
}
