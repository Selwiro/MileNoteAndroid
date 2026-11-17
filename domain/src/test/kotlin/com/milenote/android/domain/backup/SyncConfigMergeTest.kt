package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本级配置的三向合并（1.0 审查 #22）。
 *
 * 真实事故：A 端取消隐藏「停车费」、恢复被忽略的体检项、把分类名还原成默认，B 端只新增了一笔
 * 记录；同步后这些设置又被并集带回来了。设置项必须像记录一样「谁改了听谁的、删除要能传播」。
 */
class SyncConfigMergeTest {

    private fun ledger(
        id: String = "car-1",
        hiddenCategories: List<String>? = null,
        ignoredHealthIssues: List<String>? = null,
        categoryLabels: Map<String, String>? = null,
        records: List<Record> = emptyList(),
    ) = Ledger(
        version = 1,
        id = id,
        hiddenCategories = hiddenCategories,
        ignoredHealthIssues = ignoredHealthIssues,
        categoryLabels = categoryLabels,
        records = records,
    )

    private fun garage(car: Ledger) = Garage(version = 2, activeId = car.id, theme = "blue", cars = listOf(car))

    private fun record(id: String, amount: Double = 20.0) = Record(
        id = id,
        date = "2026-11-01",
        category = "停车费",
        amount = amount,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun `local removal of a hidden category survives a sync`() {
        val base = ledger(hiddenCategories = listOf("停车费"))
        // A 端：刚取消隐藏。
        val local = ledger(hiddenCategories = emptyList())
        // B 端：只新增了一笔，设置没动。
        val remote = ledger(hiddenCategories = listOf("停车费"), records = listOf(record("b-1")))

        val merged = SyncMerge.mergeGarage(garage(base), garage(local), garage(remote), now = 1_700_000_100_000L)

        val hidden = merged.garage.cars.first().hiddenCategories
        assertTrue("取消隐藏不应被同步带回来：$hidden", hidden.isNullOrEmpty())
        assertEquals(1, merged.garage.cars.first().records.size)
    }

    @Test
    fun `restoring the default category label is not undone by sync`() {
        val base = ledger(categoryLabels = mapOf("停车费" to "泊车"))
        val local = ledger(categoryLabels = null)
        val remote = ledger(categoryLabels = mapOf("停车费" to "泊车"), records = listOf(record("b-1")))

        val merged = SyncMerge.mergeGarage(garage(base), garage(local), garage(remote), now = 1_700_000_100_000L)

        val labels = merged.garage.cars.first().categoryLabels
        assertTrue("还原默认名不应被同步带回来：$labels", labels.isNullOrEmpty())
    }

    @Test
    fun `un-ignoring a health issue is not re-added by sync`() {
        val base = ledger(ignoredHealthIssues = listOf("mileage-drop:r1"))
        val local = ledger(ignoredHealthIssues = emptyList())
        val remote = ledger(ignoredHealthIssues = listOf("mileage-drop:r1"), records = listOf(record("b-1")))

        val merged = SyncMerge.mergeGarage(garage(base), garage(local), garage(remote), now = 1_700_000_100_000L)

        val ignored = merged.garage.cars.first().ignoredHealthIssues
        assertTrue("取消忽略不应被同步带回来：$ignored", ignored.isNullOrEmpty())
    }

    @Test
    fun `a new setting made on the other device still arrives`() {
        val base = ledger(hiddenCategories = null)
        // 本机没动设置。
        val local = ledger(hiddenCategories = null, records = listOf(record("a-1")))
        // 另一台设备隐藏了「罚单」。
        val remote = ledger(hiddenCategories = listOf("罚单"))

        val merged = SyncMerge.mergeGarage(garage(base), garage(local), garage(remote), now = 1_700_000_100_000L)

        assertEquals(listOf("罚单"), merged.garage.cars.first().hiddenCategories)
    }

    @Test
    fun `without a baseline removals fall back to the union`() {
        // 无基线时不知道谁删过，保持老行为（并集），不能凭空删掉对面的设置。
        val local = ledger(hiddenCategories = listOf("停车费"))
        val remote = ledger(hiddenCategories = listOf("罚单"))

        val merged = SyncMerge.mergeGarage(null, garage(local), garage(remote), now = 1_700_000_100_000L)

        val hidden = merged.garage.cars.first().hiddenCategories.orEmpty()
        assertFalse(hidden.isEmpty())
        assertEquals(setOf("停车费", "罚单"), hidden.toSet())
    }
}
