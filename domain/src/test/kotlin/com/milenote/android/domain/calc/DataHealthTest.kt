package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1 数据体检：规则命中与修复动作。 */
class DataHealthTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun ledger(
        records: List<Record> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        vehicle: Vehicle = Vehicle(currentKm = 50_000.0),
        customCategories: List<String>? = null,
    ): Ledger = blankLedger().copy(
        id = "car-1",
        vehicle = vehicle,
        records = records,
        reminders = reminders,
        customCategories = customCategories,
    )

    private fun codes(ledger: Ledger): List<String> = dataHealth(ledger, today).issues.map { it.code }

    @Test
    fun `clean ledger reports nothing`() {
        val clean = ledger(
            records = listOf(
                Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 10.0, odometer = 49_000.0),
                Record(id = "r2", date = "2026-11-05", category = "油费", amount = 300.0, odometer = 49_600.0, liters = 40.0),
            ),
        )
        assertTrue(dataHealth(clean, today).isEmpty)
    }

    @Test
    fun `duplicate id is an error and keeps the newest record`() {
        val dup = ledger(
            records = listOf(
                Record(id = "same", date = "2026-11-01", category = "停车费", amount = 10.0, updatedAt = 1L),
                Record(id = "same", date = "2026-11-02", category = "停车费", amount = 20.0, updatedAt = 2L),
                Record(id = "other", date = "2026-11-03", category = "停车费", amount = 30.0),
            ),
        )
        val issues = dataHealth(dup, today).issues
        assertEquals(1, issues.size)
        assertEquals(HealthCodes.DUPLICATE_ID, issues.first().code)
        assertEquals(HealthSeverity.ERROR, issues.first().severity)

        val fixed = removeDuplicateRecords(dup, "same")
        assertEquals(2, fixed.ledger.records.size)
        val kept = fixed.ledger.records.first { it.id == "same" }
        assertEquals(20.0, kept.amount, 0.0)
        assertEquals(1, fixed.undo.records.size)
        // 撤销能把删掉的那条原位插回。
        assertEquals(3, restoreRemovedRecords(fixed.ledger, fixed.undo, allowDuplicateIds = true).records.size)
    }

    @Test
    fun `dates invalid before 1970 and future are reported`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "bad", date = "not-a-date", category = "停车费", amount = 1.0),
                Record(id = "old", date = "1899-12-31", category = "停车费", amount = 1.0),
                Record(id = "future", date = "2026-12-01", category = "停车费", amount = 1.0),
            ),
        )
        val issues = dataHealth(dirty, today).issues
        assertTrue(issues.any { it.code == HealthCodes.DATE_INVALID })
        assertTrue(issues.any { it.code == HealthCodes.DATE_BEFORE_1970 })
        val future = issues.first { it.code == HealthCodes.DATE_FUTURE }
        assertEquals(HealthSeverity.INFO, future.severity)
    }

    @Test
    fun `unknown category and invalid amount are errors`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "cat", date = "2026-11-01", category = "不存在的分类", amount = 5.0),
                Record(id = "zero", date = "2026-11-02", category = "停车费", amount = 0.0),
                Record(id = "negative", date = "2026-11-03", category = "停车费", amount = -1.0),
            ),
            customCategories = listOf("宠物"),
        )
        val issues = dataHealth(dirty, today).issues
        assertTrue(issues.any { it.code == HealthCodes.CATEGORY_UNKNOWN && it.recordId == "cat" })
        assertTrue(issues.any { it.code == HealthCodes.AMOUNT_INVALID && it.recordId == "zero" })
        assertTrue(issues.any { it.code == HealthCodes.AMOUNT_INVALID && it.recordId == "negative" })

        // 自定义分类是合法的。
        val custom = ledger(
            records = listOf(Record(id = "pet", date = "2026-11-01", category = "宠物", amount = 5.0)),
            customCategories = listOf("宠物"),
        )
        assertTrue(dataHealth(custom, today).isEmpty)
    }

    @Test
    fun `odometer going backwards and below start are warnings`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 1.0, odometer = 40_000.0),
                Record(id = "r2", date = "2026-11-02", category = "停车费", amount = 1.0, odometer = 39_000.0),
            ),
            vehicle = Vehicle(currentKm = 40_000.0, startKm = 39_500.0),
        )
        val issues = dataHealth(dirty, today).issues
        assertTrue(issues.any { it.code == HealthCodes.ODOMETER_BACKWARDS })
        assertTrue(issues.any { it.code == HealthCodes.ODOMETER_BELOW_START })
    }

    @Test
    fun `mixed energy units and service without odometer`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "mix", date = "2026-11-01", category = "油费", amount = 100.0, liters = 10.0, kwh = 20.0),
                Record(id = "svc", date = "2026-11-02", category = "保养", amount = 500.0),
            ),
        )
        val issues = dataHealth(dirty, today).issues
        assertTrue(issues.any { it.code == HealthCodes.MIXED_ENERGY_UNITS })
        assertTrue(issues.any { it.code == HealthCodes.SERVICE_WITHOUT_ODOMETER })
    }

    @Test
    fun `dangling reminder is repaired`() {
        val dirty = ledger(
            records = listOf(Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 1.0)),
            reminders = listOf(
                Reminder(id = "m1", title = "机油", intervalKm = 10_000.0, dueKm = 40_000.0, recordId = "gone"),
                Reminder(id = "m2", title = "轮胎", intervalKm = 5_000.0, baseKm = 40_000.0),
            ),
        )
        val issues = dataHealth(dirty, today).issues
        assertTrue(issues.any { it.code == HealthCodes.REMINDER_DANGLING && it.reminderId == "m1" })
        assertTrue(issues.any { it.code == HealthCodes.REMINDER_NO_DUE_KM && it.reminderId == "m2" })

        val repaired = repairReminders(dirty, today)
        assertNull(repaired.reminders.first { it.id == "m1" }.recordId)
        assertEquals(45_000.0, repaired.reminders.first { it.id == "m2" }.dueKm!!, 0.0)
        assertTrue(dataHealth(repaired, today).issues.none { it.code.startsWith("REMINDER_") })
    }

    @Test
    fun `time reminder without due date gets one`() {
        val dirty = ledger(
            reminders = listOf(
                Reminder(id = "m1", title = "空调滤芯", intervalMonths = 12, baseDate = "2026-01-10"),
            ),
        )
        assertTrue(codes(dirty).contains(HealthCodes.REMINDER_NO_DUE_DATE))
        val repaired = repairReminders(dirty, today)
        assertEquals("2027-01-10", repaired.reminders.single().dueDate)
    }

    @Test
    fun `fuel excluded from cost is info and can be marked`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "f1", date = "2026-11-01", category = "油费", amount = 300.0, liters = 40.0, fuelCounted = false),
            ),
        )
        val issue = dataHealth(dirty, today).issues.single { it.code == HealthCodes.FUEL_NOT_COUNTED }
        assertEquals(HealthSeverity.INFO, issue.severity)
        val fixed = markFuelCounted(dirty, "f1")
        assertEquals(true, fixed.records.single().fuelCounted)
        assertTrue(dataHealth(fixed, today).isEmpty)
    }

    @Test
    fun `vehicle mileage conflicts`() {
        val dirty = ledger(
            records = listOf(Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 1.0, odometer = 60_000.0)),
            vehicle = Vehicle(currentKm = 55_000.0, startKm = 56_000.0),
        )
        val issues = dataHealth(dirty, today).issues
        assertNotNull(issues.firstOrNull { it.code == HealthCodes.VEHICLE_MILEAGE_CONFLICT })
        assertNotNull(issues.firstOrNull { it.code == HealthCodes.START_ABOVE_CURRENT })
        assertTrue(issues.all { it.fix == HealthFix.OPEN_VEHICLE })
    }

    @Test
    fun `report counts by severity`() {
        val dirty = ledger(
            records = listOf(
                Record(id = "bad", date = "not-a-date", category = "停车费", amount = 1.0),
                Record(id = "future", date = "2026-12-01", category = "停车费", amount = 1.0),
            ),
        )
        val report = dataHealth(dirty, today)
        assertEquals(1, report.errorCount)
        assertEquals(1, report.infoCount)
        assertEquals(1, report.attentionCount)
    }
}
