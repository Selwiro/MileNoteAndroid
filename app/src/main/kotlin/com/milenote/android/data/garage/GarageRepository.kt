package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.BackupException
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
 */
class GarageRepository(
    private val store: GarageFileStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    private val _garage = MutableStateFlow<Garage?>(null)
    val garage: StateFlow<Garage?> = _garage.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** 本地账本写入事件（用于自动同步去抖；自动下载覆盖不触发，避免循环）。 */
    private val _localWrites = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localWrites = _localWrites.asSharedFlow()

    private var persisted: Garage? = null

    /** 首次加载：读取本地快照；损坏时保留现场并从空账本开始。 */
    suspend fun load(): Garage = mutex.withLock {
        _garage.value?.let { return@withLock it }
        val loaded = withContext(io) {
            val raw = store.read()
            if (raw.isNullOrBlank()) {
                GarageOps.normalizeGarage(null)
            } else {
                try {
                    BackupCodec.decode(raw)
                } catch (e: BackupException) {
                    _loadError.value = tr(
                        "本地账本读取失败：%s（原始数据已另存）",
                        "Failed to read the local ledger: %s (original data has been saved separately)",
                        e.message,
                    )
                    store.quarantine()
                    GarageOps.normalizeGarage(null)
                } catch (e: Exception) {
                    _loadError.value = tr("本地账本读取失败：%s", "Failed to read the local ledger: %s", e.message)
                    store.quarantine()
                    GarageOps.normalizeGarage(null)
                }
            }
        }
        persisted = loaded
        _garage.value = loaded
        withContext(io) { store.write(BackupCodec.encode(loaded)) }
        loaded
    }

    /** 任务式写入；`syncHistory` 复刻 Web「保存即重算保养」的副作用。 */
    suspend fun update(syncHistory: Boolean = true, transform: (Garage) -> Garage): Garage? = mutex.withLock {
        val current = _garage.value ?: return@withLock null
        var next = transform(current)
        if (syncHistory) next = syncActiveLedger(next)
        next = GarageOps.normalizeGarage(next)
        if (!_demoMode.value) {
            withContext(io) { store.write(BackupCodec.encode(next)) }
            persisted = next
        }
        _garage.value = next
        if (!_demoMode.value) _localWrites.tryEmit(Unit)
        next
    }

    /** 整体替换（导入/恢复后用）。 */
    suspend fun replace(garage: Garage): Garage = mutex.withLock {
        val normalized = GarageOps.normalizeGarage(garage)
        if (!_demoMode.value) {
            withContext(io) { store.write(BackupCodec.encode(normalized)) }
            persisted = normalized
        }
        _garage.value = normalized
        normalized
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

    /** 最近一次读取失败被隔离的原始账本文件（供用户导出检查）；没有则返回 null。 */
    suspend fun quarantinedRaw(): File? = withContext(io) { store.latestQuarantined() }

    /** 恢复备份（v1 合到当前车 / v2 按车辆 id 合并），返回合并后的数量摘要。 */
    suspend fun restoreBackup(text: String): RestoreSummary {
        val current = _garage.value ?: load()
        val payload = BackupCodec.decodePayload(text)
        val merged = withContext(io) { BackupCodec.restore(current, text) }
        replace(merged)
        return summarizeRestore(payload, merged)
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
