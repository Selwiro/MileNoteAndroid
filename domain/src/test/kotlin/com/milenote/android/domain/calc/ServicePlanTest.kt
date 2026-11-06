package com.milenote.android.domain.calc

import com.milenote.android.domain.d
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
    fun `schedule rows generate due date and km`() {
        val headers = listOf("项目", "周期公里数", "周期月数", "上次保养日期", "上次保养公里数")
        val rows = serviceRows(
            listOf(listOf("机油", 5000, 6, "2026-10-01", 10000)),
            serviceMap(headers),
            "schedule",
            "",
            null,
        )
        val row = rows[0]
        assertEquals("", row.error)
        assertEquals("2027-04-01", row.reminder!!.dueDate)
        assertEquals(15000.0, row.reminder.dueKm!!, 1e-9)
    }

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
    fun `history rows keep only date location km projects amount`() {
        val headers = listOf("日期", "时刻", "地点", "公里数", "项目", "费用")
        val rows = serviceRows(
            listOf(
                listOf("2026-10-01", "10:30", "4S店", 10000, "更换滤芯", 380),
                listOf("2026-10-02", "12:00", "4S店", 10100, "保修检查", 0),
            ),
            serviceMap(headers),
            "history",
            "",
            null,
        )
        assertTrue(rows.all { it.error.isEmpty() })
        assertEquals("4S店", rows[0].record!!.location)
        assertNull(rows[0].record!!.time)
        assertEquals("更换滤芯", rows[0].record!!.projects)
        assertEquals(0.0, rows[1].record!!.amount, 1e-9)
        assertEquals("保养", rows[0].record!!.category)
        assertEquals("保养记录导入", rows[0].record!!.source)
    }

    @Test
    fun `invalid schedule and history rows report errors`() {
        val scheduleHeaders = listOf("项目", "周期公里数", "周期月数")
        assertTrue(
            serviceRows(
                listOf(listOf("机油", "", -6)),
                serviceMap(scheduleHeaders),
                "schedule",
                "2026-10-01",
                10000.0,
            )[0].error.isNotEmpty(),
        )
        val historyHeaders = listOf("日期", "项目", "费用")
        assertTrue(
            serviceRows(
                listOf(listOf("2026-10-01", "机油", "")),
                serviceMap(historyHeaders),
                "history",
                "",
                null,
            )[0].error.isNotEmpty(),
        )
        // 月数非整数
        assertTrue(
            serviceRows(
                listOf(listOf("机油", "", 6.5)),
                serviceMap(scheduleHeaders),
                "schedule",
                "2026-10-01",
                10000.0,
            )[0].error.isNotEmpty(),
        )
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
    fun `parse chinese combined cycle text`() {
        assertEquals(CycleText(10000.0, 12.0), parseCycleText("1万公里或1年"))
        assertEquals(CycleText(8000.0, 9.0), parseCycleText("8千公里或9月"))
        assertEquals(CycleText(100000.0, null), parseCycleText("10万公里"))
        assertEquals(CycleText(null, 6.0), parseCycleText("6个月"))
        assertEquals(CycleText(null, null), parseCycleText(""))

        val row = serviceRows(
            listOf(listOf("机油机滤", "1万公里或1年")),
            serviceMap(listOf("项目", "周期")),
            "schedule",
            "2026-01-01",
            0.0,
        )[0]
        assertEquals("", row.error)
        assertEquals(10000.0, row.reminder!!.dueKm!!, 1e-9)
        assertEquals("2027-01-01", row.reminder.dueDate)
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

    @Test
    fun `service map ignores time column equal to date`() {
        val map = serviceMap(listOf("日期", "项目"))
        assertNull(map["time"])
        assertEquals(0, map["date"])
        assertEquals(1, map["project"])
    }
}
