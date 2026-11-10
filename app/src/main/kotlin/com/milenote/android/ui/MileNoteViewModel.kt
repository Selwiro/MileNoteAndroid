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
import com.milenote.android.data.webdav.RemoteLedger
import com.milenote.android.data.webdav.WebDavAutoSyncEngine
import com.milenote.android.data.webdav.WebDavFetchResult
import com.milenote.android.data.webdav.WebDavRemoteMeta
import com.milenote.android.data.webdav.WebDavRepository
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.LedgerCrypto
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.DateRange
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Period
import com.milenote.android.domain.calc.applyServicePlan
import com.milenote.android.domain.calc.completeService
import com.milenote.android.domain.calc.restoreServiceRemindersAfterRecordDelete
import com.milenote.android.domain.calc.currentKm
import com.milenote.android.domain.calc.defaultServicePlan
import com.milenote.android.domain.calc.defaultServiceReminders
import com.milenote.android.domain.calc.EV_ONLY_SERVICE_TITLES
import com.milenote.android.domain.calc.FUEL_ONLY_SERVICE_TITLES
import com.milenote.android.domain.calc.syncServiceHistory
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_FUEL
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.PlanEntry
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.powertrainKey
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.calc.completeImportedServices
import com.milenote.android.domain.calc.duplicateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记一笔/编辑记录的目标。 */
data class EditorTarget(
    val record: Record? = null,
    val category: String = "油费",
    val serviceReminderId: String? = null,
    /** 打开编辑器后是否直接进入识票（快捷方式「油费识别」）。 */
    val startOcr: Boolean = false,
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
    val confirmLabel: String = tr("确定", "OK"),
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

/** 需要用户输入加密密码的待办动作（解密成功后重跑）。 */
enum class PendingWebDavAction { DOWNLOAD_REPLACE, DOWNLOAD_MERGE, SYNC, MERGE_CONFLICT }

/** 远端账本已加密、需要用户输入密码的弹层状态。 */
data class WebDavDecryptPrompt(
    val pending: PendingWebDavAction,
    /** 弹层内错误（密码错误等）；null 表示无错误。 */
    val error: String? = null,
)

/** WebDAV 同步页状态（VM 维护）。 */
data class WebDavUiState(
    val configured: Boolean = false,
    val fileUrl: String = "",
    val username: String = "",
    val allowHttp: Boolean = false,
    val hasPassword: Boolean = false,
    /** 是否已保存账本加密密码（= 加密上传已开启）。 */
    val hasEncryptionPassword: Boolean = false,
    /** 上次读到的远端格式：null=未知，false=明文，true=加密。 */
    val remoteEncrypted: Boolean? = null,
    /** 远端加密但本机没有密码时的密码输入弹层；null 表示无。 */
    val decryptPrompt: WebDavDecryptPrompt? = null,
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

/** 最近一次被隔离的损坏账本文件（设置页「导出原始账本」用）。 */
data class QuarantinedRaw(
    val name: String,
    val savedAtMillis: Long,
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
    /** 最近一次读取失败被隔离的原始账本；仅用于设置页导出入口。 */
    val quarantinedRaw: QuarantinedRaw? = null,
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
                            hasEncryptionPassword = config?.hasEncryptionPassword ?: false,
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

    fun openEditor(
        recordId: String? = null,
        category: String = "油费",
        serviceReminderId: String? = null,
        startOcr: Boolean = false,
    ) {
        val record = recordId?.let { id -> _state.value.activeCar?.records?.firstOrNull { it.id == id } }
        // 纯电车辆不支持加油票识别（OCR 解析器只认油票）：快捷方式降级为普通「记一笔」并提示。
        // 分类存储值仍是「油费」，纯电界面上由 DataLabels 显示为「电费」。
        val ocrUnsupported = startOcr && _state.value.activeCar?.isEv == true
        _state.value = _state.value.copy(
            editor = EditorTarget(
                record = record,
                category = category,
                serviceReminderId = serviceReminderId,
                startOcr = startOcr && !ocrUnsupported,
            ),
            message = if (ocrUnsupported) {
                tr("纯电车辆不支持加油票识别，已打开记一笔", "Fuel receipt scanning isn't available for EVs — opened a new entry instead.")
            } else {
                _state.value.message
            },
        )
    }

    fun closeEditor() {
        _state.value = _state.value.copy(editor = null)
    }

    fun saveRecord(record: Record) {
        // 能源费分类显示名随能量档位：纯电「电费」、插混「能源」；燃油车保持原提示文案不变。
        val profile = _state.value.activeCar?.vehicle?.energyProfile ?: EnergyProfile.FUEL_ONLY
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
            _state.value = _state.value.copy(
                editor = null,
                message = if (profile != EnergyProfile.FUEL_ONLY) {
                    tr("已保存 · %s", "Saved · %s", DataLabels.category(record.category, profile))
                } else {
                    tr("已保存", "Saved")
                },
            )
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
                title = tr("删除这条记录？", "Delete this record?"),
                message = "${Dates.displayDots(record.date)} · ${DataLabels.category(record.category)} · ${Units.currencySymbol}${Format.money(record.amount)}",
                confirmLabel = tr("删除", "Delete"),
                destructive = true,
            ) {
                viewModelScope.launch {
                    repository.update(syncHistory = true) { garage ->
                        updateActive(garage) { it.copy(records = it.records.filter { r -> r.id != record.id }) }
                    }
                    _state.value = _state.value.copy(message = tr("已删除", "Deleted"))
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
                message = if (restoreReminders) tr("已删除，保养提醒已恢复", "Deleted — service reminder restored") else tr("已删除", "Deleted"),
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

    /**
     * 保存车辆信息。
     *
     * 动力类型相对当前账本改变时执行迁移（设计 §6.3）：
     * `servicePlan` 只丢弃「旧动力类型专属」的 key（共用 key 的周期覆盖与自定义 key 保留）；
     * [prunePowertrainReminders] 为 true 时删除「未完成且只属于旧动力类型」的提醒，
     * 已完成提醒与历史记录保留为历史，`currentKm / startKm` 不变。
     *
     * @param prunePowertrainReminders 切换动力类型时是否一并删除不再适用的未完成保养提醒
     *   （默认 false，保持既有调用点行为不变）。
     */
    fun saveVehicle(vehicle: Vehicle, prunePowertrainReminders: Boolean = false) {
        // 插混 / 纯电不保留旧版油费成本口径（否则钱迹来源的加油/充电记录会被判成 0）。
        val nextVehicle = if (vehicle.powertrainKey != POWERTRAIN_FUEL) {
            vehicle.copy(fuelCostSource = null)
        } else {
            vehicle
        }
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                updateActive(garage) { ledger ->
                    var next = ledger.copy(vehicle = nextVehicle)
                    if (ledger.vehicle.powertrainKey != nextVehicle.powertrainKey) {
                        next = migratePowertrain(
                            ledger = next,
                            fromEv = ledger.vehicle.isEv,
                            toEv = nextVehicle.isEv,
                            pruneReminders = prunePowertrainReminders,
                        )
                    }
                    next
                }
            }
            _state.value = _state.value.copy(showVehicleEditor = false, message = tr("车辆信息已保存", "Vehicle saved"))
        }
    }

    fun addVehicle(vehicle: Vehicle) {
        // 新账本没有历史可保留：插混 / 纯电清掉旧版油费口径；纯电连燃油标号一并清空。
        val nextVehicle = when (vehicle.powertrainKey) {
            POWERTRAIN_EV -> vehicle.copy(fuelCostSource = null, fuelGrade = null)
            POWERTRAIN_PHEV -> vehicle.copy(fuelCostSource = null)
            else -> vehicle
        }
        viewModelScope.launch {
            repository.update(syncHistory = false) { garage ->
                val ledger = blankLedger(powertrain = nextVehicle.powertrain)
                    .copy(id = Ids.newId(), vehicle = nextVehicle)
                garage.copy(cars = garage.cars + ledger, activeId = ledger.id)
            }
            _state.value = _state.value.copy(showVehicleEditor = false, vehicleSwitcher = false, message = tr("已添加车辆", "Vehicle added"))
        }
    }

    fun deleteVehicle(id: String) {
        val rawName = _state.value.garage?.cars?.firstOrNull { it.id == id }?.vehicle?.name
        val name = if (rawName.isNullOrBlank()) tr("这辆车", "this car") else DataLabels.vehicleName(rawName)
        confirm(
            PendingConfirm(
                title = tr("删除车辆", "Delete vehicle"),
                message = tr("将删除「%s」及其全部费用、提醒与保养记录。建议先导出完整备份。", "This deletes \"%s\" and all its expenses, reminders and service records. Export a full backup first.", name),
                confirmLabel = tr("删除", "Delete"),
                destructive = true,
            ) {
                viewModelScope.launch {
                    repository.deleteData("one", id)
                    _state.value = _state.value.copy(message = tr("已删除车辆", "Vehicle deleted"))
                }
            },
        )
    }

    fun deleteAllData() {
        val confirmWord = tr("删除", "DELETE")
        confirm(
            PendingConfirm(
                title = tr("删除全部数据", "Delete all data"),
                message = tr(
                    "将删除所有车辆、费用、提醒与保养记录，且不可恢复。请输入「%s」确认。",
                    "This deletes all vehicles, expenses, reminders and service records. It cannot be undone. Type \"%s\" to confirm.",
                    confirmWord,
                ),
                confirmLabel = tr("永久删除", "Delete permanently"),
                destructive = true,
                requireText = confirmWord,
            ) {
                viewModelScope.launch {
                    repository.deleteData("all")
                    _state.value = _state.value.copy(message = tr("已清空数据", "All data cleared"))
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

    /** 切换界面语言（跟随系统 / 中文 / English）。 */
    fun setLanguage(language: com.milenote.android.i18n.AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    /** 货币显示单位；null 表示跟随界面语言（中文元 / 英文美元）。 */
    fun setCurrency(currency: com.milenote.android.i18n.CurrencyUnit?) {
        viewModelScope.launch { settingsRepository.setCurrency(currency) }
    }

    /** 里程显示单位（公里 / 英里，仅显示）。 */
    fun setDistanceUnit(unit: com.milenote.android.i18n.DistanceUnit) {
        viewModelScope.launch { settingsRepository.setDistanceUnit(unit) }
    }

    /** 油量显示单位（升 / 加仑，仅显示）。 */
    fun setVolumeUnit(unit: com.milenote.android.i18n.VolumeUnit) {
        viewModelScope.launch { settingsRepository.setVolumeUnit(unit) }
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
                _state.value = _state.value.copy(showServicePlanEditor = false, message = tr("保养方案已保存", "Service plan saved"))
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: tr("保存失败", "Save failed"))
            }
        }
    }

    fun useDefaultServicePlan(baseDate: String, baseKm: Double, includeFirst: Boolean) {
        viewModelScope.launch {
            try {
                repository.update(syncHistory = true) { garage ->
                    updateActive(garage) { ledger ->
                        val reminders = defaultServiceReminders(baseDate, baseKm, includeFirst, ev = ledger.isEv)
                        val existing = ledger.reminders.filter { r ->
                            reminders.none { n -> n.presetKey == r.presetKey || n.title == r.title }
                        }
                        ledger.copy(reminders = existing + reminders, servicePlanConfigured = true)
                    }
                }
                _state.value = _state.value.copy(showServicePlanEditor = false, message = tr("已启用默认保养方案", "Default service plan enabled"))
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = e.message ?: tr("启用失败", "Enable failed"))
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
            _state.value = _state.value.copy(message = tr("已完成并记账", "Completed and logged"))
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
            _state.value = _state.value.copy(message = tr("提醒已保存", "Reminder saved"))
        }
    }

    fun deleteReminder(reminder: Reminder) {
        confirm(
            PendingConfirm(
                title = tr("删除提醒？", "Delete reminder?"),
                message = DataLabels.serviceItem(reminder.title),
                confirmLabel = tr("删除", "Delete"),
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
    fun applyImport(
        items: List<ImportItem>,
        fuelBasis: String? = null,
        overwriteDuplicates: Boolean = false,
    ) {
        viewModelScope.launch {
            try {
                var added = 0
                var merged = 0
                var skipped = 0
                var overwritten = 0
                var completedServices = 0
                repository.update(syncHistory = true) { garage ->
                    updateActive(garage) { ledger ->
                        // 覆盖模式：先移除命中的旧记录，再走正常合并把导入数据作为新记录写回。
                        val baseRecords = if (overwriteDuplicates) {
                            val kept = ledger.records.toMutableList()
                            items.filter { it.isValid }.forEach { item ->
                                val old = duplicateOf(kept, item.record)
                                if (old != null) {
                                    kept.removeAll { it.id == old.id }
                                    overwritten++
                                }
                            }
                            kept.toList()
                        } else {
                            ledger.records
                        }
                        val result = if (fuelBasis != null) {
                            mergeFuelBatch(baseRecords, items, fuelBasis)
                        } else {
                            mergeImported(baseRecords, items)
                        }
                        added = result.added
                        merged = result.merged
                        skipped = result.skipped
                        val newIds = result.records
                            .filter { r -> baseRecords.none { it.id == r.id } }
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
                val extra = if (completedServices > 0) tr(" · 自动完成 %d 项保养", " · auto-completed %d service items", completedServices) else ""
                val overwriteText = if (overwritten > 0) tr(" · 覆盖 %d", " · overwritten %d", overwritten) else ""
                _state.value = _state.value.copy(
                    message = tr(
                        "导入完成：新增 %d · 合并 %d · 跳过 %d%s%s",
                        "Import complete: %d added · %d merged · %d skipped%s%s",
                        added, merged, skipped, overwriteText, extra,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = tr("导入失败：%s", "Import failed: %s", e.message))
            }
        }
    }

    // ---------- 备份 / 演示 ----------

    suspend fun exportBackup(): String = repository.exportBackup()

    /**
     * 标记一次 JSON 完整备份已成功写到磁盘（写 [SettingsRepository.setLastBackupAt]）。
     *
     * 只在 SAF 真正写盘成功后由设置页调用；取消（未选文件）或写盘失败不调用，
     * 「分享」二次入口也不重复调用。时间只存本机设置，不进账本备份。
     */
    fun markBackupDone(nowMillis: Long = System.currentTimeMillis()) {
        viewModelScope.launch { settingsRepository.setLastBackupAt(nowMillis) }
    }

    /** 刷新「最近一次损坏账本」状态；进入备份与还原页时调用。 */
    suspend fun refreshQuarantinedRaw() {
        val file = repository.quarantinedRaw()
        _state.value = _state.value.copy(
            quarantinedRaw = file?.let { QuarantinedRaw(name = it.name, savedAtMillis = it.lastModified()) },
        )
    }

    /** 读取损坏账本的原始字节；读取失败或不存在返回 null。 */
    suspend fun readQuarantinedRaw(): ByteArray? = withContext(Dispatchers.IO) {
        val file = repository.quarantinedRaw() ?: return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    fun restoreBackup(text: String) {
        viewModelScope.launch {
            try {
                val summary = repository.restoreBackup(text)
                _state.value = _state.value.copy(
                    message = tr(
                        "已恢复备份：%d 辆车 · %d 条记录 · %d 条提醒",
                        "Backup restored: %d vehicles · %d records · %d reminders",
                        summary.cars, summary.records, summary.reminders,
                    ),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = tr("恢复失败：%s", "Restore failed: %s", e.message))
            }
        }
    }

    fun enterDemo() {
        repository.enterDemo()
        _state.value = _state.value.copy(vehicleSwitcher = false, message = tr("已进入演示账本（不保存）", "Demo ledger opened (changes are not saved)"))
    }

    fun exitDemo() {
        repository.exitDemo()
        _state.value = _state.value.copy(message = tr("已退出演示账本", "Demo ledger closed"))
    }

    // ---------- WebDAV 同步 ----------

    /**
     * 保存 WebDAV 配置（含可选的账本加密密码），随后自动测试连接。
     *
     * @param encryptionEnabled 是否开启加密上传；关闭时清除本机保存的加密密码（远端文件不动）。
     * @param encryptionPassword 新的加密密码明文；null/空白表示沿用已保存的密码。
     */
    fun saveWebDavConfig(
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean,
        encryptionEnabled: Boolean,
        encryptionPassword: String?,
    ) {
        if (_state.value.demo) {
            _state.value = _state.value.copy(webDav = _state.value.webDav.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")))
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null) }
            try {
                webDavRepository.save(url, username, password, allowHttp, encryptionPassword, encryptionEnabled)
                val meta = webDavRepository.testConnection()
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = if (meta.exists) {
                            tr("连接成功，远端已有备份（%d 字节）", "Connected — a backup exists on the server (%d bytes)", meta.size)
                        } else {
                            tr("连接成功，远端文件尚不存在", "Connected — no backup on the server yet")
                        },
                        remoteExists = meta.exists,
                        remoteSize = meta.size,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: tr("WebDAV 配置保存失败", "Could not save WebDAV settings")) }
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
                        lastResult = tr("已清除 WebDAV 连接", "WebDAV connection cleared"),
                        remoteExists = null,
                        remoteSize = null,
                        remoteEncrypted = null,
                        decryptPrompt = null,
                        conflict = null,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: tr("清除失败", "Clear failed")) }
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
                        lastResult = if (meta.exists) {
                            tr("连接成功，远端已有备份", "Connected — a backup exists on the server")
                        } else {
                            tr("连接成功，远端文件尚不存在", "Connected — no backup on the server yet")
                        },
                        remoteExists = meta.exists,
                        remoteSize = meta.size,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: tr("连接失败", "Connection failed")) }
            }
        }
    }

    /** 智能同步：比较本地与远端，自动上传/下载，冲突时弹选择。 */
    fun webDavSyncNow() {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")) }
            return
        }
        viewModelScope.launch { runWebDavSync() }
    }

    /** 强制把本地备份覆盖到远端（用户确认后）；沿用已保存的加密配置决定明文/密文。 */
    fun webDavUpload() {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")) }
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null, remoteEncrypted = null) }
            try {
                val localJson = repository.exportBackup()
                val localHash = webDavRepository.plainHash(localJson)
                val meta = webDavRepository.uploadLedger(localJson, force = true)
                val encrypted = webDavRepository.hasEncryptionPassword()
                webDavRepository.markSynced(meta.etag, localHash, localHash)
                updateWebDav {
                    it.copy(
                        busy = false,
                        lastResult = tr("已上传并覆盖远端备份", "Uploaded and overwrote the remote backup"),
                        remoteExists = true,
                        remoteSize = meta.size,
                        remoteEncrypted = encrypted,
                        decryptPrompt = null,
                        conflict = null,
                    )
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: tr("上传失败", "Upload failed")) }
            }
        }
    }

    /** 从远端恢复：MERGE 只新增；REPLACE 覆盖本地。远端加密且本机无密码时弹密码框。 */
    fun webDavDownload(mode: WebDavDownloadMode) {
        if (_state.value.demo) {
            updateWebDav { it.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")) }
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, lastResult = null) }
            try {
                when (val fetched = webDavRepository.fetchLedger()) {
                    is WebDavFetchResult.NotFound -> {
                        updateWebDav {
                            it.copy(
                                busy = false,
                                error = tr("远端没有备份文件，请先上传", "No backup on the server. Upload one first."),
                                remoteExists = false,
                                remoteSize = null,
                                remoteEncrypted = null,
                            )
                        }
                    }
                    is WebDavFetchResult.PasswordRequired -> {
                        askWebDavDecryptPassword(
                            if (mode == WebDavDownloadMode.MERGE) PendingWebDavAction.DOWNLOAD_MERGE else PendingWebDavAction.DOWNLOAD_REPLACE,
                        )
                    }
                    is WebDavFetchResult.Found -> {
                        val text = fetched.ledger.text
                        applyRemoteBackup(text, mode)
                        val localJson = repository.exportBackup()
                        val localHash = webDavRepository.plainHash(localJson)
                        val remoteHash = webDavRepository.plainHash(text)
                        webDavRepository.markSynced(fetched.meta.etag, localHash, remoteHash)
                        updateWebDav {
                            it.copy(
                                busy = false,
                                lastResult = if (mode == WebDavDownloadMode.MERGE) {
                                    tr("已从远端合并备份", "Merged the remote backup")
                                } else {
                                    tr("已用远端备份覆盖本地", "Replaced local data with the remote backup")
                                },
                                remoteExists = true,
                                remoteSize = fetched.bytes.size.toLong(),
                                remoteEncrypted = fetched.ledger is RemoteLedger.Decrypted,
                                decryptPrompt = null,
                                conflict = null,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                updateWebDav { it.copy(busy = false, error = t.message ?: tr("下载失败", "Download failed")) }
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
                    updateWebDav { it.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")) }
                    return
                }
                viewModelScope.launch {
                    updateWebDav { it.copy(busy = true, error = null) }
                    try {
                        when (val fetched = webDavRepository.fetchLedger()) {
                            is WebDavFetchResult.NotFound -> {
                                updateWebDav { it.copy(busy = false, error = tr("远端备份已不存在，请重新同步", "The remote backup no longer exists. Sync again.")) }
                            }
                            is WebDavFetchResult.PasswordRequired -> {
                                askWebDavDecryptPassword(PendingWebDavAction.MERGE_CONFLICT)
                            }
                            is WebDavFetchResult.Found -> {
                                val text = fetched.ledger.text
                                val current = _state.value.garage ?: repository.load()
                                val merged = BackupCodec.restore(current, text)
                                repository.replace(merged)
                                val mergedJson = repository.exportBackup()
                                val mergedHash = webDavRepository.plainHash(mergedJson)
                                val meta = webDavRepository.uploadLedger(mergedJson, force = true)
                                webDavRepository.markSynced(meta.etag, mergedHash, mergedHash)
                                updateWebDav {
                                    it.copy(
                                        busy = false,
                                        lastResult = tr("已合并远端记录并上传合并结果", "Merged remote records and uploaded the result"),
                                        remoteExists = true,
                                        remoteSize = meta.size,
                                        remoteEncrypted = fetched.ledger is RemoteLedger.Decrypted,
                                        decryptPrompt = null,
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        updateWebDav { it.copy(busy = false, error = t.message ?: tr("合并失败", "Merge failed")) }
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

    /**
     * 提交解密密码：先用 [LedgerCrypto] 对已缓存的远端信封做本地校验，
     * **验证成功才落库**（保存加密密码 = 开启加密上传，防止后续把远端降级成明文），然后重跑待办动作。
     */
    fun submitWebDavDecryptPassword(password: String) {
        val prompt = _state.value.webDav.decryptPrompt ?: return
        if (password.isBlank()) {
            updateWebDav {
                it.copy(decryptPrompt = prompt.copy(error = tr("请输入加密密码", "Enter the encryption password")))
            }
            return
        }
        if (_state.value.demo) {
            updateWebDav {
                it.copy(decryptPrompt = prompt.copy(error = tr("演示账本不支持 WebDAV 同步，请先退出演示", "WebDAV sync is not available in the demo ledger. Exit the demo first.")))
            }
            return
        }
        viewModelScope.launch {
            updateWebDav { it.copy(busy = true, error = null, decryptPrompt = prompt.copy(error = null)) }
            val envelope = webDavRepository.cachedEncryptedRemoteBytes()?.toString(Charsets.UTF_8)
            if (envelope == null || !LedgerCrypto.isEncrypted(envelope)) {
                updateWebDav {
                    it.copy(
                        busy = false,
                        decryptPrompt = prompt.copy(error = tr("远端账本内容已变化，请重新同步", "The remote ledger has changed — sync again")),
                    )
                }
                return@launch
            }
            val decryptError = try {
                LedgerCrypto.decryptIfEncrypted(envelope, password)
                null
            } catch (t: Throwable) {
                t.message ?: tr("解密失败，请重试", "Decryption failed — try again")
            }
            if (decryptError != null) {
                // 密码错误不落库，只把错误写进弹层。
                updateWebDav { it.copy(busy = false, decryptPrompt = prompt.copy(error = decryptError)) }
                return@launch
            }
            try {
                webDavRepository.saveEncryptionPassword(password)
            } catch (t: Throwable) {
                updateWebDav {
                    it.copy(
                        busy = false,
                        decryptPrompt = prompt.copy(error = t.message ?: tr("加密密码保存失败", "Could not save the encryption password")),
                    )
                }
                return@launch
            }
            updateWebDav { it.copy(busy = false, decryptPrompt = null, remoteEncrypted = true) }
            // 重跑刚才被密码拦住的动作。
            when (prompt.pending) {
                PendingWebDavAction.DOWNLOAD_REPLACE -> webDavDownload(WebDavDownloadMode.REPLACE)
                PendingWebDavAction.DOWNLOAD_MERGE -> webDavDownload(WebDavDownloadMode.MERGE)
                PendingWebDavAction.SYNC -> webDavSyncNow()
                PendingWebDavAction.MERGE_CONFLICT -> resolveWebDavConflict(WebDavConflictChoice.MERGE)
            }
        }
    }

    /** 关闭解密密码弹层（保持现状，不做任何同步）。 */
    fun cancelWebDavDecrypt() {
        updateWebDav { it.copy(decryptPrompt = null, busy = false) }
    }

    /** 远端加密但本机没有密码：记录待办动作并弹密码框，不报错。 */
    private fun askWebDavDecryptPassword(pending: PendingWebDavAction) {
        updateWebDav {
            it.copy(
                busy = false,
                error = null,
                decryptPrompt = WebDavDecryptPrompt(pending),
                remoteEncrypted = true,
            )
        }
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
                message = tr("自动同步发现冲突，请到 设置 → WebDAV 同步 手动处理", "Auto sync found a conflict. Resolve it in Settings → WebDAV sync."),
            )
        }
        if (outcome.kind == AutoSyncOutcome.Kind.NEEDS_PASSWORD) {
            // 自动同步不弹密码框，只提示用户去设置页输入。
            _state.value = _state.value.copy(message = outcome.message)
        }
        updateWebDav {
            it.copy(
                lastResult = outcome.message,
                error = if (outcome.kind == AutoSyncOutcome.Kind.ERROR) outcome.message else it.error,
                remoteEncrypted = when (outcome.kind) {
                    AutoSyncOutcome.Kind.NEEDS_PASSWORD -> true
                    else -> it.remoteEncrypted
                },
            )
        }
    }

    private suspend fun runWebDavSync() {
        updateWebDav { it.copy(busy = true, error = null, lastResult = null, conflict = null) }
        val outcome = webDavAutoSyncEngine.sync(recordAutoResult = false)
        if (outcome.kind == AutoSyncOutcome.Kind.NEEDS_PASSWORD) {
            // 手动路径：弹密码框而不是报错。
            askWebDavDecryptPassword(PendingWebDavAction.SYNC)
            return
        }
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

    /**
     * 动力类型切换后的账本迁移（设计 §6.3）。
     *
     * 1. `servicePlan` 只丢弃旧动力类型专属的 key（燃油→纯电 7 项、纯电→燃油 `ev-diag`）；
     *    共用 key 的用户周期覆盖与自定义 key 保留（与 `resolveServicePlan` 的取舍一致）；
     * 2. [pruneReminders] 时删除未完成且只属于旧动力类型的提醒（按旧方案 key / 旧类型专属标题）；
     *    已完成提醒与费用记录保留为历史；
     * 3. `currentKm / startKm / records` 不动。
     */
    private fun migratePowertrain(
        ledger: Ledger,
        fromEv: Boolean,
        toEv: Boolean,
        pruneReminders: Boolean,
    ): Ledger {
        if (fromEv == toEv) return ledger
        val oldKeys = defaultServicePlan(fromEv).map { it.key }.toSet()
        val newKeys = defaultServicePlan(toEv).map { it.key }.toSet()
        val staleKeys = oldKeys - newKeys
        val staleTitles = if (toEv) FUEL_ONLY_SERVICE_TITLES else EV_ONLY_SERVICE_TITLES
        val plan = ledger.servicePlan?.filter { it.key in newKeys || it.key !in oldKeys }
        val reminders = if (pruneReminders) {
            ledger.reminders.filterNot { reminder ->
                reminder.done != true &&
                    (reminder.presetKey?.let { it in staleKeys } == true || reminder.title in staleTitles)
            }
        } else {
            ledger.reminders
        }
        return ledger.copy(servicePlan = plan, reminders = reminders)
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
