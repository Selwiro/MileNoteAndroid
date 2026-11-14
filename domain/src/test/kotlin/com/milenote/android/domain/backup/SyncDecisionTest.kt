package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同步决策（`SyncDecision`）：6 种组合 + 合并 / 冲突路径。 */
class SyncDecisionTest {

    private fun record(id: String, amount: Double = 100.0, updatedAt: Long? = null) = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        kind = "expense",
        updatedAt = updatedAt,
    )

    private fun garage(records: List<Record> = emptyList()) = Garage(
        version = 2,
        activeId = "car1",
        cars = listOf(
            Ledger(version = 1, id = "car1", vehicle = Vehicle(currentKm = 1_000.0), records = records),
        ),
    )

    @Test
    fun `remote missing and local changed - upload`() {
        val plan = SyncDecision.decide(
            local = garage(listOf(record("r1"))),
            remote = null,
            base = null,
            localChanged = true,
            remoteChanged = false,
            now = 1_000L,
        )
        assertTrue(plan is SyncPlan.Upload)
    }

    @Test
    fun `remote missing and local unchanged - up to date`() {
        val plan = SyncDecision.decide(
            local = garage(),
            remote = null,
            base = null,
            localChanged = false,
            remoteChanged = false,
            now = 1_000L,
        )
        assertEquals(SyncPlan.UpToDate, plan)
    }

    @Test
    fun `only local changed - upload`() {
        val plan = SyncDecision.decide(
            local = garage(listOf(record("r1"))),
            remote = garage(),
            base = garage(),
            localChanged = true,
            remoteChanged = false,
            now = 1_000L,
        )
        assertTrue(plan is SyncPlan.Upload)
    }

    @Test
    fun `only remote changed - download`() {
        val remote = garage(listOf(record("r1")))
        val plan = SyncDecision.decide(
            local = garage(),
            remote = remote,
            base = garage(),
            localChanged = false,
            remoteChanged = true,
            now = 1_000L,
        )
        assertTrue(plan is SyncPlan.Download)
        assertEquals(remote, (plan as SyncPlan.Download).garage)
    }

    @Test
    fun `nothing changed - up to date`() {
        val plan = SyncDecision.decide(
            local = garage(listOf(record("r1", updatedAt = 10L))),
            remote = garage(listOf(record("r1", updatedAt = 10L))),
            base = garage(listOf(record("r1", updatedAt = 10L))),
            localChanged = false,
            remoteChanged = false,
            now = 1_000L,
        )
        assertEquals(SyncPlan.UpToDate, plan)
    }

    @Test
    fun `both changed but mergeable - merged upload`() {
        val base = garage(listOf(record("r1", 100.0, 10L), record("r2", 200.0, 10L)))
        val local = garage(listOf(record("r1", 111.0, 20L), record("r2", 200.0, 10L)))
        val remote = garage(listOf(record("r1", 100.0, 10L), record("r2", 222.0, 21L)))

        val plan = SyncDecision.decide(local, remote, base, localChanged = true, remoteChanged = true, now = 1_000L)

        assertTrue(plan is SyncPlan.UploadMerged)
        val merged = (plan as SyncPlan.UploadMerged)
        assertEquals(111.0, merged.garage.cars.single().records[0].amount, 0.0)
        assertEquals(222.0, merged.garage.cars.single().records[1].amount, 0.0)
        assertEquals(1, merged.summary.updated)
    }

    @Test
    fun `both changed with equal timestamps - conflict`() {
        val base = garage(listOf(record("r1", 100.0, 10L)))
        val local = garage(listOf(record("r1", 150.0, 50L)))
        val remote = garage(listOf(record("r1", 300.0, 50L)))

        val plan = SyncDecision.decide(local, remote, base, localChanged = true, remoteChanged = true, now = 1_000L)

        assertTrue(plan is SyncPlan.Conflict)
        assertEquals("r1", (plan as SyncPlan.Conflict).summary.conflicts.single().id)
    }
}
