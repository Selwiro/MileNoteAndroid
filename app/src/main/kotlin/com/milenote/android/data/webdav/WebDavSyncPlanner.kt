package com.milenote.android.data.webdav

/**
 * 一次同步的决策结果。
 *
 * - [UPLOAD]：把本地备份上传到远端（远端不存在且本地有改动，或只本地有改动）；
 * - [DOWNLOAD]：用远端覆盖本地（只远端有改动）；
 * - [CONFLICT]：两边都改动，需要用户选择保留哪边；
 * - [NONE]：无需同步。
 */
enum class Plan { UPLOAD, DOWNLOAD, CONFLICT, NONE }

/**
 * 纯函数同步计划器：根据「本地是否改动 / 远端是否改动 / 远端是否存在」决定动作。
 *
 * 判定前提（由调用方 VM 负责计算）：
 * - `localChanged` = 本地备份 SHA-256 ≠ `syncState.lastLocalHash`（无记录视为有改动）；
 * - `remoteChanged` = 远端现状与上次同步不一致：
 *   - 有 ETag：`remoteMeta.etag != syncState.lastEtag`；
 *   - 无 ETag：远端内容 SHA-256 ≠ `syncState.lastRemoteHash`；
 * - `remoteExists` = `remoteMeta.exists`。
 *
 * 规则表：
 * | remoteExists | localChanged | remoteChanged | 结果 |
 * |---|---|---|---|
 * | false | true | - | UPLOAD |
 * | false | false | - | NONE |
 * | true | true | false | UPLOAD |
 * | true | false | true | DOWNLOAD |
 * | true | true | true | CONFLICT |
 * | true | false | false | NONE |
 *
 * 注意：远端不存在时 `remoteChanged` 无意义（例如首次从别的设备删除了文件），统一按上表处理。
 */
object WebDavSyncPlanner {

    fun plan(localChanged: Boolean, remoteChanged: Boolean, remoteExists: Boolean): Plan {
        if (!remoteExists) {
            return if (localChanged) Plan.UPLOAD else Plan.NONE
        }
        return when {
            localChanged && remoteChanged -> Plan.CONFLICT
            localChanged -> Plan.UPLOAD
            remoteChanged -> Plan.DOWNLOAD
            else -> Plan.NONE
        }
    }
}
