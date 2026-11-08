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
        if (ledger.version != 1) throw BackupException(tr("账本格式无效", "Invalid ledger format"))
        val custom = ledger.customCategories.orEmpty()
        for (r in ledger.records) {
            if (r.id.isEmpty()) throw BackupException(tr("账本包含无效费用记录", "The ledger contains an invalid expense record"))
            if (validateRecord(r, custom).isNotEmpty()) throw BackupException(tr("账本包含无效费用记录", "The ledger contains an invalid expense record"))
        }
        if (ledger.records.map { it.id }.toSet().size != ledger.records.size) {
            throw BackupException(tr("费用记录编号重复", "Duplicate expense record ID"))
        }
        for (r in ledger.reminders) {
            if (r.id.isEmpty() || r.title.isEmpty()) throw BackupException(tr("账本包含无效提醒", "The ledger contains an invalid reminder"))
            if (!Dates.isValid(r.dueDate) && r.dueKm == null) throw BackupException(tr("账本包含无效提醒", "The ledger contains an invalid reminder"))
            val dueKm = r.dueKm
            if (dueKm != null && (!dueKm.isFinite() || dueKm < 0)) throw BackupException(tr("账本包含无效提醒", "The ledger contains an invalid reminder"))
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
        if (input.version != 2 || input.cars.isEmpty()) throw BackupException(tr("不是有效的多车账本", "Not a valid multi-vehicle ledger"))
        val ids = mutableSetOf<String>()
        for (car in input.cars) {
            validateLedger(car)
            if (car.id.isEmpty() || !ids.add(car.id)) throw BackupException(tr("车辆编号无效或重复", "Invalid or duplicate vehicle ID"))
        }
        val cars = input.cars.map { car ->
            car.copy(records = car.records.map { it.copy(category = Categories.normalize(it.category)) })
        }
        val activeId = if (ids.contains(input.activeId)) input.activeId else cars.first().id
        return input.copy(activeId = activeId, cars = cars)
    }

    /** 旧版 v1 单车账本迁移为 v2 单车站（生成新车辆 id，分类归一）。 */
    fun migrateLegacy(ledger: Ledger): Garage {
        val normalized = ledger.copy(
            version = 1,
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
        n = n.copy(
            servicePlanConfigured = existing.servicePlanConfigured == true ||
                incoming.servicePlanConfigured == true,
        )
        if (existing.records.isEmpty() && existing.reminders.isEmpty()) {
            n = n.copy(vehicle = incoming.vehicle)
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

    /** 恢复 v1 单车备份：只合并到当前车辆（Web `mergeGarage` 的 version===1 分支）。 */
    fun mergeLegacyBackup(current: Garage, legacy: Ledger): Garage {
        validateLedger(legacy)
        val active = current.activeCar ?: throw BackupException(tr("不是有效的多车账本", "Not a valid multi-vehicle ledger"))
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
