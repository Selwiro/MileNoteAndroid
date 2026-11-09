package com.milenote.android.ui.screens.editor

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.ui.EditorTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录编辑器状态：纯电（充电量/电价）与燃油（加油量/油价）两套口径。 */
class RecordEditorStateTest {

    private fun ledger(
        records: List<Record> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        ev: Boolean = false,
    ): Ledger = Ledger(
        id = if (ev) "car-ev" else "car-fuel",
        vehicle = Vehicle(
            name = if (ev) "纯电车" else "燃油车",
            powertrain = if (ev) POWERTRAIN_EV else null,
        ),
        records = records,
        reminders = reminders,
    )

    private fun firstServiceReminder(projects: List<String>): Reminder = Reminder(
        id = "r-first",
        title = "首保",
        projects = projects,
        firstOnly = true,
        intervalKm = 5000.0,
        intervalMonths = 3,
        dueDate = "2027-01-01",
        dueKm = 9000.0,
        done = false,
        source = "默认保养方案",
    )

    @Test
    fun `ev editor title and category switch to charging`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        assertTrue(form.isEv)
        assertTrue(form.isFuel)
        assertEquals("记录充电", form.title)
    }

    @Test
    fun `fuel editor keeps refuel title`() {
        val form = RecordEditorState(ledger(), EditorTarget())
        assertEquals("记录加油", form.title)
    }

    @Test
    fun `ev charge amount times price fills payable`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.kwh = "45"
        form.unitPrice = "0.64"
        form.onFuelFieldChanged("kwh")
        assertEquals("28.8", form.payable)
        assertEquals("0", form.discount)
    }

    @Test
    fun `fuel amount times price still fills payable`() {
        val form = RecordEditorState(ledger(), EditorTarget())
        form.liters = "40"
        form.unitPrice = "7.5"
        form.onFuelFieldChanged("liters")
        assertEquals("300", form.payable)
    }

    @Test
    fun `ev kwh and price auto fills amount paid`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.kwh = "45"
        form.unitPrice = "0.64"
        form.onFuelFieldChanged("kwh")
        form.onFuelFieldChanged("unitPrice")
        assertEquals("28.8", form.payable)
        assertEquals("28.8", form.amount)
        assertEquals("0", form.discount)
    }

    @Test
    fun `ev typing price digit by digit after amount keeps energy consistent`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.amount = "28.8"
        form.onFuelFieldChanged("amount")
        for (text in listOf("0", "0.", "0.6", "0.64")) {
            form.unitPrice = text
            form.onFuelFieldChanged("unitPrice")
        }
        assertEquals("45", form.kwh)
        assertEquals("28.8", form.payable)
        assertEquals("0", form.discount)
    }

    @Test
    fun `ev typing price digit by digit keeps amount consistent`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.kwh = "45"
        for (text in listOf("0", "0.", "0.6", "0.64")) {
            form.unitPrice = text
            form.onFuelFieldChanged("unitPrice")
        }
        assertEquals("28.8", form.amount)
        assertEquals("28.8", form.payable)
        assertEquals("0", form.discount)
    }

    @Test
    fun `ev amount and price auto fills charging amount`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.amount = "28.8"
        form.unitPrice = "0.64"
        form.onFuelFieldChanged("amount")
        form.onFuelFieldChanged("unitPrice")
        assertEquals("45", form.kwh)
    }

    @Test
    fun `ev editing amount is not overwritten by auto fill`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.kwh = "45"
        form.unitPrice = "0.64"
        form.onFuelFieldChanged("kwh")
        form.onFuelFieldChanged("unitPrice")
        // 用户手动改实付：不应被自动值抢回
        form.amount = "30"
        form.onFuelFieldChanged("amount")
        assertEquals("30", form.amount)
        assertEquals("28.8", form.payable)
        assertEquals("-1.2", form.discount)
    }

    @Test
    fun `ev build record writes kwh and clears fuel fields`() {
        val form = RecordEditorState(ledger(ev = true), EditorTarget())
        form.amount = "28.8"
        form.kwh = "45"
        form.unitPrice = "0.64"
        form.full = true
        val record = form.buildRecord()
        assertEquals("油费", record.category)
        assertEquals(45.0, record.kwh!!, 1e-9)
        assertNull(record.liters)
        assertNull(record.fuelGrade)
        assertEquals(0.64, record.unitPrice!!, 1e-9)
        assertTrue(record.full)
    }

    @Test
    fun `fuel build record keeps liters and grade`() {
        val form = RecordEditorState(ledger(), EditorTarget())
        form.amount = "300"
        form.liters = "40"
        form.unitPrice = "7.5"
        form.fuelGrade = "95#"
        val record = form.buildRecord()
        assertEquals(40.0, record.liters!!, 1e-9)
        assertNull(record.kwh)
        assertEquals("95#", record.fuelGrade)
    }

    @Test
    fun `ev first service requires its own plan projects`() {
        val reminder = firstServiceReminder(listOf("三电诊断"))
        val form = RecordEditorState(
            ledger(reminders = listOf(reminder), ev = true),
            EditorTarget(category = "保养", serviceReminderId = "r-first"),
        )
        form.amount = "200"
        form.date = "2026-11-09"
        assertEquals("首保项目需包含：三电诊断", form.validate())
        form.projects = listOf("三电诊断")
        form.odometer = "9000"
        assertNull(form.validate())
    }

    @Test
    fun `fuel first service still requires oil and filter`() {
        val reminder = firstServiceReminder(listOf("机油机滤"))
        val form = RecordEditorState(
            ledger(reminders = listOf(reminder)),
            EditorTarget(category = "保养", serviceReminderId = "r-first"),
        )
        form.amount = "200"
        form.date = "2026-11-09"
        assertEquals("首保项目需包含：机油机滤", form.validate())
    }
}
