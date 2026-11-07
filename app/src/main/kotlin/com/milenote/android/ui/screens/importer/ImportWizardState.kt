package com.milenote.android.ui.screens.importer

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milenote.android.domain.calc.duplicateOf
import com.milenote.android.domain.calc.fuelBatchConflict
import com.milenote.android.domain.calc.mergeFuelBatch
import com.milenote.android.domain.import.ImportItem
import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.file.SpreadsheetData
import com.milenote.android.domain.import.normalizeImport
import com.milenote.android.ui.MileNoteViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入向导的状态机与全部业务动作。
 *
 * 只依赖 VM 暴露的 2 个公开方法（`applyImport` / `restoreBackup`）
 * 与 domain 的纯函数；文件读取、字段映射、预览、油费口径选择都在本类完成。
 */
@Stable
internal class ImportWizardState(
    private val vm: MileNoteViewModel,
    private val scope: CoroutineScope,
    private val context: android.content.Context,
) {
    // ---------- 导航与通用状态 ----------

    /** 0=选择来源，1=读取与预览，2=完成。 */
    var step by mutableIntStateOf(0)
        private set

    var source by mutableStateOf<ImportSource?>(null)
        private set

    /** 是否由系统分享进入（用于顶部「来自分享」提示）。 */
    var fromShare by mutableStateOf(false)
        private set

    /** 分享来源的展示文件名；未分享时为空。 */
    var sharedFileName by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    var busyMessage by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    // ---------- 文件 / 表格 ----------

    var picked by mutableStateOf<PickedFile?>(null)
        private set

    var sheetData by mutableStateOf<SpreadsheetData?>(null)
        private set

    var sheetNames by mutableStateOf<List<String>>(emptyList())
        private set

    var mapping by mutableStateOf<Map<String, Int?>>(emptyMap())
        private set

    // ---------- 费用导入预览 ----------

    var tableItems by mutableStateOf<List<ImportItem>>(emptyList())
        private set

    /** 与现有账本重复的预览行下标（仅展示，实际由 merge 判重）。 */
    var duplicates by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** 用户取消勾选的行下标。 */
    var excluded by mutableStateOf<Set<Int>>(emptySet())
        private set

    var fuelConflict by mutableStateOf<com.milenote.android.domain.calc.FuelBatchConflict?>(null)
        private set

    var fuelBasis by mutableStateOf("bear")
        private set

    var defaultCategory by mutableStateOf("")
        private set

    // ---------- 备份 ----------

    var backupPreview by mutableStateOf<BackupPreview?>(null)
        private set

    private var backupText: String? = null

    // ---------- 结果 ----------

    var result by mutableStateOf<WizardResult?>(null)
        private set

    private var previewGeneration = 0

    private val ledger get() = vm.state.value.activeCar
    private val records get() = ledger?.records.orEmpty()
    private val customCategories get() = ledger?.customCategories.orEmpty()

    // ---------- 来源与文件 ----------

    fun selectSource(value: ImportSource) {
        source = value
        resetFile()
        error = null
    }

    fun updateError(message: String?) {
        error = message
    }

    fun resetFile() {
        picked = null
        sheetData = null
        sheetNames = emptyList()
        mapping = emptyMap()
        tableItems = emptyList()
        duplicates = emptySet()
        excluded = emptySet()
        fuelConflict = null
        defaultCategory = ""
        backupPreview = null
        backupText = null
        result = null
        error = null
        previewGeneration++
    }

    fun goBack(onClose: () -> Unit) {
        when (step) {
            0 -> onClose()
            1 -> {
                resetFile()
                fromShare = false
                sharedFileName = ""
                step = 0
            }
            else -> onClose()
        }
    }

    /** 读取 SAF URI 并解析；解析失败时保留 [picked] 供「重试」。 */
    suspend fun load(uri: Uri) {
        val src = source ?: run {
            error = "请先选择导入来源"
            return
        }
        busy = true
        busyMessage = "正在读取文件…"
        error = null
        try {
            val name = queryDisplayName(context, uri) ?: "未命名文件"
            val isBackup = src == ImportSource.BACKUP
            val limit = if (isBackup) BACKUP_MAX_BYTES else TABLE_MAX_BYTES
            val limitMessage = if (isBackup) {
                "备份文件超过 20 MB，无法恢复"
            } else {
                "文件超过 10 MB，请拆分后再导入"
            }
            val size = queryFileSize(context, uri)
            if (size != null && size > limit) throw ImportFileException(limitMessage)
            val bytes = withContext(Dispatchers.IO) { readUriBytes(context, uri, limit, limitMessage) }
            if (bytes.isEmpty()) throw ImportFileException("文件是空的，请选择有效文件")
            picked = PickedFile(name, bytes.size.toLong(), bytes)
            parsePicked(src, name, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImportWizard", "load(uri) failed", e)
            error = e.message ?: "读取文件失败，请重新选择"
        } finally {
            busy = false
            busyMessage = null
        }
    }

    /**
     * 系统分享（ACTION_SEND）进来时调用：自动识别来源并直接进入「读取与预览」。
     *
     * - 文本 / 文件字节统一走 [readSpreadsheet]（xlsx/xls 按文件签名，JSON/CSV/TXT 按内容）；
     * - 来源判断：表头「总里程/加油量/日期时间」→ 小熊；「时间/类型/金额」→ 钱迹；
     *   文件名/ MIME 兜底；无法判断默认钱迹（用户可在界面「重新选择来源」）；
     * - 若分享内容像 Web 备份 JSON（version + vehicle/cars），按备份恢复处理。
     * - 10 MB / 20,000 行限制由 domain 校验，错误 message 可直接展示。
     */
    suspend fun loadShared(bytes: ByteArray, fileName: String, mimeType: String?) {
        if (busy) return
        fromShare = true
        sharedFileName = fileName.ifBlank { "分享导入" }
        error = null
        if (bytes.isEmpty()) {
            error = "分享内容为空，无法导入"
            return
        }
        busy = true
        busyMessage = "正在读取分享内容…"
        try {
            val rawName = fileName.ifBlank { "分享导入" }
            // 20 MB 级文本解码放到后台，避免主线程卡顿。
            val trimmed = withContext(Dispatchers.Default) {
                runCatching { decodeText(bytes) }.getOrNull()?.trim()
            }
            val textContent = trimmed.orEmpty()
            val jsonLike = textContent.startsWith("[") || textContent.startsWith("{")
            if (jsonLike && looksLikeBackupJson(textContent)) {
                if (bytes.size > BACKUP_MAX_BYTES) {
                    throw ImportFileException("备份文件超过 20 MB，无法恢复")
                }
                val name = ensureExtension(rawName, "json")
                source = ImportSource.BACKUP
                picked = PickedFile(name, bytes.size.toLong(), bytes)
                val backupTextValue = textContent
                backupText = backupTextValue
                backupPreview = withContext(Dispatchers.Default) { inspectBackup(backupTextValue) }
                step = 1
                return
            }
            if (bytes.size > TABLE_MAX_BYTES) {
                throw ImportFileException("文件超过 10 MB，请拆分后再导入")
            }
            val name = ensureExtension(rawName, if (jsonLike) "json" else "csv")
            val data = withContext(Dispatchers.IO) {
                readSpreadsheet(bytes, name, allowJsonFallback = true)
            }
            val detected = detectSharedSource(name, data.headers, mimeType)
            source = detected
            picked = PickedFile(name, bytes.size.toLong(), bytes)
            setupSheet(data)
            if (detected == ImportSource.QIANJI) applyQianjiHeuristics()
            excluded = emptySet()
            fuelBasis = "bear"
            step = 1
            scheduleTablePreview()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImportWizard", "loadShared failed", e)
            error = e.message ?: "分享内容解析失败，请重试或手动选择文件"
        } finally {
            busy = false
            busyMessage = null
        }
    }

    /** 分享识别错误时回到来源选择；不清空账本，也不残留 shared 状态。 */
    fun chooseAnotherSource() {
        fromShare = false
        sharedFileName = ""
        source = null
        resetFile()
        step = 0
    }

    private fun detectSharedSource(
        fileName: String,
        headers: List<String>,
        mimeType: String?,
    ): ImportSource {
        val trimmedHeaders = headers.map { it.trim() }
        if (trimmedHeaders.any { it == "总里程" || it == "加油量" || it == "日期时间" }) {
            return ImportSource.BEAR
        }
        if (trimmedHeaders.any { it == "时间" || it == "类型" || it == "金额" }) {
            return ImportSource.QIANJI
        }
        val lowerName = fileName.lowercase()
        val mime = mimeType.orEmpty().lowercase()
        if (lowerName.contains("小熊") || lowerName.endsWith(".xls") || mime.contains("vnd.ms-excel")) {
            return ImportSource.BEAR
        }
        if (lowerName.contains("钱迹") || lowerName.contains("qianji") ||
            lowerName.endsWith(".json") || mime.contains("json") || mime.contains("csv")
        ) {
            return ImportSource.QIANJI
        }
        // 任务约定：无法判断时默认钱迹，界面可切回来源选择。
        return ImportSource.QIANJI
    }

    private fun ensureExtension(name: String, extension: String): String {
        val trimmed = name.trim().ifBlank { "分享导入" }
        val hasExtension = trimmed.substringAfterLast('.', "").isNotBlank()
        return if (hasExtension) trimmed else "$trimmed.$extension"
    }

    private fun looksLikeBackupJson(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        return trimmed.contains("\"version\"") &&
            (trimmed.contains("\"vehicle\"") || trimmed.contains("\"cars\""))
    }

    fun retry() {
        val src = source ?: return
        val file = picked ?: return
        scope.launch {
            busy = true
            busyMessage = "正在重新解析…"
            error = null
            try {
                parsePicked(src, file.name, file.bytes)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ImportWizard", "retry failed", e)
                error = e.message ?: "解析失败，请检查文件格式"
            } finally {
                busy = false
                busyMessage = null
            }
        }
    }

    private suspend fun parsePicked(src: ImportSource, name: String, bytes: ByteArray) {
        busy = true
        busyMessage = "正在解析文件…"
        error = null
        try {
            when (src) {
                ImportSource.BACKUP -> {
                    val text = withContext(Dispatchers.IO) { decodeText(bytes) }
                    val preview = withContext(Dispatchers.Default) { inspectBackup(text) }
                    backupText = text
                    backupPreview = preview
                    picked = PickedFile(name, bytes.size.toLong(), bytes)
                    step = 1
                }

                ImportSource.BEAR, ImportSource.QIANJI -> {
                    val data = withContext(Dispatchers.IO) {
                        readSpreadsheet(bytes, name, allowJsonFallback = src == ImportSource.QIANJI)
                    }
                    setupSheet(data)
                    if (src == ImportSource.QIANJI) applyQianjiHeuristics()
                    excluded = emptySet()
                    fuelBasis = "bear"
                    picked = PickedFile(name, bytes.size.toLong(), bytes)
                    step = 1
                    scheduleTablePreview()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImportWizard", "parsePicked failed", e)
            error = e.message ?: "解析失败，请检查文件格式"
        } finally {
            busy = false
            busyMessage = null
        }
    }

    private fun setupSheet(data: SpreadsheetData) {
        sheetData = data
        sheetNames = data.sheetNames.ifEmpty {
            listOf(data.activeSheet).filter { it.isNotBlank() }
        }
        mapping = detectMap(data.headers)
        defaultCategory = ""
    }

    /**
     * 钱迹中文表头兜底：部分钱迹 Excel 导出的「分类」是账本大类（如「汽车」），
     * 真正的费用分类在「二级分类」；ID 列大写时也默认映射为记录唯一编号。
     * 只是默认映射，用户仍可在「调整字段匹配」里改回。
     */
    private fun applyQianjiHeuristics() {
        val headers = sheetData?.headers ?: return
        val values = mapping.toMutableMap()
        val secondLevel = headers.indexOfFirst { it.trim() == "二级分类" }
        if (secondLevel >= 0) {
            val currentHeader = values["category"]?.let { headers.getOrNull(it)?.trim() }
            if (currentHeader == null || currentHeader == "分类" || currentHeader == "类别" || currentHeader == "一级分类") {
                values["category"] = secondLevel
            }
        }
        if (values["sourceId"] == null) {
            val idIndex = headers.indexOfFirst { it.trim() == "ID" || it.trim() == "Id" || it.trim() == "iD" }
            if (idIndex >= 0) values["sourceId"] = idIndex
        }
        mapping = values
    }

    /** 切换 Excel 工作表（CSV / JSON 忽略）。 */
    fun switchSheet(name: String) {
        val file = picked ?: return
        val src = source ?: return
        val current = sheetData ?: return
        if (name == current.activeSheet) return
        scope.launch {
            busy = true
            busyMessage = "正在读取工作表…"
            error = null
            try {
                val data = withContext(Dispatchers.IO) {
                    readSpreadsheetSheet(
                        bytes = file.bytes,
                        fileName = file.name,
                        sheetName = name,
                        allowJsonFallback = src == ImportSource.QIANJI,
                    )
                }
                setupSheet(data)
                if (src == ImportSource.QIANJI) applyQianjiHeuristics()
                scheduleTablePreview()
            } catch (e: Exception) {
                error = e.message ?: "读取工作表失败"
            } finally {
                busy = false
                busyMessage = null
            }
        }
    }

    // ---------- 费用导入：映射 / 选项 / 预览 ----------

    fun setMapping(field: String, index: Int?) {
        mapping = mapping + (field to index)
        scheduleTablePreview()
    }

    fun changeDefaultCategory(value: String) {
        defaultCategory = value
        scheduleTablePreview()
    }

    fun changeFuelBasis(value: String) {
        if (value in setOf("bear", "qianji", "both")) fuelBasis = value
    }

    fun setExcluded(index: Int, exclude: Boolean) {
        excluded = if (exclude) excluded + index else excluded - index
        tableItems = tableItems.mapIndexed { i, item ->
            if (i == index) item.copy(exclude = exclude) else item
        }
        val snapshot = tableItems
        val base = records
        scope.launch {
            try {
                fuelConflict = withContext(Dispatchers.Default) { fuelBatchConflict(base, snapshot) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单行勾选失败不阻塞预览；确认导入时会再次校验油费冲突。
            }
        }
    }

    private fun effectiveTableItems(): List<ImportItem> = tableItems.mapIndexed { i, item ->
        if (i in excluded) item.copy(exclude = true) else item
    }

    fun scheduleTablePreview() {
        val src = source ?: return
        if (src != ImportSource.BEAR && src != ImportSource.QIANJI) return
        val data = sheetData ?: return
        val generation = ++previewGeneration
        val base = records
        val custom = customCategories
        val currentMapping = mapping
        val currentDefault = defaultCategory
        val currentExcluded = excluded
        scope.launch {
            busy = true
            busyMessage = "正在生成预览…"
            try {
                val normalized = withContext(Dispatchers.Default) {
                    normalizeImport(data.rows, currentMapping, src.key, currentDefault, custom)
                }
                if (generation != previewGeneration) return@launch
                val marked = normalized.mapIndexed { i, item ->
                    if (i in currentExcluded) item.copy(exclude = true) else item
                }
                val duplicateRows = withContext(Dispatchers.Default) {
                    normalized.mapIndexedNotNull { i, item ->
                        // 与 mergeFuelBatch 的判重口径保持一致：跨来源油费在合并时只按同来源判重，
                        // 不应在预览里标成「重复会跳过」，否则「预览重复但实际导入」会误导用户。
                        val scoped = if (item.record.category == "油费" &&
                            item.record.source in setOf("小熊油耗", "钱迹")
                        ) {
                            base.filter { it.source == item.record.source }
                        } else {
                            base
                        }
                        if (duplicateOf(scoped, item.record) != null) i else null
                    }.toSet()
                }
                if (generation != previewGeneration) return@launch
                tableItems = marked
                duplicates = duplicateRows
                fuelConflict = withContext(Dispatchers.Default) { fuelBatchConflict(base, marked) }
            } catch (e: Exception) {
                if (generation == previewGeneration) {
                    error = e.message ?: "预览生成失败"
                }
            } finally {
                if (generation == previewGeneration) {
                    busy = false
                    busyMessage = null
                }
            }
        }
    }

    /** 冷启动分享时账本可能尚未加载完；账本到达后重算一次预览（仅预览步骤）。 */
    fun refreshPreviewFromLedger() {
        if (step != 1) return
        when (source) {
            ImportSource.BEAR, ImportSource.QIANJI -> scheduleTablePreview()
            else -> Unit
        }
    }

    // ---------- 确认 ----------

    fun confirm() {
        if (busy) return
        val src = source ?: return
        val currentLedger = ledger
        scope.launch {
            busy = true
            busyMessage = "正在应用导入…"
            error = null
            try {
                when (src) {
                    ImportSource.BACKUP -> {
                        val text = backupText ?: throw ImportFileException("请先选择备份文件")
                        val preview = backupPreview
                        vm.restoreBackup(text)
                        result = WizardResult(
                            title = "备份已恢复",
                            lines = buildList {
                                if (preview != null) {
                                    add("备份版本：version ${preview.version}")
                                    add("车辆 ${preview.cars} 辆 · 记录 ${preview.records} 条 · 提醒 ${preview.reminders} 条")
                                }
                            },
                            note = "按车辆编号合并：已有记录保留，新增记录追加；旧版单车备份合并到当前车辆。",
                        )
                    }

                    ImportSource.BEAR, ImportSource.QIANJI -> {
                        val items = effectiveTableItems()
                        if (items.none { it.isValid }) {
                            throw ImportFileException("没有可导入的记录，请检查字段匹配")
                        }
                        val merge = withContext(Dispatchers.Default) {
                            mergeFuelBatch(currentLedger?.records.orEmpty(), items, fuelBasis)
                        }
                        vm.applyImport(items, fuelBasis)
                        result = WizardResult(
                            title = "导入已提交",
                            lines = listOf(
                                "新增 ${merge.added} 条",
                                "合并 ${merge.merged} 条",
                                "跳过 ${merge.skipped} 条",
                            ),
                            note = "写入完成后可在「费用记录」查看；钱迹保养记录会按项目自动完成待办。",
                        )
                    }
                }
                step = 2
            } catch (e: Exception) {
                error = e.message ?: "导入失败，请重试"
            } finally {
                busy = false
                busyMessage = null
            }
        }
    }

    // ---------- 供 UI 计算的统计 ----------

    fun validTableCount(): Int = tableItems.count { it.error.isEmpty() && !it.exclude }

    fun invalidTableCount(): Int = tableItems.count { it.error.isNotEmpty() }

    fun excludedTableCount(): Int = tableItems.count { it.error.isEmpty() && it.exclude }

    fun duplicateTableCount(): Int = tableItems.indices.count { i ->
        i in duplicates && tableItems[i].error.isEmpty() && !tableItems[i].exclude
    }

    fun canConfirm(): Boolean {
        if (busy || step != 1) return false
        return when (source) {
            ImportSource.BACKUP -> backupPreview != null
            ImportSource.BEAR, ImportSource.QIANJI -> validTableCount() > 0
            null -> false
        }
    }

    fun confirmLabel(): String = when (source) {
        ImportSource.BACKUP -> "确认恢复"
        else -> "确认导入"
    }
}
