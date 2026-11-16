package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F5 保养里程预测：日均里程与到期天数。 */
class ServicePredictionTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun ledger(vararg readings: Pair<String, Double>): Ledger = blankLedger().copy(
        id = "car-1",
        vehicle = Vehicle(currentKm = readings.maxOfOrNull { it.second } ?: 0.0),
        records = readings.mapIndexed { index, (date, odometer) ->
            Record(id = "r$index", date = date, category = "停车费", amount = 1.0, odometer = odometer)
        },
    )

    @Test
    fun `average daily km over the window`() {
        val car = ledger(
            "2026-08-17" to 42_000.0,
            "2026-10-01" to 43_500.0,
            "2026-11-14" to 45_000.0,
        )
        val daily = averageDailyKm(car, today)
        // 08-17 → 11-14 = 89 天，3,000 km。
        assertEquals(3_000.0 / 89, daily!!, 1e-9)
    }

    @Test
    fun `needs at least two readings`() {
        assertNull(averageDailyKm(ledger("2026-11-01" to 42_000.0), today))
        assertNull(averageDailyKm(blankLedger(), today))
    }

    @Test
    fun `short span and backwards readings are not predicted`() {
        // 跨度 10 天 < 14 天。
        assertNull(averageDailyKm(ledger("2026-11-01" to 42_000.0, "2026-11-11" to 42_500.0), today))
        // 读数倒挂。
        assertNull(averageDailyKm(ledger("2026-09-01" to 42_000.0, "2026-11-01" to 41_000.0), today))
        // 没有位移。
        assertNull(averageDailyKm(ledger("2026-09-01" to 42_000.0, "2026-11-01" to 42_000.0), today))
    }

    @Test
    fun `two readings on the same day use the higher one`() {
        // 同一天两次加油：后录入的读数反而更小，不能把当天末条当成「最后读数」。
        val car = ledger(
            "2026-09-01" to 42_000.0,
            "2026-11-01" to 45_000.0,
            "2026-11-01" to 44_000.0,
        )

        val daily = averageDailyKm(car, today)

        // 09-01 → 11-01 = 61 天，取当天最大读数 45,000 → 3,000 km。
        assertEquals(3_000.0 / 61, daily!!, 1e-9)
    }

    @Test
    fun `readings outside the window are ignored`() {
        // 旧读数在 90 天窗口外，窗口内只剩一条 → 不预测。
        assertNull(averageDailyKm(ledger("2026-01-01" to 30_000.0, "2026-11-01" to 45_000.0), today))
    }

    @Test
    fun `predicts days and date to reach due mileage`() {
        val reminder = Reminder(id = "m1", title = "机油", intervalKm = 10_000.0, dueKm = 46_200.0)
        // 剩 1,200 km，日均 50 km → 24 天。
        assertEquals(24L, predictDueDays(reminder, 45_000.0, 50.0))
        assertEquals(LocalDate.of(2026, 12, 9), predictDueDate(reminder, 45_000.0, 50.0, today))
    }

    @Test
    fun `no prediction when already due or invalid`() {
        val reminder = Reminder(id = "m1", title = "机油", intervalKm = 10_000.0, dueKm = 46_200.0)
        assertNull(predictDueDays(reminder, 46_200.0, 40.0))
        assertNull(predictDueDays(reminder, 47_000.0, 40.0))
        assertNull(predictDueDays(reminder.copy(dueKm = null), 45_000.0, 40.0))
        assertNull(predictDueDays(reminder, 45_000.0, 0.0))
        assertNull(predictDueDays(reminder, 45_000.0, Double.NaN))
    }
}
