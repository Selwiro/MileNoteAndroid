package com.milenote.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.milenote.android.AppContainer
import com.milenote.android.data.garage.GarageRepository
import com.milenote.android.data.settings.AppSettings
import com.milenote.android.data.settings.DarkMode
import com.milenote.android.data.share.SharedImport
import com.milenote.android.data.settings.SettingsRepository
import com.milenote.android.data.webdav.Plan
import com.milenote.android.data.webdav.AutoSyncOutcome
import com.milenote.android.data.webdav.WebDavAutoSyncEngine
import com.milenote.android.data.webdav.WebDavFetchResult
import com.milenote.android.data.webdav.WebDavRemoteMeta
import com.milenote.android.data.webdav.WebDavRepository
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.completeService
import com.milenote.android.domain.calc.restoreServiceRemindersAfterRecordDelete
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.defaultServiceReminders
import com.milenote.android.domain.calc.syncServiceHistory
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.calc.completeImportedServices
import com.milenote.android.domain.calc.mergeFuelBatch
import com.milenote.android.domain.calc.mergeImported
import com.milenote.android.domain.import.ImportItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 记一笔/编辑记录的目标。 */
data class EditorTarget(
    val record: Record? = null,
    val category: String = "油费",
    val serviceReminderId: String? = null,
)

/** 删除保养记录时询问是否恢复其完成的提醒。 */
data class PendingRecordDelete(
    val record: Record,
    /** 该记录完成的提醒标题。 */
    val reminderTitles: List<String>,
)

/** 通用确认弹层。 */
data class PendingConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String = "确定",
    val destructive: Boolean = false,
    val requireText: String? = null,
    val action: () -> Unit,
)

/** WebDAV 同步模式。 */
enum class WebDavDownloadMode { MERGE, REPLACE }

/** WebDAV 冲突处理方式。 */
enum class WebDavConflictChoice { KEEP_LOCAL, KEEP_REMOTE, MERGE }

/** WebDAV 冲突：本地与远端都有改动。 */
data class WebDavConflict(
    val localChanged: Boolean,
    val remoteChanged: Boolean,
)

/** WebDAV 同步页状态（VM 维护）。 */
data class WebDavUiState(
    val configured: Boolean = false,
    val fileUrl: String = "",
    val username: String = "",
    val allowHttp: Boolean = false,
    val hasPassword: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastResult: String? = null,
    val remoteExists: Boolean? = null,
    val remoteSize: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val conflict: WebDavConflict? = null,
    /** 是否开启自动同步（仅应用打开期间：打开时一次 + 本地修改后防抖）。 */
    val autoSyncEnabled: Boolean = false,
    /** 最近一次自动同步时间。 */
    val lastAutoSyncAt: Long? = null,
    /** 最近一次自动同步结果。 */
    val lastAutoSyncResult: String? = null,
)

/** 全局 UI 状态。 */
data class UiState(
    val garage: Garage? = null,
    val demo: Boolean = false,
    val loadError: String? = null,
    val period: Period = Period.ALL,
    val editor: EditorTarget? = null,
    val vehicleSwitcher: Boolean = false,
    val confirm: PendingConfirm? = null,
    val recordDelete: PendingRecordDelete? = null,
    val message: String? = null,
    val showVehicleEditor: Boolean = false,
    val vehicleEditorId: String? = null,
    val showServicePlanEditor: Boolean = false,
    val showImporter: Boolean = false,
    val sharedImport: SharedImport? = null,
    val webDav: WebDavUiState = WebDavUiState(),
    /** 总览自定义时间范围；非空时覆盖 [period]。 */
    val customRange: DateRange? = null,
) {
    /** 总览实际使用的统计范围（自定义优先）。 */
    fun effectiveRange(today: java.time.LocalDate = Dates.today()): DateRange =
        customRange ?: Dates.range(period, today)
    val activeCar: Ledger? get() = garage?.activeCar
    val vehicle: Vehicle? get() = activeCar?.vehicle
}

/** 主 ViewModel：所有写操作经仓库持久化，并复刻 Web 的保存副作用。 */
class MileNoteViewModel(
    private val repository: GarageRepository,
    private val settingsRepository: SettingsRepository,
    private val webDavRepository: WebDavRepository,
    private val webDavAutoSyncEngine: WebDavAutoSyncEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 外观等本机设置（皮肤在账本里）。 */
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private var autoSyncDebounceJob: Job? = null

    init {
        viewModelScope.launch {
            repository.load()
        }
        viewModelScope.launch {
            combine(repository.garage, repository.demoMode, repository.loadError) { garage, demo, loadError ->
                Triple(garage, demo, loadError)
            }.collect { (garage, demo, loadError) ->
                _state.value = _state.value.copy(
                    garage = garage,
                    demo = demo,
                    loadError = loadError,
                )
            }
        }
        viewModelScope.launch {
            combine(webDavRepository.config, webDavRepository.syncState) { config, sync -> config to sync }
                .collect { (config, sync) ->
                    val current = _state.value.webDav
                    _state.value = _state.value.copy(
                        webDav = current.copy(
                            configured = config != null,
                            fileUrl = config?.fileUrl ?: current.fileUrl,
                            username = config?.username ?: current.username,
                            allowHttp = config?.allowHttp ?: current.allowHttp,
                            hasPassword = config?.hasPassword ?: false,
                            lastSyncAt = sync.lastSyncAt,
                            lastAutoSyncAt = sync.lastAutoSyncAt,
                            lastAutoSyncResult = sync.lastAutoSyncResult,
                        ),
                    )
                }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.value = _state.value.copy(
                    webDav = _state.value.webDav.copy(autoSyncEnabled = settings.webDavAutoSync),
                )
            }
        }
        viewModelScope.launch {
            repository.localWrites.collect {
                if (_state.value.demo) return@collect
                val enabled = settingsRepository.settings.first().webDavAutoSync
                if (!enabled || !webDavRepository.canSync()) return@collect
                autoSyncDebounceJob?.cancel()
                autoSyncDebounceJob = viewModelScope.launch {
                    delay(2_500)
                    autoSync()
                }
            }
        }
    }

    // ---------- 基础操作 ----------

    fun setPeriod(period: Period) {
        _state.value = _state.value.copy(period = period, customRange = null)
    }

    /** 总览「自定义」时间范围（闭区间 ISO 日期）。 */
    fun setCustomRange(from: String, to: String) {
        val start = Dates.dateValue(from)
        val end = Dates.dateValue(to)
        if (start.isEmpty() || end.isEmpty() || start > end) return
        _state.value = _state.value.copy(customRange = DateRange(start, end))
    }

    fun selectVehicle(id: String) {
        viewModelScope.launch {
            repository.update(syncHistory = false) { it.copy(activeId = id) }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 系统分享进来的导入内容。 */
    fun setSharedImport(value: SharedImport?) {
        _state.value = _state.value.copy(sharedImport = value)
    }

    fun consumeSharedImport() {
        _state.value = _state.value.copy(sharedImport = null)
    }

    fun showVehicleSwitcher(show: Boolean) {
        _state.value = _state.value.copy(vehicleSwitcher = show)
    }

    // ---------- 记一笔 / 编辑记录 ----------

    fun openEditor(recordId: String? = null, category: String = "油费", serviceReminderId: String? = null) {
        val record = recordId?.let { id -> _state.value.activeCar?.records?.firstOrNull { it.id == id } }
        _state.value = _state.value.copy(
            editor = EditorTarget(record = record, category = category, serviceReminderId = serviceReminderId),
        )
    }

    fun closeEditor() {
        _state.value = _state.value.copy(editor = null)
    }

    fun saveRecord(record: Record) {
        viewModelScope.launch {
            repository.update(syncHistory = true) { garage ->
                updateActive(garage) { ledger ->
                    val exists = ledger.records.any { it.id == record.id }
                    val records = if (exists) {
                        ledger.records.map { if (it.id == record.id) record else it }
                    } else {
                        ledger.records + record
                    }
                    ledger.copy(records = records)
                }
            }
            _state.value = _state.value.copy(editor = null, message = "已保存")
        }
    }

    fun deleteRecord(record: Record) {
        val linked = _state.value.activeCar?.reminders.orEmpty()
            .filter { it.done == true && it.recordId == record.id }
        if (linked.isNotEmpty()) {
            _state.value = _state.value.copy(
                recordDelete = PendingRecordDelete(
                    record = record,
                    reminderTitles = linked.map { it.title }.distinct(),
                ),
            )
            return
        }
        confirm(
            PendingConfirm(
                title = "删除这条记录？",
                message = "${Dates.displayDots(record.date)} · ${record.category} · ¥${Format.money(record.amount)}",
                confirmLabel = "删除",
                destructive = true,
            ) {
                viewModelScope.launch {
                    repository.update(syncHistory = true) { garage ->
                        updateActive(garage) { it.copy(records = it.records.filter { r -> r.id != record.id }) }
                    }
                    _state.value = _state.value.copy(message = "已删除")
                }
            },
        )
    }

    /** 取消「删除记录后是否恢复提醒」。 */
    fun cancelRecordDelete() {
        _state.value = _state.value.copy(recordDelete = null)
    }

    /** 删除记录；[restoreReminders] 为 true 时同时恢复该记录完成的保养提醒。 */
    fun confirmRecordDelete(record: Record, restoreReminders: Boolean) {
        viewModelScope.launch {
            repository.update(syncHistory = true) { garage ->
                updateActive(garage) { ledger ->
                    val reminders = if (restoreReminders) {
                        restoreServiceRemindersAfterRecordDelete(ledger.reminders, record)
                    } else {
                        ledger.reminders.map { reminder ->
                            if (reminder.recordId == record.id) reminder.copy(recordId = null)
                            else reminder
                        }
                    }
                    ledger.copy(
                        records = ledger.records.filter { r -> r.id != record.id },
                        reminders = reminders,
                    )
                }
            }
            _state.value = _state.value.copy(
                recordDelete = null,
                editor = _state.value.editor?.takeIf { it.record?.id != record.id },
                message = if (restoreReminders) "已删除，保养提醒已恢复" else "已删除",
            )
        }
    }

    // ---------- 车辆 ----------

    fun openVehicleEditor(vehicleId: String? = null) {
        _state.value = _state.value.copy(showVehicleEditor = true, vehicleEditorId = vehicleId)
    }

    fun closeVehicleEditor() {
        _state.value = _state.value.copy(showVehicleEditor = false, vehicleEditorId = null)
    }

    fun saveVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { it.copy(vehicle = vehicle) }
            }
            _state.value = _state.value.copy(showVehicleEditor = false, message = "车辆信息已保存")
        }
    }

    fun addVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                val ledger = blankLedger().copy(id = Ids.newId(), vehicle = vehicle)
                garage.copy(cars = garage.cars + ledger, activeId = ledger.id)
            }
            _state.value = _state.value.copy(showVehicleEditor = false, vehicleSwitcher = false, message = "已添加车辆")
        }
    }

    fun deleteVehicle(id: String) {
        val name = _state.value.garage?.cars?.firstOrNull { it.id == id }?.vehicle?.name ?: "这辆车"
        confirm(
            PendingConfirm(
                title = "删除车辆",
                message = "将删除「$name」及其全部费用、提醒与保养记录。建议先导出完整备份。",
                confirmLabel = "删除",
                destructive = true,
            ) {
                viewModelScope.launch {
                    repository.deleteData("one", id)
                    _state.value = _state.value.copy(message = "已删除车辆")
                }
            },
        )
    }

    fun deleteAllData() {
        confirm(
            PendingConfirm(
                title = "删除全部数据",
                message = "将删除所有车辆、费用、提醒与保养记录，且不可恢复。请输入「删除」确认。",
                confirmLabel = "永久删除",
                destructive = true,
                requireText = "删除",
            ) {
                viewModelScope.launch {
                    repository.deleteData("all")
                    _state.value = _state.value.copy(message = "已清空数据")
                }
            },
        )
    }

    /** 删除自定义费用分类（内置 9 类不可删；已有记录保留原分类名不受影响）。 */
    fun removeCustomCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed in Categories.ALL) return
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { ledger ->
                    ledger.copy(
                        customCategories = ledger.customCategories.orEmpty().filter { it != trimmed },
                    )
                }
            }
        }
    }

    /** 新增某个分类下的可选项目（保养/维修/汽车美容/保险等）。 */
    fun addProject(category: String, name: String) {
        val cat = Categories.normalize(category)
        val trimmed = name.trim()
        if (cat.isEmpty() || trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { ledger ->
                    val map = ledger.serviceProjectsByCategory.orEmpty().toMutableMap()
                    map[cat] = (map[cat].orEmpty() + trimmed).distinct()
                    ledger.copy(serviceProjectsByCategory = map)
                }
            }
        }
    }

    /** 删除某个分类下的自定义项目（内置项目不可删；已有记录保留文字不受影响）。 */
    fun removeProject(category: String, name: String) {
        val cat = Categories.normalize(category)
        val trimmed = name.trim()
        if (cat.isEmpty() || trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { ledger ->
                    val map = ledger.serviceProjectsByCategory.orEmpty().toMutableMap()
                    val remaining = map[cat].orEmpty().filter { it != trimmed }
                    if (remaining.isEmpty()) map.remove(cat) else map[cat] = remaining
                    ledger.copy(serviceProjectsByCategory = map)
                }
            }
        }
    }

    /** 新增/持久化自定义费用分类（不产生记录）。 */
    fun addCustomCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { ledger ->
                    if (ledger.customCategories.orEmpty().contains(trimmed)) ledger
                    else ledger.copy(customCategories = ledger.customCategories.orEmpty() + trimmed)
                }
            }
        }
    }

    // ---------- 主题 ----------

    fun setTheme(themeId: String) {
        viewModelScope.launch { repository.setTheme(themeId) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { settingsRepository.setDarkMode(mode) }
    }


    // ---------- 保养 ----------

    fun openServicePlanEditor() {
        _state.value = _state.value.copy(showServicePlanEditor = true)
    }

    fun closeServicePlanEditor() {
        _state.value = _state.value.copy(showServicePlanEditor = false)
    }

    fun saveServicePlan(entries: List<PlanEntry>, baseDate: String, baseKm: Double, includeFirst: Boolean) {
        viewModelScope.launch {
            try {
                repository.update(syncHistory = true) { garage ->
                    updateActive(garage) { ledger ->
                        applyServicePlan(ledger, entries, baseDate, baseKm, includeFirst)
                    }
                }
                _state.value = _state.value.copy(showServicePlanEditor = false, message = "保养方案已保存")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: "保存失败")
            }
        }
    }

    fun useDefaultServicePlan(baseDate: String, baseKm: Double, includeFirst: Boolean) {
        viewModelScope.launch {
            try {
                repository.update(syncHistory = true) { garage ->
                    updateActive(garage) { ledger ->
                        val reminders = defaultServiceReminders(baseDate, baseKm, includeFirst)
                        val existing = ledger.reminders.filter { r ->
                            reminders.none { n -> n.presetKey == r.presetKey || n.title == r.title }
                        }
                        ledger.copy(reminders = existing + reminders, servicePlanConfigured = true)
                    }
                }
                _state.value = _state.value.copy(showServicePlanEditor = false, message = "已启用默认保养方案")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: "启用失败")
            }
        }
    }

    fun completeService(reminder: Reminder, record: Record) {
        viewModelScope.launch {
            repository.update(syncHistory = true) { garage ->
                updateActive(garage) { ledger ->
                    val reminders = completeService(ledger.reminders, reminder, record)
                    ledger.copy(reminders = reminders, records = ledger.records + record)
                }
            }
            _state.value = _state.value.copy(message = "已完成并记账")
        }
    }

    fun saveReminder(reminder: Reminder) {
        viewModelScope.launch {
            repository.update(syncHistory = true) { garage ->
                updateActive(garage) { ledger ->
                    val exists = ledger.reminders.any { it.id == reminder.id }
                    val reminders = if (exists) {
                        ledger.reminders.map { if (it.id == reminder.id) reminder else it }
                    } else {
                        ledger.reminders + reminder
                    }
                    ledger.copy(reminders = reminders)
                }
            }
            _state.value = _state.value.copy(message = "提醒已保存")
        }
    }

    fun deleteReminder(reminder: Reminder) {
        confirm(
            PendingConfirm(
                title = "删除提醒？",
                message = reminder.title,
                confirmLabel = "删除",
                destructive = true,
            ) {
                viewModelScope.launch {
                    repository.update(syncHistory = true) { garage ->
                        updateActive(garage) { it.copy(reminders = it.reminders.filter { r -> r.id != reminder.id }) }
                    }
                }
            },
        )
    }

    // ---------- 导入 ----------

    /**
     * 合并账单导入结果；[fuelBasis] 非空时按整批油费口径处理（bear/qianji/both）。
     * 钱迹新增的保养记录会自动完成匹配的待办（与 Web 一致）。
     */
    fun applyImport(items: List<ImportItem>, fuelBasis: String? = null) {
        viewModelScope.launch {
            try {
                var added = 0
                var merged = 0
                var skipped = 0
                var completedServices = 0
                repository.update(syncHistory = true) { garage ->
                    updateActive(garage) { ledger ->
                        val result = if (fuelBasis != null) {
                            mergeFuelBatch(ledger.records, items, fuelBasis)
                        } else {
                            mergeImported(ledger.records, items)
                        }
                        added = result.added
                        merged = result.merged
                        skipped = result.skipped
                        val newIds = result.records
                            .filter { r -> ledger.records.none { it.id == r.id } }
                            .map { it.id }
                        val serviceResult = completeImportedServices(
                            ledger.copy(records = result.records),
                            newIds,
                        )
                        completedServices = serviceResult.completed
                        ledger.copy(
                            records = result.records,
                            reminders = serviceResult.reminders,
                        )
                    }
                }
                val extra = if (completedServices > 0) " · 自动完成 $completedServices 项保养" else ""
                _state.value = _state.value.copy(
                    message = "导入完成：新增 $added · 合并 $merged · 跳过 $skipped$extra",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "导入失败：${e.message}")
            }
        }
    }

    /** 保养台账导入：合并/更新提醒。 */
    fun applyServiceImport(reminders: List<Reminder>) {
        viewModelScope.launch {
            repository.update(syncHistory = true) { garage ->
                updateActive(garage) { ledger ->
                    val merged = ledger.reminders.toMutableList()
                    reminders.forEach { incoming ->
                        val index = merged.indexOfFirst { it.id == incoming.id }
                        if (index >= 0) merged[index] = incoming else merged.add(incoming)
                    }
                    ledger.copy(reminders = merged)
                }
            }
            _state.value = _state.value.copy(message = "保养提醒导入完成：${reminders.size} 项")
        }
    }

    // ---------- 备份 / 演示 ----------

    suspend fun exportBackup(): String = repository.exportBackup()

    fun restoreBackup(text: String) {
        viewModelScope.launch {
            try {
                val summary = repository.restoreBackup(text)
                _state.value = _state.value.copy(
                    message = "已恢复备份：${summary.cars} 辆车 · ${summary.records} 条记录 · ${summary.reminders} 条提醒",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "恢复失败：${e.message}")
            }
        }
    }

    fun enterDemo() {
        repository.enterDemo()
        _state.value = _state.value.copy(vehicleSwitcher = false, message = "已进入演示账本（不保存）")
    }

    fun exitDemo() {
        repository.exitDemo()
        _state.value = _state.value.copy(message = "已退出演示账本")
    }

    // ---------- WebDAV 同步 ----------

    fun saveWebDavConfig(url: String, username: String, password: String, allowHttp: Boolean) {
        if (_state.value.demo) {
            _state.value = _state.value.copy(webDav = _state.value.webDav.copy(error = "演示账本不支持 WebDAV 同步，请先退出演示"))
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null) }
            try {
                webDavRepository.save(url, username, password, allowHttp)
                val meta = webDavRepository.testConnection()
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = if (meta.exists) "连接成功，远端已有备份（${meta.size} 字节）" else "连接成功，远端文件尚不存在",
                        remoteExists = meta.exists,
                        remoteSize = meta.size,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: "WebDAV 配置保存失败") }
            }
        }
    }

    fun clearWebDavConfig() {
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null) }
            try {
                webDavRepository.clear()
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = "已清除 WebDAV 连接",
                        remoteExists = null,
                        remoteSize = null,
                        conflict = null,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: "清除失败") }
            }
        }
    }

    fun testWebDav() {
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null) }
            try {
                val meta = webDavRepository.testConnection()
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = if (meta.exists) "连接成功，远端已有备份" else "连接成功，远端文件尚不存在",
                        remoteExists = meta.exists,
                        remoteSize = meta.size,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: "连接失败") }
            }
        }
    }

    /** 智能同步：比较本地与远端，自动上传/下载，冲突时弹选择。 */
    fun webDavSyncNow() {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = "演示账本不支持 WebDAV 同步，请先退出演示") }
            return
        }
        viewModelScope.launch { runWebDavSync() }
    }

    /** 强制把本地备份覆盖到远端（用户确认后）。 */
    fun webDavUpload() {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = "演示账本不支持 WebDAV 同步，请先退出演示") }
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null) }
            try {
                val localJson = repository.exportBackup()
                val localBytes = localJson.toByteArray(Charsets.UTF_8)
                val localHash = webDavRepository.sha256(localBytes)
                val meta = webDavRepository.upload(localBytes, force = true)
                webDavRepository.markSynced(meta.etag, localHash, localHash)
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = "已上传并覆盖远端备份",
                        remoteExists = true,
                        remoteSize = localBytes.size.toLong(),
                        conflict = null,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: "上传失败") }
            }
        }
    }

    /** 从远端恢复：MERGE 只新增；REPLACE 覆盖本地。 */
    fun webDavDownload(mode: WebDavDownloadMode) {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = "演示账本不支持 WebDAV 同步，请先退出演示") }
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null) }
            try {
                when (val fetched = webDavRepository.fetch()) {
                    is WebDavFetchResult.NotFound -> {
                        updateWebDav { it.copy(busy = false, error = "远端没有备份文件，请先上传") }
                    }
                    is WebDavFetchResult.Found -> {
                        val text = fetched.bytes.toString(Charsets.UTF_8)
                        applyRemoteBackup(text, mode)
                        val localJson = repository.exportBackup()
                        val localHash = webDavRepository.sha256(localJson.toByteArray(Charsets.UTF_8))
                        val remoteHash = webDavRepository.sha256(fetched.bytes)
                        webDavRepository.markSynced(fetched.meta.etag, localHash, remoteHash)
                        updateWebDav {
                            it.copy(
                                busy = false,
                                lastResult = if (mode == WebDavDownloadMode.MERGE) "已从远端合并备份" else "已用远端备份覆盖本地",
                                remoteExists = true,
                                remoteSize = fetched.bytes.size.toLong(),
                                conflict = null,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: "下载失败") }
            }
        }
    }

    /** 冲突：保留本地/远端/合并。 */
    fun resolveWebDavConflict(choice: WebDavConflictChoice) {
        updateWebDav { it.copy(conflict = null) }
        when (choice) {
            WebDavConflictChoice.KEEP_LOCAL -> webDavUpload()
            WebDavConflictChoice.KEEP_REMOTE -> webDavDownload(WebDavDownloadMode.REPLACE)
            WebDavConflictChoice.MERGE -> {
                if (_state.value.demo) {
                    updateWebDav { it.copy(error = "演示账本不支持 WebDAV 同步，请先退出演示") }
                    return
                }
                viewModelScope.launch {
                    updateWebDav { it.copy(busy = true, error = null) }
                    try {
                        when (val fetched = webDavRepository.fetch()) {
                            is WebDavFetchResult.NotFound -> {
                                updateWebDav { it.copy(busy = false, error = "远端备份已不存在，请重新同步") }
                            }
                            is WebDavFetchResult.Found -> {
                                val text = fetched.bytes.toString(Charsets.UTF_8)
                                val current = _state.value.garage ?: repository.load()
                                val merged = BackupCodec.restore(current, text)
                                repository.replace(merged)
                                val mergedJson = repository.exportBackup()
                                val mergedBytes = mergedJson.toByteArray(Charsets.UTF_8)
                                val mergedHash = webDavRepository.sha256(mergedBytes)
                                val meta = webDavRepository.upload(mergedBytes, force = true)
                                webDavRepository.markSynced(meta.etag, mergedHash, mergedHash)
                                updateWebDav {
                                    it.copy(
                                        busy = false,
                                        lastResult = "已合并远端记录并上传合并结果",
                                        remoteExists = true,
                                        remoteSize = mergedBytes.size.toLong(),
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        updateWebDav { it.copy(busy = false, error = t.message ?: "合并失败") }
                    }
                }
            }
        }
    }

    fun dismissWebDavConflict() {
        updateWebDav { it.copy(conflict = null) }
    }

    fun dismissWebDavMessage() {
        updateWebDav { it.copy(error = null, lastResult = null) }
    }

    /** 开启/关闭 WebDAV 自动同步；开启后立即尝试一次。 */
    fun setWebDavAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWebDavAutoSync(enabled)
            if (enabled) autoSync()
        }
    }

    /** 应用打开时调用：开启自动同步且已配置时，静默同步一次。 */
    fun maybeAutoSyncOnOpen() {
        viewModelScope.launch {
            val enabled = settingsRepository.settings.first().webDavAutoSync
            if (enabled && !_state.value.demo && webDavRepository.canSync()) autoSync()
        }
    }

    private suspend fun autoSync() {
        if (_state.value.demo) return
        val outcome = webDavAutoSyncEngine.sync()
        if (outcome.kind == AutoSyncOutcome.Kind.SKIPPED) return
        if (outcome.kind == AutoSyncOutcome.Kind.CONFLICT) {
            _state.value = _state.value.copy(
                message = "自动同步发现冲突，请到 设置 → 数据管理 → WebDAV 手动处理",
            )
        }
        updateWebDav {
            it.copy(
                lastResult = outcome.message,
                error = if (outcome.kind == AutoSyncOutcome.Kind.ERROR) outcome.message else it.error,
            )
        }
    }

    private suspend fun runWebDavSync() {
        updateWebDav { it.copy(busy = true, error = null, lastResult = null, conflict = null) }
        val outcome = webDavAutoSyncEngine.sync(recordAutoResult = false)
        updateWebDav {
            it.copy(
                busy = false,
                lastResult = outcome.message,
                conflict = if (outcome.kind == AutoSyncOutcome.Kind.CONFLICT) {
                    WebDavConflict(localChanged = true, remoteChanged = true)
                } else {
                    it.conflict
                },
                error = if (outcome.kind == AutoSyncOutcome.Kind.ERROR) outcome.message else null,
            )
        }
    }

    private suspend fun applyRemoteBackup(text: String, mode: WebDavDownloadMode) {
        val current = _state.value.garage ?: repository.load()
        val next = when (mode) {
            WebDavDownloadMode.MERGE -> BackupCodec.restore(current, text)
            WebDavDownloadMode.REPLACE -> BackupCodec.decode(text)
        }
        repository.replace(next)
    }

    private fun updateWebDav(transform: (WebDavUiState) -> WebDavUiState) {
        _state.value = _state.value.copy(webDav = transform(_state.value.webDav))
    }

    // ---------- 确认弹层 ----------

    fun confirm(request: PendingConfirm) {
        _state.value = _state.value.copy(confirm = request)
    }

    fun dismissConfirm() {
        _state.value = _state.value.copy(confirm = null)
    }

    fun runConfirm() {
        val action = _state.value.confirm?.action
        _state.value = _state.value.copy(confirm = null)
        action?.invoke()
    }

    // ---------- 工具 ----------

    private fun updateActive(garage: Garage, transform: (Ledger) -> Ledger): Garage {
        val active = garage.activeCar ?: return garage
        val updated = transform(active)
        return garage.copy(cars = garage.cars.map { if (it.id == active.id) updated else it })
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MileNoteViewModel(
                    container.garageRepository,
                    container.settingsRepository,
                    container.webDavRepository,
                    container.webDavAutoSyncEngine,
                )
            }
        }
    }
}
