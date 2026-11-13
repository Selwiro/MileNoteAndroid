package com.milenote.android.ui.screens.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.CategoryConfig
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.fillEnergyAmounts
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.Numbers
import com.milenote.android.domain.calc.SERVICE_CATEGORIES
import com.milenote.android.domain.calc.serviceProjectOptions
import com.milenote.android.domain.calc.splitProjects
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.model.EnergyProfile
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.domain.model.hasCharging
import com.milenote.android.domain.model.hasFuel
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.i18n.DataLabels
import com.milenote.android.domain.ocr.FuelReceipt
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.ui.EditorTarget

/** 燃油标号选项（车辆设置与油费记录共用同一口径）。 */
internal val FuelGradeOptions: List<String> = listOf("92#", "95#", "98#", "0#")

/**
 * 「记一笔 / 记录加油 / 编辑记录」弹层的表单状态。
 *
 * 状态对象只在弹层打开期间存在（`remember(ledger.id, target)`），
 * 负责：编辑既有记录时回填、新增自定义分类草稿、构造 [Record]、按 domain 规则校验。
 *
 * 文案与校验规则对齐 Web `recordDialog` 与审计文档 §5.3：
 * 金额 > 0（服务类 / 手填油耗例外）、服务类必须有项目、首保需含「机油机滤」、
 * 按里程循环的提醒必须填总里程、备注 ≤ 1000。
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

    /** 当前车辆的「能量档位」：燃油 / 插混双流 / 纯电（决定标题、能量字段与分类显示名）。 */
    val energyProfile: EnergyProfile = ledger.vehicle.energyProfile

    /** 当前车辆是否纯电（保留给纯电专属判断用）。 */
    val isEv: Boolean = ledger.vehicle.isEv

    /** 有油箱 / 有充电口（插混两者都有）。 */
    val hasFuel: Boolean = ledger.vehicle.hasFuel
    val hasCharging: Boolean = ledger.vehicle.hasCharging

    /**
     * 插混：本条记录录的是「充电」（由燃油标号下拉的「充电」选项切换）。
     * 编辑既有充电记录（`kwh != null`）时默认选中充电，避免保存时被改写成加油记录。
     */
    var charging by mutableStateOf(original?.kwh != null)

    /** 本条记录的能量种类是否是充电：纯电恒 true，燃油恒 false，插混看下拉。 */
    val electricView: Boolean
        get() = when (energyProfile) {
            EnergyProfile.ELECTRIC_ONLY -> true
            EnergyProfile.DUAL -> charging
            EnergyProfile.FUEL_ONLY -> false
        }

    /** 燃油标号下拉是否提供「充电」选项（仅插混）。 */
    val chargeOption: Boolean
        get() = energyProfile == EnergyProfile.DUAL

    /**
     * 插混切换「加油 / 充电」：两种量纲的能源量与单价不能沿用，清空后由用户重填；
     * 金额 / 应付 / 优惠 保留，自动补全标记一并清掉。
     */
    fun switchChargingMode(charging: Boolean) {
        if (!chargeOption || this.charging == charging) return
        this.charging = charging
        liters = ""
        kwh = ""
        unitPrice = ""
        autoFuelFields.clear()
        autoFillFuel()
    }

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
    var kwh by mutableStateOf(numberText(original?.kwh))
    var unitPrice by mutableStateOf(numberText(original?.unitPrice))
    var fuelGrade by mutableStateOf(
        original?.fuelGrade?.takeIf { it in FuelGradeOptions }
            ?: ledger.vehicle.fuelGrade?.takeIf { it in FuelGradeOptions }
            ?: "92#",
    )
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

    /** 上一次由自动计算填出来的油费字段（用户手改后移除）。 */
    private val autoFuelFields = mutableSetOf<String>()

    /**
     * 表单是否有未保存的改动（返回时据此确认是否丢弃）。
     *
     * 编辑既有记录时与回填值比较；新增记录与初始默认值比较（日期默认今天，
     * 改过日期、金额、分类、备注等任一字段即视为有改动）。
     */
    val isDirty: Boolean
        get() = snapshot() != initialSnapshot

    private val initialSnapshot: String = snapshot()

    private fun snapshot(): String = listOf(
        amount,
        date,
        category,
        odometer,
        liters,
        kwh,
        unitPrice,
        fuelGrade,
        payable,
        discount,
        rate,
        distance,
        full.toString(),
        missed.toString(),
        note,
        location,
        projects.joinToString("\u3001"),
        newProjects,
        charging.toString(),
        draftCategories.joinToString("\u3001"),
    ).joinToString("|")

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

    /**
     * 分类 chip 候选：`CategoryConfig.pickOptions`（九类 + 自定义 + 历史分类，减去设置页里
     * 被隐藏的分类；受保护分类强制保留）+ 本次新增草稿。
     *
     * 隐藏只影响「新建」的候选：编辑既有记录时，它的原分类即使已被隐藏也会保留在候选里并选中，
     * 保存时按原值写回，不会被改写。
     */
    val categoryOptions: List<String>
        get() {
            val keep = listOf(
                Categories.normalize(original?.category),
                Categories.normalize(target.category),
            ).filter { it.isNotEmpty() }
            return (CategoryConfig.pickOptions(ledger) + keep + draftCategories).distinct()
        }

    /** 当前是否为能源费（燃油「油费」/ 纯电显示「电费」；手填油耗视为油费，但不显示能量字段）。 */
    val isFuel: Boolean
        get() = !isManual && Categories.isEnergy(category)

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

    /** 弹层标题：编辑 / 记录加油（纯电为记录充电）/ 记一笔。 */
    val title: String
        get() = when {
            original != null -> tr("编辑记录", "Edit record")
            isFuel -> if (electricView) tr("记录充电", "Log charging") else tr("记录加油", "Log refuel")
            else -> tr("记一笔", "Add entry")
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
                filled += tr("金额", "amount")
            }
        }
        receipt.liters?.let { value ->
            // 充电记录不写升数（插混切到「充电」时 OCR 也不会出现，双保险）
            if (!electricView && liters.isBlank()) {
                liters = Numbers.jsString(value)
                filled += tr("加油量", "fuel amount")
            }
        }
        receipt.unitPrice?.let { value ->
            if (unitPrice.isBlank()) {
                unitPrice = Numbers.jsString(value)
                filled += tr("油价", "unit price")
            }
        }
        receipt.payable?.let { value ->
            if (payable.isBlank()) {
                payable = Numbers.jsString(value)
                filled += tr("应付金额", "amount due")
            }
        }
        receipt.discount?.let { value ->
            if (discount.isBlank()) {
                discount = Numbers.jsString(value)
                filled += tr("优惠金额", "discount")
            }
        }
        if (!dateTouched && Dates.isValid(receipt.date)) {
            date = receipt.date
            filled += tr("日期", "date")
        }
        receipt.odometer?.let { value ->
            if (odometer.isBlank()) {
                odometer = Numbers.jsString(value)
                filled += tr("总里程", "odometer")
            }
        }
        if (isFuel) autoFillFuel()
        ocrWarnings = receipt.warnings
        ocrNotice = if (filled.isEmpty()) {
            tr("未识别到可回填字段，请手动填写。", "No fillable fields were detected. Enter them manually.")
        } else {
            tr(
                "已识别并填入 %s，请核对后保存。",
                "Filled in %s — please check and save.",
                filled.joinToString(", "),
            )
        }
    }

    /** 用户手动改了某个能源字段：取消它的自动填充标记并重算空白项。 */
    fun onFuelFieldChanged(field: String) {
        if (!isFuel) return
        autoFuelFields.remove(field)
        // 正在编辑实付金额时不要反向回填它，避免和用户输入抢值。
        autoFillFuel(includeAmount = field != "amount")
    }

    /**
     * 能源字段联动：能量量 × 单价 = 应付金额，应付金额 - 优惠金额 = 实付金额。
     * 燃油用「加油量 × 油价」，纯电用「充电量 × 电价」；能算出来的字段都补
     * （实付 / 应付 / 优惠 / 单价 / 能源量），只补空字段或上一次自动填出来的字段。
     *
     * @param includeAmount 是否允许回填「实付金额」（用户正在编辑实付时不回填，避免抢输入）。
     */
    private fun autoFillFuel(includeAmount: Boolean = true) {
        if (!isFuel) return
        // 自动填过的字段先置空再重算，避免输入变化后沿用旧的派生值。
        // 上一次自动补出来的能源量/金额都不当作「用户填写」，否则会出现 48 度 × 0.64 之类的自洽错误。
        val energyField = if (electricView) "kwh" else "liters"
        val energyValue = Numbers.parse(if (electricView) kwh else liters)
            .takeUnless { energyField in autoFuelFields }
        val seed = Record(
            category = "油费",
            // 上一次自动补出来的实付金额不当作「用户填写」，否则会把应付-实付算成优惠。
            amount = (Numbers.parse(amount).takeUnless { "amount" in autoFuelFields }) ?: 0.0,
            liters = if (electricView) null else energyValue,
            kwh = if (electricView) energyValue else null,
            unitPrice = Numbers.parse(unitPrice).takeUnless { "unitPrice" in autoFuelFields },
            payable = Numbers.parse(payable).takeUnless { "payable" in autoFuelFields },
            discount = Numbers.parse(discount).takeUnless { "discount" in autoFuelFields },
        )
        val filled = fillEnergyAmounts(seed, electricView)
        if (electricView) {
            applyAuto("kwh", kwh, filled.kwh) { kwh = it }
        } else {
            applyAuto("liters", liters, filled.liters) { liters = it }
        }
        applyAuto("unitPrice", unitPrice, filled.unitPrice) { unitPrice = it }
        applyAuto("payable", payable, filled.payable) { payable = it }
        applyAuto("discount", discount, filled.discount) { discount = it }
        if (includeAmount && filled.amount > 0.0) {
            applyAuto("amount", amount, filled.amount) { amount = it }
        }
    }

    private fun applyAuto(field: String, current: String, next: Double?, set: (String) -> Unit) {
        if (next == null) return
        val text = Numbers.jsString(next)
        if (text == current) return
        if (current.isBlank() || field in autoFuelFields) {
            set(text)
            autoFuelFields.add(field)
        }
    }

    /** 构造待保存的 [Record]（不修改原记录）。 */
    fun buildRecord(): Record {
        val base = original ?: Record(id = recordId, kind = if (isManual) "manual" else "expense")
        val record = base.copy(
            id = original?.id ?: recordId,
            date = date,
            category = effectiveCategory,
            amount = Numbers.parse(amount) ?: 0.0,
            odometer = Numbers.parse(odometer),
            liters = if (isFuel && !electricView) Numbers.parse(liters) else null,
            kwh = if (isFuel && electricView) Numbers.parse(kwh) else null,
            unitPrice = if (isFuel) Numbers.parse(unitPrice) else null,
            fuelGrade = if (isFuel && !electricView) fuelGrade.takeIf { it in FuelGradeOptions } else null,
            payable = if (isFuel) Numbers.parse(payable) else null,
            discount = if (isFuel) Numbers.parse(discount) else null,
            rate = if (isManual) Numbers.parse(rate) else null,
            distance = if (isManual) Numbers.parse(distance) else null,
            full = isFuel && full,
            missed = isFuel && missed,
            note = note.trim().take(1000),
            // 服务类=门店；油费/电费=加油站/充电站（用于按站点比价与汇总）；其余分类不写。
            location = when {
                isService -> location.trim().take(200)
                isFuel -> location.trim().take(200).takeIf { it.isNotEmpty() }
                else -> null
            },
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
        return fillEnergyAmounts(record, electricView)
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
        if (reminder?.firstOnly == true) {
            // 首保完成判定项目由方案决定：燃油=机油机滤，纯电=三电诊断（不再硬编码）。
            val required = splitProjects(reminder.projects)
            val missing = required.filterNot { projects.contains(it) }
            if (missing.isNotEmpty()) {
                return tr(
                    "首保项目需包含：%s",
                    "The first service must include: %s",
                    missing.joinToString("、") { DataLabels.projectFor(ledger, it) },
                )
            }
        }
        if (isService && projects.isEmpty()) return tr("请填写维修保养项目", "Add at least one service item")
        if (reminder != null && (reminder.intervalKm ?: 0.0) > 0.0 && Numbers.parse(odometer) == null) {
            return tr(
                "该提醒按%s数循环，请填写总里程",
                "This reminder repeats by mileage (%s) — enter the odometer",
                Units.distanceUnit,
            )
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
        ?.takeIf {
            val lower = it.lowercase()
            it.contains("金额") || it.contains("日期") ||
                lower.contains("amount") || lower.contains("date")
        }
}

/** 数值字段回填：整数不带 `.0`，小数去掉尾随 0（与 Web `String(number)` 一致）。 */
private fun numberText(value: Double?): String =
    if (value == null || !value.isFinite()) "" else Numbers.jsString(value)
