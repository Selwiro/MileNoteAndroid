package com.milenote.android.domain.calc

import com.milenote.android.domain.d
import com.milenote.android.domain.evLedger
import com.milenote.android.domain.import.inferServiceNote
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.testLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web maintenance.test：历史反算、未启用方案生成、钱迹保养自动完成。 */
class ServiceHistoryTest {

    @Test
    fun `actual service drives cycle and rolls back`() {
        var ledger = testLedger()
        ledger = ledger.copy(reminders = defaultServiceReminders("2026-01-01", 0.0, false))
        ledger = ledger.copy(
            records = listOf(
                Record(
                    id = "new",
                    date = "2026-05-01",
                    category = "维修保养",
                    amount = 0.0,
                    odometer = 15000.0,
                    projects = "机油机滤、空气滤芯",
                ),
                Record(
                    id = "old",
                    date = "2026-02-01",
                    category = "维修保养",
                    amount = 0.0,
                    odometer = 9000.0,
                    projects = "机油机滤",
                ),
            ),
        )
        fun oil(ledger: com.milenote.android.domain.model.Ledger) =
            ledger.reminders.first { it.title == "机油机滤" && !it.done }

        var next = syncServiceHistory(ledger)
        assertEquals("2027-05-01", oil(next).dueDate)
        assertEquals(25000.0, oil(next).dueKm!!, 1e-9)
        assertEquals(25000.0, next.reminders.first { it.title == "空气滤芯" }.dueKm!!, 1e-9)
        assertEquals(10000.0, ledger.reminders.first { it.title == "机油机滤" }.dueKm!!, 1e-9)
        assertEquals(next, syncServiceHistory(next))

        next = next.copy(
            records = next.records.toMutableList().also {
                it[0] = it[0].copy(date = "2026-06-01", odometer = 16000.0)
            },
        )
        next = syncServiceHistory(next)
        assertEquals("2027-06-01", oil(next).dueDate)
        assertEquals(26000.0, oil(next).dueKm!!, 1e-9)

        next = applyServicePlan(
            next,
            listOf(PlanEntry(key = "oil", intervalKm = 7500.0, intervalMonths = 6)),
            "2026-01-01",
            0.0,
            false,
        )
        assertEquals("2026-12-01", oil(next).dueDate)
        assertEquals(23500.0, oil(next).dueKm!!, 1e-9)

        next = syncServiceHistory(next.copy(records = next.records.drop(1)))
        assertEquals("2026-08-01", oil(next).dueDate)

        next = syncServiceHistory(next.copy(records = emptyList()))
        assertEquals("2026-07-01", oil(next).dueDate)
    }

    @Test
    fun `generates default cycle without plan and does not fake km`() {
        var ledger = testLedger(
            records = listOf(
                Record(
                    id = "r",
                    date = "2026-05-01",
                    category = "维修保养",
                    amount = 0.0,
                    odometer = null,
                    projects = "机油机滤、洗车",
                ),
            ),
        )
        var next = syncServiceHistory(ledger)
        assertEquals(1, next.reminders.size)
        assertEquals("2027-05-01", next.reminders[0].dueDate)
        assertNull(next.reminders[0].dueKm)

        next = syncServiceHistory(next.copy(records = emptyList()))
        assertEquals(0, next.reminders.size)
        assertEquals(0, syncServiceHistory(blankLedger()).reminders.size)
    }

    @Test
    fun `qianji services complete matching reminders and stay idempotent`() {
        var ledger = testLedger(
            records = listOf(
                Record(
                    id = "q1",
                    source = "钱迹",
                    category = "保养",
                    date = "2026-05-01",
                    odometer = 15000.0,
                    projects = "机油机滤、空气滤芯",
                    amount = 300.0,
                ),
            ),
        ).copy(reminders = defaultServiceReminders("2026-01-01", 0.0, false))

        val result = completeImportedServices(ledger, listOf("q1"))
        assertEquals(2, result.completed)
        ledger = ledger.copy(reminders = result.reminders)

        val oil = ledger.reminders.first { it.title == "机油机滤" && !it.done }
        assertEquals("2027-05-01", oil.dueDate)
        assertEquals(25000.0, oil.dueKm!!, 1e-9)
        assertNull(oil.recordId)
        assertEquals("q1", ledger.reminders.first { it.title == "机油机滤" && it.done }.recordId)

        assertEquals(0, completeImportedServices(ledger, listOf("q1")).completed)
        assertEquals(0, completeImportedServices(ledger, emptyList()).completed)

        val synced = syncServiceHistory(ledger)
        assertEquals(1, synced.reminders.count { it.title == "机油机滤" && !it.done })

        val withOld = ledger.copy(
            records = ledger.records + Record(
                id = "old",
                source = "钱迹",
                category = "保养",
                date = "2026-02-01",
                odometer = 5000.0,
                projects = "机油机滤",
                amount = 300.0,
            ),
        )
        assertEquals(0, completeImportedServices(withOld, listOf("old")).completed)
    }

    @Test
    fun `qianji first service and combo matching are conservative`() {
        var ledger = testLedger(
            records = listOf(
                Record(
                    id = "first",
                    source = "钱迹",
                    category = "保养",
                    date = "2026-03-01",
                    odometer = 5000.0,
                    projects = "机油机滤",
                    note = "5000km 首保机油机滤",
                    amount = 0.0,
                ),
            ),
        ).copy(
            reminders = defaultServiceReminders("2026-01-01", 0.0, true) +
                com.milenote.android.domain.model.Reminder(
                    id = "combo",
                    title = "组合保养",
                    projects = listOf("机油机滤", "火花塞"),
                    dueDate = "2026-06-01",
                ),
        )

        val first = completeImportedServices(ledger, listOf("first"))
        assertEquals(1, first.completed)
        assertTrue(first.reminders.first { it.firstOnly == true }.done)
        assertEquals(15000.0, first.reminders.first { it.title == "机油机滤" && !it.done }.dueKm!!, 1e-9)
        assertTrue(!first.reminders.first { it.id == "combo" }.done)

        var noKm = testLedger(
            records = listOf(
                Record(
                    id = "x",
                    source = "钱迹",
                    category = "保养",
                    date = "2026-05-01",
                    odometer = null,
                    projects = "机油机滤",
                    amount = 0.0,
                ),
            ),
        ).copy(reminders = defaultServiceReminders("2026-01-01", 0.0, false))
        val result = completeImportedServices(noKm, listOf("x"))
        assertEquals(1, result.completed)
        val pending = result.reminders.first { !it.done && it.title == "机油机滤" }
        assertNull(pending.dueKm)
        assertEquals("2027-05-01", pending.dueDate)
    }

    @Test
    fun `legacy maintenance category migrates to service`() {
        val ledger = blankLedger().copy(
            records = listOf(
                Record(id = "old", date = "2026-10-01", category = "维修保养", amount = 380.0),
            ),
        )
        val garage = com.milenote.android.domain.backup.GarageOps.migrateLegacy(ledger)
        assertEquals("保养", garage.cars[0].records[0].category)
        assertEquals(380.0, stats(garage.cars[0]).total, 1e-9)
        assertEquals("保养", Categories.categoryOf("维修保养"))
    }

    @Test
    fun `history generated reminder is removed after record deletion`() {
        val record = Record(
            id = "r",
            date = "2026-05-01",
            category = "保养",
            amount = 100.0,
            odometer = 12000.0,
            projects = "机油机滤",
        )
        val synced = syncServiceHistory(testLedger(records = listOf(record)))
        assertEquals(1, synced.reminders.size)
        assertEquals(true, synced.reminders[0].historyGenerated)
        assertEquals("2027-05-01", synced.reminders[0].dueDate)
        val cleared = syncServiceHistory(synced.copy(records = emptyList()))
        assertEquals(0, cleared.reminders.size)
        assertEquals("2026-05-01", d("2026-05-01").toString())
    }

    // ---- 纯电：首保/历史反算 ----

    @Test
    fun `ev qianji first service completes and reschedules diagnostics`() {
        val ledger = evLedger(
            records = listOf(
                Record(
                    id = "ev-first",
                    source = "钱迹",
                    category = "保养",
                    date = "2026-03-01",
                    odometer = 5000.0,
                    projects = "三电诊断",
                    note = "5000km 首保三电诊断",
                    amount = 0.0,
                ),
            ),
        ).copy(reminders = defaultServiceReminders("2026-01-01", 0.0, true, ev = true))

        val result = completeImportedServices(ledger, listOf("ev-first"))
        assertEquals(1, result.completed)
        assertTrue(result.reminders.first { it.firstOnly == true }.done)
        assertEquals(
            15000.0,
            result.reminders.first { it.presetKey == "ev-diag" && !it.done }.dueKm!!,
            1e-9,
        )
        // 同一记录不会重复完成三电诊断循环项
        assertEquals(
            0,
            completeImportedServices(ledger.copy(reminders = result.reminders), listOf("ev-first")).completed,
        )
    }

    @Test
    fun `ev history sync rolls diagnostics cycle from records`() {
        val ledger = evLedger(
            records = listOf(
                Record(
                    id = "diag",
                    date = "2026-05-01",
                    category = "保养",
                    amount = 320.0,
                    odometer = 15000.0,
                    projects = "三电诊断",
                ),
            ),
        ).copy(reminders = defaultServiceReminders("2026-01-01", 0.0, false, ev = true))
        val next = syncServiceHistory(ledger)
        val diag = next.reminders.first { it.title == "三电诊断" && !it.done }
        assertEquals(25000.0, diag.dueKm!!, 1e-9)
        assertEquals("2027-05-01", diag.dueDate)
        // 燃油专属项目不会被历史反算凭空生成
        assertTrue(next.reminders.none { it.title == "机油机滤" })
    }

    @Test
    fun `ev infer service note uses ev project titles`() {
        val record = Record(category = "保养", note = "表显20000km，三电诊断、刹车油")
        val result = inferServiceNote(record, ev = true)
        assertEquals(20000.0, result.odometer!!, 1e-9)
        assertEquals("刹车油、三电诊断", result.projects)
        // 燃油口径仍输出「制动液」
        assertEquals(
            "制动液",
            inferServiceNote(Record(category = "保养", note = "更换刹车油")).projects,
        )
    }
}
