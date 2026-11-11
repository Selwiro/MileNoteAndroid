package com.milenote.android.domain.calc

import com.milenote.android.domain.testLedger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 计划表「上次已做」到保养记录的互链（P2）：`PlanRow.lastRecordId` 必须指向
 * 与完成日期一致的那条保养记录；没有记录 / 未完成时为空。
 */
class PlanRecordLinkTest {

    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun `done reminder links to its completion record`() {
        val record = Record(
            id = "rec-oil",
            date = "2026-10-01",
            category = "保养",
            amount = 520.0,
            odometer = 12000.0,
            projects = "机油机滤",
        )
        val reminder = Reminder(
            id = "m-oil",
            title = "机油机滤",
            presetKey = "oil",
            done = true,
            completedDate = "2026-10-01",
            completedKm = 12000.0,
            recordId = "rec-oil",
            intervalKm = 10000.0,
            intervalMonths = 12,
            dueDate = "2027-10-01",
            dueKm = 22000.0,
        )
        val ledger = testLedger(records = listOf(record), reminders = listOf(reminder))

        val row = servicePlanRows(ledger, today, 12500.0).first { it.title == "机油机滤" }

        assertEquals("rec-oil", row.lastRecordId)
        assertEquals("2026-10-01", row.completedDate)
    }

    @Test
    fun `historical record links even without a completed reminder`() {
        val record = Record(
            id = "rec-history",
            date = "2026-08-01",
            category = "保养",
            amount = 300.0,
            odometer = 9000.0,
            projects = "空气滤芯",
        )
        val ledger = testLedger(records = listOf(record))

        val row = servicePlanRows(ledger, today, 12500.0).first { it.title == "空气滤芯" }

        assertEquals("rec-history", row.lastRecordId)
    }

    @Test
    fun `row without records has no link`() {
        val ledger = testLedger()

        val row = servicePlanRows(ledger, today, 12500.0).first { it.title == "空气滤芯" }

        assertNull(row.lastRecordId)
        assertEquals("unconfigured", row.status)
    }
}
