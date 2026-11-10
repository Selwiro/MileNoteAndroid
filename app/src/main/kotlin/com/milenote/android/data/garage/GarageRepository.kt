package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.calc.syncServiceHistory
import com.milenote.android.domain.demo.DemoData
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.i18n.tr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 恢复备份后的数量摘要，用于结果提示。 */
data class RestoreSummary(
    val cars: Int,
    val records: Int,
    val reminders: Int,
)

/**
 * 账本仓库：单文件 JSON 快照（与 Web 备份同构）+ 内存演示模式。
 *
 * 写入语义与 Web 版对齐：每次保存先对当前车辆执行 [syncServiceHistory]，
 * 再整体落盘；演示模式只改内存（不落盘），退出后恢复真实账本。
 *
 * **数据安全**：
 * - 读取失败且无法隔离原始文件时进入 [persistenceBlocked] 状态：**停止一切落盘**，
 *   避免用空账本覆盖唯一副本（修复前会静默覆盖并提示「已另存」）；
 * - [replace] / [restoreBackup] 等整体覆盖前先写一份本地快照（保留最近 3 份），
 *   可用 [rollbackToLatestSnapshot] 回滚；
 * - [reload] 供「读取失败」后用户主动重试，清掉内存缓存重新读盘。
 */
class GarageRepository(
    private val store: GarageStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    private val _garage = MutableStateFlow<Garage?>(null)
    val garage: StateFlow<Garage?> = _garage.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** 读取失败且原始文件无法隔离：为保护唯一副本，本次会话停止落盘（UI 显示为「已暂停保存」）。 */
    private val _persistenceBlocked = MutableStateFlow(false)
    val persistenceBlocked: StateFlow<Boolean> = _persistenceBlocked.asStateFlow()

    /** 本地账本写入事件（用于自动同步去抖；自动下载覆盖不触发，避免循环）。 */
    private val _localWrites = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localWrites = _localWrites.asSharedFlow()

    private var persisted: Garage? = null

    /** 本次会话是否禁止落盘（见 [persistenceBlocked]）。 */
    private var writeBlocked: Boolean = false

    /** 首次加载：读取本地快照；损坏时保留现场并从空账本开始。 */
    suspend fun load(): Garage = mutex.withLock { loadLocked() }

    /**
     * 用户主动重试读取（读取失败后调用）：清掉内存缓存与错误状态，重新读盘。
     *
     * 成功后自动退出「暂停保存」；仍失败则保持保护状态。
     */
    suspend fun reload(): Garage = mutex.withLock {
        _garage.value = null
        _loadError.value = null
        writeBlocked = false
        _persistenceBlocked.value = false
        loadLocked()
    }

    private suspend fun loadLocked(): Garage {
        _garage.value?.let { return it }
        var blocked = false
        val loaded = withContext(io) {
            val raw = store.read()
            if (raw.isNullOrBlank()) {
                GarageOps.normalizeGarage(null)
            } else {
                try {
                    BackupCodec.decode(raw)
                } catch (e: Exception) {
                    // 先尝试保住原始文件；保不住就绝不能写回，否则唯一副本被空账本抹掉。
                    val saved = store.quarantine() != null
                    blocked = !saved
                    _loadError.value = if (saved) {
                        tr(
                            "本地账本读取失败：%s（原始数据已另存，可在 设置 → 备份与还原 导出）",
                            "Failed to read the local ledger: %s (a copy of the original was saved — export it from Settings → Backup & restore)",
                            e.message,
                        )
                    } else {
                        tr(
                            "本地账本读取失败：%s（无法另存原始数据，已停止写入以保护原文件）",
                            "Failed to read the local ledger: %s (could not save a copy — writing is disabled to protect the original file)",
                            e.message,
                        )
                    }
                    GarageOps.normalizeGarage(null)
                }
            }
        }
        writeBlocked = blocked
        _persistenceBlocked.value = blocked
        persisted = loaded
        _garage.value = loaded
        // 保护状态下不写盘：宁可本次会话的修改不保存，也不能覆盖无法备份的坏文件。
        if (!blocked) withContext(io) { store.write(BackupCodec.encode(loaded)) }
        return loaded
    }

    /** 任务式写入；`syncHistory` 复刻 Web「保存即重算保养」的副作用。 */
    suspend fun update(syncHistory: Boolean = true, transform: (Garage) -> Garage): Garage? = mutex.withLock {
        val current = _garage.value ?: return@withLock null
        var next = transform(current)
        if (syncHistory) next = syncActiveLedger(next)
        next = GarageOps.normalizeGarage(next)
        if (!_demoMode.value && !writeBlocked) {
            withContext(io) { store.write(BackupCodec.encode(next)) }
            persisted = next
            _localWrites.tryEmit(Unit)
        }
        _garage.value = next
        next
    }

    /** 整体替换（导入/恢复/远端覆盖后用）：覆盖前先留本地快照，便于回滚。 */
    suspend fun replace(garage: Garage): Garage = mutex.withLock { replaceLocked(garage, snapshot = true) }

    private suspend fun replaceLocked(garage: Garage, snapshot: Boolean): Garage {
        val normalized = GarageOps.normalizeGarage(garage)
        if (!_demoMode.value && !writeBlocked) {
            withContext(io) {
                if (snapshot) store.snapshot()
                store.write(BackupCodec.encode(normalized))
            }
            persisted = normalized
        }
        _garage.value = normalized
        return normalized
    }

    suspend fun setTheme(themeId: String) {
        update(syncHistory = false) { it.copy(theme = themeId) }
    }

    fun enterDemo() {
        if (_demoMode.value) return
        persisted = _garage.value
        _garage.value = DemoData.createDemoGarage()
        _demoMode.value = true
    }

    fun exitDemo() {
        if (!_demoMode.value) return
        _demoMode.value = false
        _garage.value = persisted ?: GarageOps.normalizeGarage(null)
    }

    suspend fun exportBackup(): String = withContext(io) {
        BackupCodec.encode(_garage.value ?: GarageOps.normalizeGarage(null))
    }

    /**
     * 读取解析失败时保留下来的原始账本（供用户导出检查）。
     *
     * 优先返回隔离副本；若当时连隔离都失败（[persistenceBlocked]），退回当前账本文件本身，
     * 保证这条「最后抢救通道」不会因为保护状态而消失。
     */
    suspend fun quarantinedRaw(): File? = withContext(io) {
        store.latestQuarantined() ?: if (writeBlocked) store.currentFile().takeIf { it.exists() } else null
    }

    /** 最近一次整体覆盖前的本地快照（设置页据此显示「回滚」入口）。 */
    suspend fun latestReplaceSnapshot(): File? = withContext(io) { store.latestSnapshot() }

    /**
     * 回滚到最近一次覆盖前的快照。
     *
     * 回滚本身也写快照（便于再次反悔），但不会递归回滚到回滚前状态。
     *
     * @return 是否成功（没有快照 / 快照不可解析 / 演示模式返回 false）。
     */
    suspend fun rollbackToLatestSnapshot(): Boolean = mutex.withLock {
        val snapshot = withContext(io) { store.latestSnapshot() } ?: return@withLock false
        val text = withContext(io) { runCatching { snapshot.readText() }.getOrNull() } ?: return@withLock false
        val restored = runCatching { BackupCodec.decode(text) }.getOrNull() ?: return@withLock false
        writeBlocked = false
        _persistenceBlocked.value = false
        _loadError.value = null
        replaceLocked(restored, snapshot = true)
        true
    }

    /** 恢复备份（v1 合到当前车 / v2 按车辆 id 合并），返回合并后的数量摘要。 */
    suspend fun restoreBackup(text: String): RestoreSummary = mutex.withLock {
        // 读基线、合并、替换必须在同一把锁内，避免并发写丢更新。
        val current = _garage.value ?: loadLocked()
        val payload = BackupCodec.decodePayload(text)
        val merged = withContext(io) { BackupCodec.restore(current, text) }
        replaceLocked(merged, snapshot = true)
        summarizeRestore(payload, merged)
    }

    /** 清空数据（单辆或全部）；自动补一辆空车，避免出现空车库。 */
    suspend fun deleteData(scope: String, carId: String? = null) {
        update(syncHistory = false) { GarageOps.deleteGarageData(it, scope, carId) }
    }

    private fun syncActiveLedger(garage: Garage): Garage {
        val active = garage.activeCar ?: return garage
        val synced: Ledger = syncServiceHistory(active)
        return garage.copy(cars = garage.cars.map { if (it.id == active.id) synced.copy(id = active.id) else it })
    }
}
