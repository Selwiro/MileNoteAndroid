package com.milenote.android.ui.screens.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.SERVICE_CATEGORIES
import com.milenote.android.domain.calc.serviceProjectOptions
import com.milenote.android.domain.calc.splitProjects
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.ocr.FuelReceipt
import com.milenote.android.ui.EditorTarget

/**
 * 「记一笔 / 记录加油 / 编辑记录」弹层的表单状态。
 *
 * 状态对象只在弹层打开期间存在（`remember(ledger.id, target)`），
 * 负责：编辑既有记录时回填、新增自定义分类草稿、构造 [Record]、按 domain 规则校验。
 *
 * 文案与校验规则对齐 Web `recordDialog` 与审计文档 §5.3：
 * 金额 > 0（服务类 / 手填油耗例外）、服务类必须有项目、首保需含「机油机滤」、
 * 按公里循环的提醒必须填总里程、备注 ≤ 1000。
 */
internal class RecordEditorState(
    /** 当前账本快照（用于分类选项、保养项目候选、提醒关联）。 */
    val ledger: Ledger,
    val target: EditorTarget,
) {
    /** 编辑既有记录；null 表示新增。 */
    val original: Record? = target.record

    /** 记录 id（新增时生成，编辑时保留）。 */
    val recordId: String = original?.id ?: Ids.newId()

    /** 历史手填油耗记录（编辑时不显示分类网格，改为油耗 + 行驶里程）。 */
    val isManual: Boolean = original?.kind == "manual"

    // ---- 表单字段 ----
    var amount by mutableStateOf(
        original?.amount?.takeIf { it > 0.0 }?.let { Numbers.jsString(it) } ?: "",
    )
    var date by mutableStateOf(
        original?.date?.takeIf { Dates.isValid(it) } ?: Dates.iso(Dates.today()),
    )
    var category by mutableStateOf(original?.category ?: target.category)
    var odometer by mutableStateOf(numberText(original?.odometer))
    var liters by mutableStateOf(numberText(original?.liters))
    var unitPrice by mutableStateOf(numberText(original?.unitPrice))
    var payable by mutableStateOf(numberText(original?.payable))
    var discount by mutableStateOf(numberText(original?.discount))
    var rate by mutableStateOf(numberText(original?.rate))
    var distance by mutableStateOf(numberText(original?.distance))
    var full by mutableStateOf(original?.full ?: true)
    var missed by mutableStateOf(original?.missed ?: false)
    var note by mutableStateOf(original?.note.orEmpty())
    var location by mutableStateOf(original?.location.orEmpty())
    var projects by mutableStateOf(splitProjects(original?.projects))
    var newProjects by mutableStateOf("")
    var draftCategories by mutableStateOf<List<String>>(emptyList())

    // ---- 弹层 / 校验状态 ----
    var showErrors by mutableStateOf(false)
    var validationError by mutableStateOf<String?>(null)
    var showDatePicker by mutableStateOf(false)
    var showCategoryDialog by mutableStateOf(false)

    /**
     * 日期是否已有用户数据/手改：编辑既有记录视为已填，OCR 不覆盖；
     * 新增记录默认为系统填的今天，允许 OCR 用识别到的支付日期回填。
     */
    var dateTouched by mutableStateOf(original != null)

    /** OCR 回填结果提示（成功回填了哪些字段 / 未识别到可回填字段）。 */
    var ocrNotice by mutableStateOf<String?>(null)

    /** OCR 解析出的核对提示（来自 [FuelReceipt.warnings]）。 */
    var ocrWarnings by mutableStateOf<List<String>>(emptyList())

    /** 完成保养场景关联的提醒（用于「按公里循环必须填总里程」与首保项目校验）。 */
    val serviceReminder: Reminder?
        get() = target.serviceReminderId?.let { id -> ledger.reminders.firstOrNull { it.id == id } }

    /** 已持久化 + 本次新增的自定义分类。 */
    val customCategories: List<String>
        get() = (ledger.customCategories.orEmpty() + draftCategories).distinct()

    /** 分类 chip 选项：9 类 + 自定义 + 记录中出现的分类 + 本次新增。 */
    val categoryOptions: List<String>
        get() = (Categories.options(ledger) + draftCategories).distinct()

    /** 当前是否为油费（手填油耗视为油费，但不显示加油字段）。 */
    val isFuel: Boolean
        get() = !isManual && Categories.normalize(category) == "油费"

    /** 当前是否为服务类（保养 / 维修 / 汽车美容 / 旧维修保养）。 */
    val isService: Boolean
        get() = Categories.normalize(category) in SERVICE_CATEGORIES

    /**
     * 当前是否为保险：保险也支持「项目」多选（交强险/车损险/三者险 + 自定义），
     * 但**不**属于 [SERVICE_CATEGORIES]，因此不触发保养提醒、首保项目、金额 0 等逻辑。
     */
    val isInsurance: Boolean
        get() = Categories.normalize(category) == "保险"

    /** 实际保存的分类：手填油耗固定为油费。 */
    val effectiveCategory: String
        get() = if (isManual) "油费" else Categories.normalize(category)

    /** 弹层标题：编辑 / 记录加油 / 记一笔。 */
    val title: String
        get() = when {
            original != null -> "编辑记录"
            isFuel -> "记录加油"
            else -> "记一笔"
        }

    /**
     * 项目候选（按分类隔离，并合入已选项）。
     *
     * 保险走 `serviceProjectOptions(ledger, "保险")`：默认交强险/车损险/三者险
     * 来自 domain 的定义，自定义项目来自 `ledger.serviceProjectsByCategory["保险"]`。
     */
    val projectOptions: List<String>
        get() = (serviceProjectOptions(ledger, effectiveCategory) + projects).distinct()

    /** 勾选 / 取消某个保养项目。 */
    fun toggleProject(name: String) {
        projects = if (projects.contains(name)) projects - name else projects + name
    }

    /** 把「自定义项目」输入框里的顿号分隔文本追加并勾选。 */
    fun addCustomProjects() {
        val added = splitProjects(newProjects)
        if (added.isEmpty()) return
        projects = (projects + added).distinct()
        newProjects = ""
    }

    /**
     * 新增自定义分类：写入草稿并选中；已存在时直接选中。
     *
     * @return 真正新增的分类名（用于 ViewModel 持久化）；已存在返回 null。
     */
    fun addDraftCategory(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        category = name
        val exists = (ledger.customCategories.orEmpty() + draftCategories).contains(name)
        if (exists) return null
        draftCategories = draftCategories + name
        return name
    }

    /**
     * 用 OCR 结果回填表单（Web 保守规则：只填空字段，不覆盖用户已有/手改内容）。
     *
     * - 金额 / 加油量 / 总里程：仅当对应输入框为空时回填；
     * - 日期：仅在用户未改过日期时回填（编辑既有记录不覆盖原日期）；
     * - [FuelReceipt.warnings] 原样透出给表单顶部提示，由用户核对后保存。
     */
    fun applyOcr(receipt: FuelReceipt) {
        val filled = mutableListOf<String>()
        receipt.amount?.let { value ->
            if (amount.isBlank()) {
                amount = Numbers.jsString(value)
                filled += "金额"
            }
        }
        receipt.liters?.let { value ->
            if (liters.isBlank()) {
                liters = Numbers.jsString(value)
                filled += "加油量"
            }
        }
        receipt.unitPrice?.let { value ->
            if (unitPrice.isBlank()) {
                unitPrice = Numbers.jsString(value)
                filled += "油价"
            }
        }
        receipt.payable?.let { value ->
            if (payable.isBlank()) {
                payable = Numbers.jsString(value)
                filled += "应付金额"
            }
        }
        receipt.discount?.let { value ->
            if (discount.isBlank()) {
                discount = Numbers.jsString(value)
                filled += "优惠金额"
            }
        }
        if (!dateTouched && Dates.isValid(receipt.date)) {
            date = receipt.date
            filled += "日期"
        }
        receipt.odometer?.let { value ->
            if (odometer.isBlank()) {
                odometer = Numbers.jsString(value)
                filled += "总里程"
            }
        }
        ocrWarnings = receipt.warnings
        ocrNotice = if (filled.isEmpty()) {
            "未识别到可回填字段，请手动填写。"
        } else {
            "已识别并填入 ${filled.joinToString("、")}，请核对后保存。"
        }
    }

    /** 构造待保存的 [Record]（不修改原记录）。 */
    fun buildRecord(): Record {
        val base = original ?: Record(id = recordId, kind = if (isManual) "manual" else "expense")
        return base.copy(
            id = original?.id ?: recordId,
            date = date,
            category = effectiveCategory,
            amount = Numbers.parse(amount) ?: 0.0,
            odometer = Numbers.parse(odometer),
            liters = if (isFuel) Numbers.parse(liters) else null,
            unitPrice = if (isFuel) Numbers.parse(unitPrice) else null,
            payable = if (isFuel) Numbers.parse(payable) else null,
            discount = if (isFuel) Numbers.parse(discount) else null,
            rate = if (isManual) Numbers.parse(rate) else null,
            distance = if (isManual) Numbers.parse(distance) else null,
            full = isFuel && full,
            missed = isFuel && missed,
            note = note.trim().take(1000),
            location = if (isService) location.trim().take(200) else null,
            // 保险项目同样保存到 projects（顿号分隔），但不参与服务类校验/提醒逻辑。
            projects = if ((isService || isInsurance) && projects.isNotEmpty()) {
                projects.joinToString("、")
            } else {
                null
            },
            source = original?.source ?: "手动记录",
            kind = if (isManual) "manual" else "expense",
            time = null,
        )
    }

    /**
     * 按 Web 记账顺序做业务校验，返回可直接展示的中文错误；通过返回 null。
     *
     * **注意**: domain `validateRecord` 的约定是「空串 = 通过」，返回类型是 `String` 而非
     * `String?`；这里必须用 `ifBlank { null }` 归一化，否则空串会被当成「有错误」，
     * 导致保存按钮永远被拦下、顶部出现空内容错误条。
     */
    fun validate(): String? {
        val reminder = serviceReminder
        if (reminder?.firstOnly == true && !projects.contains("机油机滤")) {
            return "首保项目需包含机油机滤"
        }
        if (isService && projects.isEmpty()) return "请填写维修保养项目"
        if (reminder != null && (reminder.intervalKm ?: 0.0) > 0.0 && Numbers.parse(odometer) == null) {
            return "该提醒按公里数循环，请填写总里程"
        }
        return validateRecord(buildRecord(), customCategories).ifBlank { null }
    }

    /** 点「保存」时调用：打开错误提示并尝试构造记录；失败返回 null。 */
    fun attemptSave(): Record? {
        showErrors = true
        val error = validate() // 已归一化：通过时为 null，不会是空串
        validationError = error
        return if (error.isNullOrEmpty()) buildRecord() else null
    }

    /**
     * 当前应展示的校验错误（首次点保存后才计算）。
     *
     * 实时重算而不是缓存首次错误，用户修正字段后提示会立即消失。
     */
    fun currentError(): String? = if (showErrors) validate() else null

    /** 金额字段的局部错误（就地显示在金额下方）。 */
    fun amountError(): String? = currentError()
        ?.takeIf { it.contains("金额") || it.contains("日期") }
}

/** 数值字段回填：整数不带 `.0`，小数去掉尾随 0（与 Web `String(number)` 一致）。 */
private fun numberText(value: Double?): String =
    if (value == null || !value.isFinite()) "" else Numbers.jsString(value)
