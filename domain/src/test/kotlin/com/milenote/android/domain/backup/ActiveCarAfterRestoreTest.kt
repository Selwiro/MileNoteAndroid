package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 恢复备份后自动选中的车辆（UX3）。
 *
 * 新装 App 恢复备份时当前车还是刚建的空白车，`mergeGarage` 又保持 activeId 不变，
 * 结果首页空空、报告全 0，用户以为恢复失败。恢复后要切到第一辆有内容的车。
 */
class ActiveCarAfterRestoreTest {

    private fun car(
        id: String,
        name: String,
        records: Int = 0,
        reminders: Int = 0,
    ): Ledger = blankLedger().copy(
        id = id,
        vehicle = blankLedger().vehicle.copy(name = name),
        records = List(records) { index ->
            Record(id = "$id-r$index", date = "2026-11-01", category = "油费", amount = 300.0)
        },
        reminders = List(reminders) { index ->
            Reminder(id = "$id-m$index", title = "机油机滤", dueDate = "2027-11-01")
        },
    )

    private fun garage(activeId: String, vararg cars: Ledger): Garage =
        Garage(cars = cars.toList(), activeId = activeId)

    @Test
    fun `blank active car switches to the first car with records`() {
        val blank = car("blank", "我的车")
        val fuel = car("fuel", "燃油车", records = 3)
        val ev = car("ev", "电车", records = 1)
        val result = GarageOps.activeCarWithRecords(garage("blank", blank, fuel, ev))
        assertEquals("fuel", result.activeId)
    }

    @Test
    fun `active car with data is kept`() {
        val blank = car("blank", "我的车")
        val fuel = car("fuel", "燃油车", records = 3)
        val result = GarageOps.activeCarWithRecords(garage("fuel", blank, fuel))
        assertEquals("fuel", result.activeId)
    }

    @Test
    fun `car with only reminders also counts as content`() {
        val blank = car("blank", "我的车")
        val reminded = car("reminded", "换过机滤的车", reminders = 2)
        val result = GarageOps.activeCarWithRecords(garage("blank", blank, reminded))
        assertEquals("reminded", result.activeId)
    }

    @Test
    fun `garage with no content is left untouched`() {
        val a = car("a", "A 车")
        val b = car("b", "B 车")
        val result = GarageOps.activeCarWithRecords(garage("b", a, b))
        assertEquals("b", result.activeId)
    }

    @Test
    fun `already selected populated car is not switched twice`() {
        val fuel = car("fuel", "燃油车", records = 1)
        val result = GarageOps.activeCarWithRecords(garage("fuel", fuel))
        assertEquals("fuel", result.activeId)
        assertEquals(1, result.cars.size)
    }
}
