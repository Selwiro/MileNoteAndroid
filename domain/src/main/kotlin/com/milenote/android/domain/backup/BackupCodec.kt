package com.milenote.android.domain.backup

import com.milenote.android.domain.i18n.tr
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.Ledger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.SerializationException

/** 备份文件解出的两类结构：v2 多车车库，或 v1 单车账本。 */
sealed interface BackupPayload {
    data class MulticarGarage(val garage: Garage) : BackupPayload
    data class LegacyLedger(val ledger: Ledger) : BackupPayload
}

/**
 * Web 备份 JSON 编解码（`garage version: 2`）。
 *
 * - 输出与 Web `JSON.stringify(garage)` 字段集合一致：宽松未知字段、输出默认值、
 *   省略 null（Web 读取缺省属性语义相同）；
 * - 读取支持 v1 单车备份（version 1，顶层为账本）与 v2 多车备份；
 * - 读取后执行 [GarageOps.validateLedger] / [GarageOps.normalizeGarage]，
 *   非法记录 / 重复 id / 非法提醒 / 里程非法抛 [BackupException]。
 */
@OptIn(ExperimentalSerializationApi::class)
object BackupCodec {

    /** 与 Web 备份互导的 JSON 配置。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    /** 编码为 compact JSON（可直接写入文件 / WebDAV）。 */
    fun encode(garage: Garage): String = try {
        json.encodeToString(Garage.serializer(), garage)
    } catch (e: SerializationException) {
        throw BackupException(tr("备份编码失败", "Failed to encode the backup"), e)
    }

    /**
     * 解码并校验备份；v1 单车备份会迁移成 v2 单车站（新车辆 id）。
     *
     * 注意：恢复流程若要把 v1 备份合并进当前车，请用 [restore] / [decodePayload]。
     */
    fun decode(text: String): Garage = when (val payload = decodePayload(text)) {
        is BackupPayload.MulticarGarage -> GarageOps.normalizeGarage(payload.garage)
        is BackupPayload.LegacyLedger -> GarageOps.migrateLegacy(payload.ledger)
    }

    /** 解码但不迁移 v1：返回原始结构，供恢复流程区分 v1 / v2。 */
    fun decodePayload(text: String): BackupPayload {
        val root = parseObject(text)
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return when (version) {
            1 -> {
                val ledger = decodeAs<Ledger>(text, Ledger.serializer())
                BackupPayload.LegacyLedger(ledger)
            }
            2 -> {
                val garage = decodeAs<Garage>(text, Garage.serializer())
                BackupPayload.MulticarGarage(garage)
            }
            else -> throw BackupException(tr("不是有效的账本备份（缺少版本号）", "Not a valid ledger backup (missing version)"))
        }
    }

    /**
     * 恢复备份（Web 恢复流程）：
     * v1 → 合并进当前车辆；v2 → 按车辆 id 合并，已有记录保留、新增追加。
     */
    fun restore(current: Garage, text: String): Garage = when (val payload = decodePayload(text)) {
        is BackupPayload.LegacyLedger -> GarageOps.mergeLegacyBackup(current, payload.ledger)
        is BackupPayload.MulticarGarage -> GarageOps.mergeGarage(current, GarageOps.normalizeGarage(payload.garage))
    }

    private fun parseObject(text: String): JsonObject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw BackupException(tr("备份文件不是有效的 JSON", "Backup file is not valid JSON"), e)
        }
        return element as? JsonObject ?: throw BackupException(tr("不是有效的账本备份", "Not a valid ledger backup"))
    }

    private fun <T> decodeAs(text: String, serializer: kotlinx.serialization.KSerializer<T>): T = try {
        json.decodeFromString(serializer, text)
    } catch (e: BackupException) {
        throw e
    } catch (e: Exception) {
        throw BackupException(tr("账本格式无效", "Invalid ledger format"), e)
    }
}
