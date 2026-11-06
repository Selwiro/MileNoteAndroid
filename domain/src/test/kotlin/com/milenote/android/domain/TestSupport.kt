package com.milenote.android.domain

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.import.ImportItem
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.blankLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** 测试构造器与断言辅助。 */
fun fuelRecord(
    date: String,
    odometer: Double?,
    liters: Double?,
    full: Boolean = true,
    missed: Boolean = false,
    amount: Double = 300.0,
    id: String = Ids.newId(),
    source: String = "",
    sourceId: String? = null,
    fuelCounted: Boolean? = null,
): Record = Record(
    id = id,
    date = date,
    category = "油费",
    amount = amount,
    odometer = odometer,
    liters = liters,
    full = full,
    missed = missed,
    source = source,
    sourceId = sourceId,
    fuelCounted = fuelCounted,
    kind = "expense",
)

fun importedItem(
    record: Record,
    error: String = "",
    force: Boolean = false,
    exclude: Boolean = false,
): ImportItem = ImportItem(row = 2, record = record, error = error, force = force, exclude = exclude)

fun testLedger(
    records: List<Record> = emptyList(),
    reminders: List<Reminder> = emptyList(),
): Ledger = blankLedger().copy(records = records, reminders = reminders)

fun assertClose(expected: Double?, actual: Double?, delta: Double = 1e-9, message: String = "") {
    if (expected == null || actual == null) {
        assertEquals(message, expected, actual)
    } else {
        assertEquals(message, expected, actual, delta)
    }
}

fun assertNullDouble(value: Double?) = assertNull(value)

/** 分类归一后的 9 类集合（演示数据断言用）。 */
val NINE_CATEGORIES: Set<String> = Categories.ALL.toSet()

/** ISO 日期便捷方法。 */
fun d(text: String) = Dates.parse(text)!!
