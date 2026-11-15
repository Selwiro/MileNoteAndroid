package com.milenote.android.data.garage

import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.GarageOps
import com.milenote.android.domain.backup.SyncStamps
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
 * 账本写入失败（磁盘满、只读、文件被占用等）。
 *
 * 与「解析失败」不同：此时内存里的账本通常是好的，只是写不下去，所以不能静默吞掉，
 * 也不能让异常逃出协程（会直接崩溃）；调用方应提示用户「本次修改只保留在内存」。
 */
class GarageWriteException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
    /** 当前时刻（epoch 毫秒），同步打点用；单测可注入固定时钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
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

    /**
     * 最近一次写盘失败的原因（成功写入后自动清空）。
     *
     * 写盘失败不再抛到 `viewModelScope`（那会直接崩溃），而是记在这里由 UI 显示；
     * 内存里的账本仍会更新，保证用户刚输入的内容不立刻丢，但必须明确告诉用户「未写入本机」。
     */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

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
        _writeError.value = null
        writeBlocked = false
        _persistenceBlocked.value = false
        loadLocked()
    }

    private suspend fun loadLocked(): Garage {
        _garage.value?.let { return it }
        var blocked = false
        // 损坏文件被成功隔离后，写一份空账本顶替它；正常读取不再重写（旧实现每次启动都全量重写一遍）。
        var writeFresh = false
        val loaded = withContext(io) {
            val exists = runCatching { store.exists() }.getOrDefault(false)
            val raw: String? = try {
                store.read()
            } catch (e: Exception) {
                // 读不出来通常不是「文件坏了」，而是权限 / IO 问题；此时既不隔离也不写，
                // 保留最后抢救通道（quarantinedRaw 会退回当前文件），等用户重试。
                blocked = true
                _loadError.value = tr(
                    "本地数据无法读取：%s（已停止写入以保护原文件，可重新读取或导出原始数据）",
                    "The local data could not be read: %s (writing is disabled to protect the original file — re-read it or export the raw data)",
                    e.message ?: tr("文件不可读", "the file is not readable"),
                )
                return@withContext GarageOps.normalizeGarage(null)
            }
            val blank = raw.isNullOrBlank()
            // 文件不存在 = 真正的首次启动；文件存在但空白 = 损坏（写回退被中断 / 掉电），不能静默当空账本。
            if (blank && !exists) return@withContext GarageOps.normalizeGarage(null)

            val failure = if (blank) {
                tr("文件为空", "the file is empty")
            } else {
                try {
                    return@withContext BackupCodec.decode(raw)
                } catch (e: Exception) {
                    e.message ?: tr("数据格式无效", "invalid data format")
                }
            }
            // 先尝试保住原始文件；保不住就绝不能写回，否则唯一副本被空账本抹掉。
            val saved = runCatching { store.quarantine() }.getOrNull() != null
            blocked = !saved
            writeFresh = saved
            _loadError.value = if (saved) {
                tr(
                    "本地数据读取失败：%s（原始数据已另存，可在 设置 → 备份与还原 导出）",
                    "Failed to read the local data: %s (a copy of the original was saved — export it from Settings → Backup & restore)",
                    failure,
                )
            } else {
                tr(
                    "本地数据读取失败：%s（无法另存原始数据，已停止写入以保护原文件）",
                    "Failed to read the local data: %s (could not save a copy — writing is disabled to protect the original file)",
                    failure,
                )
            }
            GarageOps.normalizeGarage(null)
        }
        _garage.value = loaded
        persisted = loaded
        if (writeFresh && !blocked) {
            // 隔离成功才允许写空账本；写不进去（磁盘满）就转入保护状态，至少不崩。
            if (!writeJson(loaded)) {
                blocked = true
            }
        }
        writeBlocked = blocked
        _persistenceBlocked.value = blocked
        return loaded
    }

    /**
     * 任务式写入；`syncHistory` 复刻 Web「保存即重算保养」的副作用。
     *
     * 写入前会做多设备同步打点（[SyncStamps.stampChanges]）：真改过的记录 / 提醒写 `updatedAt`，
     * 被删的记录留墓碑。整体覆盖走 [replace]（不打点，远端时间戳原样采纳）。
     *
     * 写盘失败**不抛异常**：把原因记到 [writeError] 并保留内存中的修改，由 UI 明确提示
     * 「未写入本机」；下一次成功写入会自动清掉错误并落盘（包含这次累积的修改）。
     */
    suspend fun update(syncHistory: Boolean = true, transform: (Garage) -> Garage): Garage? = mutex.withLock {
        val current = _garage.value ?: return@withLock null
        var next = transform(current)
        if (syncHistory) next = syncActiveLedger(next)
        next = GarageOps.normalizeGarage(next)
        next = SyncStamps.stampChanges(current, next, clock())
        if (!_demoMode.value && !writeBlocked && writeJson(next)) {
            persisted = next
            _localWrites.tryEmit(Unit)
        }
        _garage.value = next
        next
    }

    /** 整体替换（导入/恢复/远端覆盖后用）：覆盖前先留本地快照，便于回滚。 */
    suspend fun replace(garage: Garage): Garage = mutex.withLock { replaceLocked(garage, snapshot = true) }

    /**
     * 条件整体替换：只有内存账本仍是 [expected]（= 同步开始时的快照）时才覆盖。
     *
     * 用于 WebDAV 同步：同步要花一次网络往返，期间用户可能又保存了一笔记录；如果直接 [replace]，
     * 会用「基于旧快照算出的合并结果」把这条新修改抹掉。返回 null 表示本地已被改过，
     * 调用方应重读账本重新判定，而不是覆盖。
     */
    suspend fun replaceIfUnchanged(expected: Garage, garage: Garage): Garage? = mutex.withLock {
        if (_garage.value != expected) return@withLock null
        replaceLocked(garage, snapshot = true)
    }

    private suspend fun replaceLocked(garage: Garage, snapshot: Boolean): Garage {
        val normalized = GarageOps.normalizeGarage(garage)
        if (!_demoMode.value && !writeBlocked) {
            // 整体替换要么成功、要么保持原状：写失败时抛给调用方提示，不能「内存换了、磁盘没换」。
            if (!writeJson(normalized, snapshot = snapshot)) {
                throw GarageWriteException(
                    _writeError.value ?: tr("账本写入失败", "Could not write the data"),
                )
            }
            persisted = normalized
        }
        _garage.value = normalized
        return normalized
    }

    /**
     * 编码并写盘；失败只记录 [writeError]，不抛异常。
     *
     * @param snapshot 写入前是否先留一份本机快照（整体覆盖 / 删除用）。
     * @return 是否成功写入。
     */
    private suspend fun writeJson(garage: Garage, snapshot: Boolean = false): Boolean = withContext(io) {
        try {
            val json = BackupCodec.encode(garage)
            if (snapshot) store.snapshot()
            store.write(json)
            _writeError.value = null
            true
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            _writeError.value = tr(
                "无法写入本机：%s（本次修改只保留在内存，重启后会丢失）",
                "Could not write to this device: %s (this change is kept in memory only and will be lost after a restart)",
                t.message ?: tr("存储空间不足或文件不可写", "storage is full or the file is not writable"),
            )
            false
        }
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

    /**
     * 导出完整备份。
     *
     * @throws IllegalStateException 演示模式下禁止导出：此时内存里是演示账本，
     *   导出会得到一份「看起来像完整备份」的演示数据，并可能诱导用户覆盖真实备份。
     */
    suspend fun exportBackup(): String = withContext(io) {
        if (_demoMode.value) {
            throw IllegalStateException(
                tr("演示模式下不能导出完整备份，请先退出演示", "Exit the demo before exporting a full backup"),
            )
        }
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

    /** 最近一次破坏性操作（整体覆盖 / 删除）前的本地快照（设置页据此显示「回滚」入口）。 */
    suspend fun latestReplaceSnapshot(): File? = withContext(io) { store.latestSnapshot() }

    /**
     * 回滚到最近一次破坏性操作（覆盖 / 删除）前的快照。
     *
     * 回滚本身也写快照（便于再次反悔），但不会递归回滚到回滚前状态。
     *
     * @return 是否成功（没有快照 / 快照不可解析 / 演示模式返回 false）。
     */
    suspend fun rollbackToLatestSnapshot(): Boolean = mutex.withLock {
        if (_demoMode.value) return@withLock false
        val snapshot = withContext(io) { store.latestSnapshot() } ?: return@withLock false
        val text = withContext(io) { runCatching { snapshot.readText() }.getOrNull() } ?: return@withLock false
        val restored = runCatching { BackupCodec.decode(text) }.getOrNull() ?: return@withLock false
        // 回滚本身就是在修数据：先允许写入；失败则恢复原来的保护状态，不能借回滚把保护关掉。
        val wasBlocked = writeBlocked
        writeBlocked = false
        val ok = runCatching { replaceLocked(restored, snapshot = true) }.isSuccess
        if (ok) {
            _persistenceBlocked.value = false
            _loadError.value = null
        } else {
            writeBlocked = wasBlocked
            _persistenceBlocked.value = wasBlocked
        }
        ok
    }

    /**
     * 恢复备份（v1 合到当前车 / v2 按车辆 id 合并），返回合并后的数量摘要。
     *
     * 演示模式 / 本机写入暂停时直接拒绝：这两种状态下 `replaceLocked` 不会落盘，
     * 提示「已恢复」但退出演示或重启就消失，属于假成功。
     */
    suspend fun restoreBackup(text: String): RestoreSummary = mutex.withLock {
        if (_demoMode.value) {
            throw IllegalStateException(
                tr("演示模式下不能还原备份，请先退出演示", "Exit the demo before restoring a backup"),
            )
        }
        if (writeBlocked) {
            throw IllegalStateException(
                tr("本机写入已暂停，请先重新读取数据再还原", "Saving is paused — re-read the local data before restoring"),
            )
        }
        // 读基线、合并、替换必须在同一把锁内，避免并发写丢更新。
        val current = _garage.value ?: loadLocked()
        val payload = BackupCodec.decodePayload(text)
        val merged = withContext(io) { BackupCodec.restore(current, text) }
        replaceLocked(merged, snapshot = true)
        summarizeRestore(payload, merged)
    }

    /**
     * 清空数据（单辆或全部）；自动补一辆空车，避免出现空车库。
     *
     * 删除是破坏性操作：写入前先留一份本机快照（保留最近 3 份），写失败时抛
     * [GarageWriteException] 并保持内存原状，避免「界面删了、磁盘还在 / 反之」。
     */
    suspend fun deleteData(scope: String, carId: String? = null): Garage = mutex.withLock {
        if (_demoMode.value) {
            throw IllegalStateException(
                tr("演示模式下不能删除数据，请先退出演示", "Exit the demo before deleting data"),
            )
        }
        val current = _garage.value ?: loadLocked()
        var next = GarageOps.deleteGarageData(current, scope, carId)
        next = GarageOps.normalizeGarage(next)
        next = SyncStamps.stampChanges(current, next, clock())
        if (!writeBlocked) {
            if (!writeJson(next, snapshot = true)) {
                throw GarageWriteException(
                    _writeError.value ?: tr("删除失败，本机数据未改变", "Delete failed — the local data is unchanged"),
                )
            }
            persisted = next
            _localWrites.tryEmit(Unit)
        }
        _garage.value = next
        next
    }

    private fun syncActiveLedger(garage: Garage): Garage {
        val active = garage.activeCar ?: return garage
        val synced: Ledger = syncServiceHistory(active)
        return garage.copy(cars = garage.cars.map { if (it.id == active.id) synced.copy(id = active.id) else it })
    }
}
