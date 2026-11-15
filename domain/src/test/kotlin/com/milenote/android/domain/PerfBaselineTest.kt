package com.milenote.android.domain

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.blankLedger
import org.junit.Test
import java.security.MessageDigest

/**
 * 性能基线（UX12）：只打印数字、不做断言。
 *
 * 每次保存都要把整本账（含 base64 图片）序列化写盘、同步还要算 SHA-256 + 可能的加密；
 * 这里用 1k / 5k 条合成账本量「序列化 / 反序列化 / 哈希」的耗时，作为 1.0 的性能基线留档
 * （数字记进 `management/changelog/state-archive.md`；图片外置 / 增量同步归 2.0，不做）。
 */
class PerfBaselineTest {

    private fun ledger(records: Int): Ledger {
        val fuel = blankLedger().copy(id = "car-$records")
        return fuel.copy(
            records = List(records) { index ->
                Record(
                    id = "r$index",
                    date = "2026-${(index % 12 + 1).toString().padStart(2, '0')}-${(index % 28 + 1).toString().padStart(2, '0')}",
                    category = if (index % 5 == 0) "保养" else "油费",
                    amount = 200.0 + (index % 300),
                    odometer = 40_000.0 + index,
                    liters = if (index % 5 == 0) null else 40.0 + (index % 20),
                    full = true,
                    note = if (index % 3 == 0) "合成备注 · $index" else "",
                    location = "合成加油站 $index",
                    projects = if (index % 5 == 0) "机油机滤" else null,
                )
            },
        )
    }

    private fun timeMs(block: () -> Unit): Long {
        // 预热一次，避免 JIT 把第一次结果算进去。
        block()
        val start = System.nanoTime()
        repeat(3) { block() }
        return (System.nanoTime() - start) / 3_000_000
    }

    @Test
    fun `print serialization baseline for 1k and 5k records`() {
        for (count in listOf(1_000, 5_000)) {
            val ledger = ledger(count)
            val garage = Garage(cars = listOf(ledger), activeId = ledger.id)
            val json = BackupCodec.encode(garage)
            val encodeMs = timeMs { BackupCodec.encode(garage) }
            val decodeMs = timeMs { BackupCodec.decode(json) }
            val shaMs = timeMs { MessageDigest.getInstance("SHA-256").digest(json.toByteArray()) }
            println(
                "[perf] $count 条：JSON ${json.length / 1024} KB · 序列化 ${encodeMs}ms · " +
                    "反序列化 ${decodeMs}ms · SHA-256 ${shaMs}ms",
            )
        }
    }
}
