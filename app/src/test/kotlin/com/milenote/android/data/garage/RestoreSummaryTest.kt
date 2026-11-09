package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.BackupException
import com.milenote.android.domain.backup.BackupPayload
import com.milenote.android.domain.model.Garage
import com.milenote.android.ui.screens.importer.inspectBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份还原路径：纯函数 [summarizeRestore]（v1 / v2 计数口径）与导入向导的只读预检
 * `inspectBackup`（非法 JSON 必须抛 [BackupException] 且消息非空）。
 *
 * 样例全部为合成数据，不引用真实账本。
 */
class RestoreSummaryTest {

    /** v2 多车备份：c1 两条记录 + 一条提醒，c2 一条记录 + 两条提醒。 */
    private val v2TwoCars = """
        {"version":2,"activeId":"c1","theme":"blue","cars":[
          {"version":1,"id":"c1","vehicle":{"name":"A"},"records":[
             {"id":"r1","date":"2026-10-01","category":"停车费","amount":100,"kind":"expense"},
             {"id":"r2","date":"2026-10-02","category":"停车费","amount":200,"kind":"expense"}],
           "reminders":[{"id":"t1","title":"保养","dueKm":5000}]},
          {"version":1,"id":"c2","vehicle":{"name":"B"},"records":[
             {"id":"r3","date":"2026-10-03","category":"油费","amount":300,"kind":"expense"}],
           "reminders":[{"id":"t2","title":"保养","dueKm":6000},{"id":"t3","title":"保养","dueKm":7000}]}
        ]}
    """.trimIndent()

    /** v1 旧版单车备份：两条记录 + 一条提醒（顶层是账本，不是车库）。 */
    private val v1Legacy = """
        {"version":1,"vehicle":{"name":"旧车"},
         "records":[{"id":"r1","date":"2026-10-01","category":"维修保养","amount":380,"kind":"expense"},
                    {"id":"r2","date":"2026-10-02","category":"停车费","amount":20,"kind":"expense"}],
         "reminders":[{"id":"t1","title":"保养","dueKm":5000}]}
    """.trimIndent()

    // -----------------------------------------------------------------------------------------
    // summarizeRestore：v2
    // -----------------------------------------------------------------------------------------

    @Test
    fun `summarize restore counts v2 payload vehicles records and reminders`() {
        val payload = BackupCodec.decodePayload(v2TwoCars)
        assertTrue(payload is BackupPayload.MulticarGarage)
        val summary = summarizeRestore(payload, BackupCodec.decode(v2TwoCars))
        assertEquals(2, summary.cars)
        assertEquals(3, summary.records)
        assertEquals(3, summary.reminders)
    }

    @Test
    fun `summarize restore for v2 ignores extra cars in the merged garage`() {
        val payload = BackupCodec.decodePayload(v2TwoCars)
        val merged = Garage(
            version = 2,
            activeId = "extra",
            cars = BackupCodec.decode(v2TwoCars).cars + BackupCodec.decode(v1Legacy).cars,
        )
        assertEquals(3, merged.cars.size)
        val summary = summarizeRestore(payload, merged)
        assertEquals(2, summary.cars)
        assertEquals(3, summary.records)
        assertEquals(3, summary.reminders)
    }

    // -----------------------------------------------------------------------------------------
    // summarizeRestore：v1
    // -----------------------------------------------------------------------------------------

    @Test
    fun `summarize restore counts legacy ledger and merged cars for v1`() {
        val payload = BackupCodec.decodePayload(v1Legacy)
        assertTrue(payload is BackupPayload.LegacyLedger)
        val merged = Garage(version = 2, activeId = "c1", cars = BackupCodec.decode(v2TwoCars).cars)
        val summary = summarizeRestore(payload, merged)
        // v1：记录/提醒按 payload.ledger 计数，车辆数取合并后的车库。
        assertEquals(2, summary.cars)
        assertEquals(2, summary.records)
        assertEquals(1, summary.reminders)
    }

    @Test
    fun `summarize restore for v1 uses ledger counts even when merged grows`() {
        val payload = BackupCodec.decodePayload(v1Legacy)
        val singleCar = Garage(
            version = 2,
            activeId = "only",
            cars = listOf(BackupCodec.decode(v2TwoCars).cars.first()),
        )
        val summary = summarizeRestore(payload, singleCar)
        assertEquals(1, summary.cars)
        assertEquals(2, summary.records)
        assertEquals(1, summary.reminders)
    }

    // -----------------------------------------------------------------------------------------
    // inspectBackup：只读预检
    // -----------------------------------------------------------------------------------------

    @Test
    fun `inspect backup previews v2 two cars`() {
        val preview = inspectBackup(v2TwoCars)
        assertEquals(2, preview.version)
        assertEquals(2, preview.cars)
        assertEquals(3, preview.records)
        assertEquals(3, preview.reminders)
    }

    @Test
    fun `inspect backup previews legacy v1 as a single car`() {
        val preview = inspectBackup(v1Legacy)
        assertEquals(1, preview.version)
        assertEquals(1, preview.cars)
        assertEquals(2, preview.records)
        assertEquals(1, preview.reminders)
    }

    @Test
    fun `inspect backup rejects invalid json with a non blank message`() {
        val error = assertThrows(BackupException::class.java) { inspectBackup("这不是 JSON") }
        assertTrue(error.message?.isNotBlank() == true)
    }

    @Test
    fun `inspect backup rejects non ledger json with a non blank message`() {
        val error = assertThrows(BackupException::class.java) { inspectBackup("""{"version":3,"cars":[]}""") }
        assertTrue(error.message?.isNotBlank() == true)
    }
}
