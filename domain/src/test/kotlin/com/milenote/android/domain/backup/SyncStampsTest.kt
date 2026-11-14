package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Tombstone
import com.milenote.android.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地修改打点（`SyncStamps`）：只有内容真变化的记录 / 提醒才写 `updatedAt`，
 * 删除产生墓碑、复活移除墓碑。多设备同步靠这套信息做后写胜出与删除传播。
 */
class SyncStampsTest {

    private fun record(id: String, amount: Double = 100.0, updatedAt: Long? = null) = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        kind = "expense",
        updatedAt = updatedAt,
    )

    private fun reminder(id: String, title: String = "换机油", updatedAt: Long? = null) = Reminder(
        id = id,
        title = title,
        intervalKm = 10_000.0,
        done = false,
        updatedAt = updatedAt,
    )

    private fun ledger(
        records: List<Record> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        tombstones: List<Tombstone>? = null,
    ) = Ledger(
        version = 1,
        id = "car1",
        vehicle = Vehicle(name = "测试车", currentKm = 1000.0),
        records = records,
        reminders = reminders,
        tombstones = tombstones,
    )

    private fun garage(ledger: Ledger) = Garage(version = 2, activeId = ledger.id, cars = listOf(ledger))

    @Test
    fun `new record is stamped with now`() {
        val before = garage(ledger())
        val after = garage(ledger(records = listOf(record("r1"))))

        val stamped = SyncStamps.stampChanges(before, after, now = 1_000L)

        assertEquals(1_000L, stamped.cars[0].records.single().updatedAt)
    }

    @Test
    fun `edited record is stamped with now`() {
        val before = garage(ledger(records = listOf(record("r1", amount = 100.0, updatedAt = 500L))))
        val after = garage(ledger(records = listOf(record("r1", amount = 120.0, updatedAt = 500L))))

        val stamped = SyncStamps.stampChanges(before, after, now = 2_000L)

        assertEquals(2_000L, stamped.cars[0].records.single().updatedAt)
    }

    @Test
    fun `unchanged record keeps its old timestamp and is not refreshed`() {
        val before = garage(ledger(records = listOf(record("r1", updatedAt = 500L))))
        val after = garage(ledger(records = listOf(record("r1", updatedAt = 500L))))

        val stamped = SyncStamps.stampChanges(before, after, now = 9_000L)

        assertEquals(500L, stamped.cars[0].records.single().updatedAt)
    }

    @Test
    fun `changing unrelated field does not restamp untouched records`() {
        val before = garage(ledger(records = listOf(record("r1", amount = 10.0, updatedAt = 500L), record("r2", amount = 20.0, updatedAt = 500L))))
        val after = garage(ledger(records = listOf(record("r1", amount = 11.0, updatedAt = 500L), record("r2", amount = 20.0, updatedAt = 500L))))

        val stamped = SyncStamps.stampChanges(before, after, now = 9_000L)

        assertEquals(9_000L, stamped.cars[0].records[0].updatedAt)
        assertEquals(500L, stamped.cars[0].records[1].updatedAt)
    }

    @Test
    fun `deleted record leaves a tombstone at now`() {
        val before = garage(ledger(records = listOf(record("r1"), record("r2"))))
        val after = garage(ledger(records = listOf(record("r1"))))

        val stamped = SyncStamps.stampChanges(before, after, now = 3_000L)

        val tombstones = stamped.cars[0].tombstones.orEmpty()
        assertEquals(1, tombstones.size)
        assertEquals("r2", tombstones.single().id)
        assertEquals(3_000L, tombstones.single().at)
    }

    @Test
    fun `revived record removes its tombstone`() {
        val before = garage(ledger(records = listOf(record("r1")), tombstones = listOf(Tombstone("r2", 1_000L))))
        val after = garage(
            ledger(
                records = listOf(record("r1"), record("r2")),
                tombstones = listOf(Tombstone("r2", 1_000L)),
            ),
        )

        val stamped = SyncStamps.stampChanges(before, after, now = 4_000L)

        assertNull(stamped.cars[0].tombstones)
        assertEquals(4_000L, stamped.cars[0].records.last().updatedAt)
    }

    @Test
    fun `existing tombstones are preserved across saves`() {
        val before = garage(ledger(tombstones = listOf(Tombstone("r9", 1_000L))))
        val after = garage(ledger(records = listOf(record("r1")), tombstones = listOf(Tombstone("r9", 1_000L))))

        val stamped = SyncStamps.stampChanges(before, after, now = 5_000L)

        val tombstones = stamped.cars[0].tombstones.orEmpty()
        assertEquals(listOf("r9"), tombstones.map { it.id })
    }

    @Test
    fun `reminders are stamped on change too`() {
        val before = garage(ledger(reminders = listOf(reminder("m1", updatedAt = 100L))))
        val after = garage(ledger(reminders = listOf(reminder("m1", title = "换机滤", updatedAt = 100L))))

        val stamped = SyncStamps.stampChanges(before, after, now = 6_000L)

        assertEquals(6_000L, stamped.cars[0].reminders.single().updatedAt)
    }

    @Test
    fun `no change produces identical garage`() {
        val before = garage(ledger(records = listOf(record("r1", updatedAt = 10L)), reminders = listOf(reminder("m1", updatedAt = 20L))))

        val stamped = SyncStamps.stampChanges(before, before, now = 7_000L)

        assertEquals(before.cars[0].records, stamped.cars[0].records)
        assertEquals(before.cars[0].reminders, stamped.cars[0].reminders)
        assertNull(stamped.cars[0].tombstones)
    }

    @Test
    fun `content comparison ignores updatedAt only`() {
        val a = record("r1", amount = 1.0, updatedAt = 1L)
        val b = record("r1", amount = 1.0, updatedAt = 99L)
        val c = record("r1", amount = 2.0, updatedAt = 1L)

        assertTrue(SyncStamps.sameRecordContent(a, b))
        assertTrue(!SyncStamps.sameRecordContent(a, c))
    }

    @Test
    fun `new car records are stamped`() {
        val before = garage(ledger())
        val second = Ledger(version = 1, id = "car2", vehicle = Vehicle(currentKm = 0.0), records = listOf(record("r1")))
        val after = before.copy(cars = before.cars + second)

        val stamped = SyncStamps.stampChanges(before, after, now = 8_000L)

        assertNotNull(stamped.cars.last().records.single().updatedAt)
    }
}
