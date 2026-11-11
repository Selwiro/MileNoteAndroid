package com.milenote.android.platform

import com.milenote.android.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保养提醒 ICS 导出：单条与「一次性导出全部」。 */
class LedgerExportsIcsTest {

    private fun reminder(
        id: String,
        title: String,
        due: String?,
        done: Boolean = false,
        note: String? = null,
    ) = Reminder(id = id, title = title, dueDate = due, done = done, note = note)

    @Test
    fun `single reminder produces one event`() {
        val ics = LedgerExports.ics(reminder("m1", "机油机滤", "2026-12-01"))

        assertNotNull(ics)
        assertTrue(ics!!.startsWith("BEGIN:VCALENDAR"))
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("SUMMARY:机油机滤"))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261201"))
    }

    @Test
    fun `invalid due date cannot be exported`() {
        assertNull(LedgerExports.ics(reminder("m1", "机油机滤", due = "")))
        assertNull(LedgerExports.ics(reminder("m1", "机油机滤", due = null)))
    }

    @Test
    fun `batch export merges every pending reminder into one calendar`() {
        val ics = LedgerExports.icsAll(
            listOf(
                reminder("m1", "机油机滤", "2026-12-01"),
                reminder("m2", "空调滤芯", "2026-11-20", note = "换活性炭"),
                reminder("m3", "已完成项", "2026-10-01", done = true),
                reminder("m4", "没有日期", due = null),
            ),
        )

        assertNotNull(ics)
        assertEquals(1, Regex("BEGIN:VCALENDAR").findAll(ics!!).count())
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("SUMMARY:机油机滤"))
        assertTrue(ics.contains("SUMMARY:空调滤芯"))
        assertTrue(ics.contains("DESCRIPTION:换活性炭"))
        assertTrue(!ics.contains("已完成项"))
        assertTrue(!ics.contains("没有日期"))
    }

    @Test
    fun `batch export returns null when nothing is exportable`() {
        assertNull(LedgerExports.icsAll(emptyList()))
        assertNull(LedgerExports.icsAll(listOf(reminder("m1", "已完成", "2026-10-01", done = true))))
    }

    @Test
    fun `batch file name carries the export date`() {
        val name = LedgerExports.icsAllFileName(java.time.LocalDate.of(2026, 11, 11))
        assertEquals("保养提醒-全部-2026-11-11.ics", name)
    }

    @Test
    fun `excel and csv column counts stay in sync per powertrain`() {
        // CSV 表头现在与工作簿共用同一份定义；这里用真实导出字节做一次口径对齐检查。
        val ledger = com.milenote.android.domain.model.blankLedger().copy(
            records = listOf(
                com.milenote.android.domain.model.Record(id = "r1", date = "2026-11-01", category = "停车费", amount = 20.0),
            ),
        )
        val csvHeader = LedgerExports.csv(ledger).toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .lineSequence()
            .first()
        val csvColumns = csvHeader.split(",").size
        val xlsxHeader = com.milenote.android.domain.export.ExpenseWorkbook.sheets(ledger)
            .first { it.name == "费用明细" }
            .rows.first()
        assertEquals(csvColumns, xlsxHeader.size)
    }
}
