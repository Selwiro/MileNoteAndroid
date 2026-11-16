package com.milenote.android.domain.backup

import com.milenote.android.domain.calc.Categories
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.Ids
import com.milenote.android.domain.calc.duplicateOf
import com.milenote.android.domain.calc.splitProjects
import com.milenote.android.domain.calc.validateRecord
import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.PlanBase
import com.milenote.android.domain.model.normalizePowertrain

/**
 * 车库级校验、合并与删除（移植 Web `garage.mjs`）。
 *
 * 所有函数都是纯函数：不修改入参，返回新对象；失败抛 [BackupException]，
 * message 与 Web 端提示文案一致。
 */
object GarageOps {

    /** 图片地址白名单校验（Web `safeImage`）：仅图片 dataURL 或安全 http(s) URL。 */
    fun safeImage(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return ""
        if (DATA_IMAGE.matches(v)) {
            if (v.length > 1_500_000) throw BackupException(tr("图片过大，请重新上传压缩后的图片", "Image too large — upload a compressed image"))
            return v
        }
        if (v.length > 2048) throw BackupException(tr("图片 URL 过长", "Image URL is too long"))
        val uri = runCatching { java.net.URI(v) }
            .getOrElse { throw BackupException(tr("请输入完整的 http 或 https 图片 URL", "Enter a full http or https image URL")) }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || !uri.userInfo.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw BackupException(tr("仅支持不含账号密码的 http / https 图片 URL", "Only http/https image URLs without credentials are supported"))
        }
        return normalizeHttpUrl(uri)
    }

    /**
     * 账本业务校验（Web `validateLedger`）：
     * 版本、记录（id/合法性/编号唯一）、提醒（id/标题/到期/里程）、车辆里程、图片。
     */
    fun validateLedger(ledger: Ledger): Ledger {
        if (ledger.version != 1) throw BackupException(tr("数据格式无效", "Invalid data format"))
        val custom = ledger.customCategories.orEmpty()
        for (r in ledger.records) {
            if (r.id.isEmpty()) throw BackupException(tr("备份包含无效费用记录", "The data contains an invalid expense record"))
            // T007：空分类只在备份 / 同步解码时放行（历史脏数据），统计层归入「未分类」；
            // 编辑器与导入向导仍要求选分类。
            if (validateRecord(r, custom, allowBlankCategory = true).isNotEmpty()) {
                throw BackupException(tr("备份包含无效费用记录", "The data contains an invalid expense record"))
            }
        }
        if (ledger.records.map { it.id }.toSet().size != ledger.records.size) {
            throw BackupException(tr("费用记录编号重复", "Duplicate expense record ID"))
        }
        for (r in ledger.reminders) {
            if (r.id.isEmpty() || r.title.isEmpty()) throw BackupException(tr("备份包含无效提醒", "The data contains an invalid reminder"))
            if (!Dates.isValid(r.dueDate) && r.dueKm == null) throw BackupException(tr("备份包含无效提醒", "The data contains an invalid reminder"))
            val dueKm = r.dueKm
            if (dueKm != null && (!dueKm.isFinite() || dueKm < 0)) throw BackupException(tr("备份包含无效提醒", "The data contains an invalid reminder"))
        }
        val currentKm = ledger.vehicle.currentKm
        if (!currentKm.isFinite() || currentKm < 0) throw BackupException(tr("车辆里程无效", "Invalid vehicle mileage"))
        val startKm = ledger.vehicle.startKm
        if (startKm != null && (!startKm.isFinite() || startKm < 0)) throw BackupException(tr("车辆里程无效", "Invalid vehicle mileage"))
        safeImage(ledger.vehicle.image)
        return ledger
    }

    /**
     * 规范化车库（Web `normalizeGarage` 的 v2 / 空数据分支）。
     *
     * @param input null 表示首次启动，生成空白单车站
     */
    fun normalizeGarage(input: Garage?): Garage {
        if (input == null) {
            val car = com.milenote.android.domain.model.blankLedger().copy(id = Ids.newId())
            return Garage(version = 2, activeId = car.id, theme = "blue", cars = listOf(car))
        }
        if (input.version != 2 || input.cars.isEmpty()) throw BackupException(tr("不是有效的多车备份", "Not a valid multi-vehicle data"))
        val ids = mutableSetOf<String>()
        for (car in input.cars) {
            validateLedger(car)
            if (car.id.isEmpty() || !ids.add(car.id)) throw BackupException(tr("车辆编号无效或重复", "Invalid or duplicate vehicle ID"))
        }
        val cars = input.cars.map { car ->
            car.copy(
                // 动力类型只认 "fuel"/"phev"/"ev"，未知值归一为 null（语义=燃油），避免坏数据把车变成怪状态。
                vehicle = car.vehicle.copy(powertrain = normalizePowertrain(car.vehicle.powertrain)),
                records = car.records.map { it.copy(category = Categories.normalize(it.category)) },
            )
        }
        val activeId = if (ids.contains(input.activeId)) input.activeId else cars.first().id
        return input.copy(activeId = activeId, cars = cars)
    }

    /** 旧版 v1 单车账本迁移为 v2 单车站（生成新车辆 id，分类与动力类型归一）。 */
    fun migrateLegacy(ledger: Ledger): Garage {
        val normalized = ledger.copy(
            version = 1,
            vehicle = ledger.vehicle.copy(powertrain = normalizePowertrain(ledger.vehicle.powertrain)),
            records = ledger.records.map { it.copy(category = Categories.normalize(it.category)) },
        )
        val validated = validateLedger(normalized)
        val car = validated.copy(id = Ids.newId())
        return Garage(version = 2, activeId = car.id, theme = "blue", cars = listOf(car))
    }

    /** 当前车保存（Web `replaceActive`）：只替换 activeId 对应的账本，保留其 id。 */
    fun replaceActive(garage: Garage, ledger: Ledger): Garage = garage.copy(
        cars = garage.cars.map { car -> if (car.id == garage.activeId) ledger.copy(id = car.id) else car },
    )

    /**
     * 合并两辆车的账本（Web `mergeLedger`，只新增不修改）：
     * 自定义分类取并集；记录按 id + 业务判重追加（旧分类归一）；
     * 提醒只按 id 去重；方案仅当前为空时采纳；项目池并集；空车整体采纳车辆信息。
     */
    fun mergeLedger(existing: Ledger, incoming: Ledger): Ledger {
        var n = existing.copy(
            customCategories = (existing.customCategories.orEmpty() + incoming.customCategories.orEmpty()).distinct(),
        )

        val records = n.records.toMutableList()
        val ids = records.map { it.id }.toMutableSet()
        for (r in incoming.records) {
            if (!ids.contains(r.id) && duplicateOf(records, r) == null) {
                records.add(r.copy(category = Categories.normalize(r.category)))
                ids.add(r.id)
            }
        }
        n = n.copy(records = records.toList())

        val reminders = n.reminders.toMutableList()
        val reminderIds = reminders.map { it.id }.toMutableSet()
        for (r in incoming.reminders) {
            if (!reminderIds.contains(r.id)) {
                reminders.add(r)
                reminderIds.add(r.id)
            }
        }
        n = n.copy(reminders = reminders.toList())

        if (n.servicePlan == null && incoming.servicePlan != null) {
            n = n.copy(
                servicePlan = incoming.servicePlan,
                servicePlanBase = incoming.servicePlanBase ?: PlanBase(),
            )
        }
        n = n.copy(
            serviceProjects = splitProjects(
                existing.serviceProjects.orEmpty() + incoming.serviceProjects.orEmpty(),
            ),
        )
        val byCategory = LinkedHashMap<String, List<String>>(n.serviceProjectsByCategory.orEmpty())
        for ((category, projects) in incoming.serviceProjectsByCategory.orEmpty()) {
            byCategory[category] = splitProjects(byCategory[category].orEmpty() + projects)
        }
        n = n.copy(serviceProjectsByCategory = byCategory)
        // 分类/项目库配置：隐藏集合取并集（任一侧隐藏都保留隐藏），显示名以「本机已有」优先。
        n = n.copy(
            hiddenCategories = (n.hiddenCategories.orEmpty() + incoming.hiddenCategories.orEmpty())
                .map { Categories.normalize(it) }
                .filter { it.isNotEmpty() }
                .distinct(),
            categoryLabels = incoming.categoryLabels.orEmpty() + n.categoryLabels.orEmpty(),
        )
        val hiddenProjects = LinkedHashMap<String, List<String>>(n.hiddenServiceProjects.orEmpty())
        for ((category, projects) in incoming.hiddenServiceProjects.orEmpty()) {
            hiddenProjects[category] = (hiddenProjects[category].orEmpty() + projects).distinct()
        }
        n = n.copy(
            hiddenServiceProjects = hiddenProjects,
            serviceProjectLabels = incoming.serviceProjectLabels.orEmpty() + n.serviceProjectLabels.orEmpty(),
        )
        n = n.copy(
            servicePlanConfigured = existing.servicePlanConfigured == true ||
                incoming.servicePlanConfigured == true,
        )
        // 车辆身份以「有记录的一侧」为准：避免把已有燃油车恢复成纯电后历史单位串掉；
        // 空车整体采纳 incoming（含动力类型）。
        n = if (existing.records.isEmpty() && existing.reminders.isEmpty()) {
            n.copy(vehicle = incoming.vehicle.copy(powertrain = normalizePowertrain(incoming.vehicle.powertrain)))
        } else {
            n.copy(vehicle = n.vehicle.copy(powertrain = normalizePowertrain(n.vehicle.powertrain)))
        }
        return n
    }

    /**
     * 合并 v2 车库（Web `mergeGarage`）：按车辆 id 匹配，不存在整辆追加，
     * 存在则 [mergeLedger]；当前 activeId / theme 保持不变。
     */
    fun mergeGarage(current: Garage, incoming: Garage): Garage {
        val source = normalizeGarage(incoming)
        var result = current
        for (car in source.cars) {
            val index = result.cars.indexOfFirst { it.id == car.id }
            result = if (index < 0) {
                result.copy(cars = result.cars + car)
            } else {
                result.copy(
                    cars = result.cars.toMutableList().also { it[index] = mergeLedger(it[index], car) },
                )
            }
        }
        return result
    }

    /**
     * 恢复备份后自动选中的车辆（UX3）。
     *
     * 新装 App 恢复备份时，`mergeGarage` 按 Web 口径保持 `activeId` 不变，
     * 于是当前车还是那辆刚建的空白「我的车」——首页空空、报告全 0，用户以为恢复失败。
     * 这里在恢复后把当前车换成「第一辆有内容的车」（有记录或提醒）：
     * - 当前车本来就有内容 → 不动（用户主动选过的车要尊重）；
     * - 当前车是空车 → 换成第一辆有内容的车；一辆都没有 → 保持原样。
     */
    fun activeCarWithRecords(garage: Garage): Garage {
        val active = garage.activeCar
        if (active != null && active.hasContent()) return garage
        val candidate = garage.cars.firstOrNull { it.hasContent() } ?: return garage
        if (candidate.id == garage.activeId) return garage
        return garage.copy(activeId = candidate.id)
    }

    /** 这辆车是否有内容（有记录或提醒）：用于恢复后自动选车，空车不抢焦点。 */
    private fun Ledger.hasContent(): Boolean = records.isNotEmpty() || reminders.isNotEmpty()

    /**
     * 把「本次恢复备份新加回本机的整车」标记为 `restoredAt = at`，并清掉它们的删除墓碑。
     *
     * 背景：A 设备删了车 X 并同步；B 设备也从同步里删掉了 X（本地留下车辆墓碑），之后用户
     * 用旧备份恢复，X 被加回来。下一次同步时 A（或 B 自己）的墓碑会比 X 里所有记录都新，
     * 按「删除 vs 修改」又把车删一次——恢复等于白做。
     *
     * 恢复是显式动作，应算一次修改：这里只标记**相对 [previous] 新增的**车辆，不动其它车。
     * 已经在本机存在的车不会被标记，避免把普通合并变成「全量覆盖远端」。
     *
     * @param previous 恢复前的车库（用来判断哪些车是这次新加回来的）
     * @param at 恢复时刻（epoch 毫秒）
     */
    fun markRestoredCars(garage: Garage, previous: Garage, at: Long): Garage {
        val existed = previous.cars.mapTo(HashSet()) { it.id }
        val restored = garage.cars.filter { it.id.isNotEmpty() && it.id !in existed }.mapTo(HashSet()) { it.id }
        if (restored.isEmpty()) return garage
        return garage.copy(
            cars = garage.cars.map { car ->
                if (car.id in restored) car.copy(restoredAt = at) else car
            },
            // 本机若还留着这辆车的墓碑（之前同步过来的删除），一并清掉：车已经回来了。
            vehicleTombstones = garage.vehicleTombstones.orEmpty()
                .filterNot { it.id in restored }
                .takeIf { it.isNotEmpty() },
        )
    }

    /** 恢复 v1 单车备份：只合并到当前车辆（Web `mergeGarage` 的 version===1 分支）。 */
    fun mergeLegacyBackup(current: Garage, legacy: Ledger): Garage {
        validateLedger(legacy)
        val active = current.activeCar ?: throw BackupException(tr("不是有效的多车备份", "Not a valid multi-vehicle data"))
        return replaceActive(current, mergeLedger(active, legacy))
    }

    /**
     * 删除车库数据（Web `deleteGarageData`）。
     *
     * `scope=one` 删除指定车；`scope=all` 删除全部；删空后创建空白车。
     */
    fun deleteGarageData(garage: Garage, scope: String, carId: String? = null): Garage {
        if (scope != "one" && scope != "all") throw BackupException(tr("请选择删除范围", "Choose a delete scope"))
        if (scope == "one" && garage.cars.none { it.id == carId }) throw BackupException(tr("请选择有效车辆", "Choose a valid vehicle"))
        var next = garage.copy(
            cars = if (scope == "all") emptyList() else garage.cars.filter { it.id != carId },
        )
        if (next.cars.isEmpty()) {
            val fresh = normalizeGarage(null)
            next = next.copy(cars = fresh.cars, activeId = fresh.activeId)
        } else if (next.cars.none { it.id == next.activeId }) {
            next = next.copy(activeId = next.cars.first().id)
        }
        return next
    }

    private val DATA_IMAGE = Regex("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$")

    private fun normalizeHttpUrl(uri: java.net.URI): String {
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$host$port$path$query$fragment"
    }
}
