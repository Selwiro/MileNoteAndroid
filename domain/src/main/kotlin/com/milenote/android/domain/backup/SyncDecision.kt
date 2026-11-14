package com.milenote.android.domain.backup

import com.milenote.android.domain.model.Garage

/**
 * 一次同步该做什么（纯决策，不碰网络、不写文件）。
 *
 * 由 app 层 `WebDavAutoSyncEngine` 在拿到「本机账本 + 远端账本 + 基线 + 两边是否改动」后调用，
 * 再把结果翻译成 GET/PUT 与界面文案。
 */
sealed interface SyncPlan {

    /** 两边都没有改动。 */
    data object UpToDate : SyncPlan

    /** 只有本机改动（或远端没有文件）：上传本机账本。 */
    data class Upload(val garage: Garage) : SyncPlan

    /** 只有远端改动：用远端覆盖本机。 */
    data class Download(val garage: Garage) : SyncPlan

    /** 两边都有改动但能自动合并：本地写入 [garage]，再上传合并结果。 */
    data class UploadMerged(val garage: Garage, val summary: MergeSummary) : SyncPlan

    /** 两边都改且无法自动裁决（同一记录时间戳相同）：停手，等用户处理。 */
    data class Conflict(val summary: MergeSummary) : SyncPlan
}

/**
 * 同步决策：三方比较 → 合并 / 上传 / 下载 / 停手。
 *
 * 判定前提（由引擎计算）：
 * - `localChanged` = 本机明文 JSON 的 SHA-256 ≠ `lastLocalHash`（从未同步过视为有改动）；
 * - `remoteChanged` = 远端 ETag ≠ `lastEtag`（无 ETag 时比明文 hash）；
 * - `remote` = 远端账本；null 表示远端没有文件。
 *
 * 规则：
 * | 远端 | 本地改 | 远端改 | 结果 |
 * |---|---|---|---|
 * | 无 | 是 | - | [SyncPlan.Upload] |
 * | 无 | 否 | - | [SyncPlan.UpToDate] |
 * | 有 | 是 | 否 | [SyncPlan.Upload] |
 * | 有 | 否 | 是 | [SyncPlan.Download] |
 * | 有 | 是 | 是 | 三向合并：有冲突 → [SyncPlan.Conflict]，否则 [SyncPlan.UploadMerged] |
 * | 有 | 否 | 否 | [SyncPlan.UpToDate] |
 */
object SyncDecision {

    fun decide(
        local: Garage,
        remote: Garage?,
        base: Garage?,
        localChanged: Boolean,
        remoteChanged: Boolean,
        now: Long,
    ): SyncPlan {
        if (remote == null) {
            return if (localChanged) SyncPlan.Upload(local) else SyncPlan.UpToDate
        }
        return when {
            localChanged && remoteChanged -> {
                val merged = SyncMerge.mergeGarage(base, local, remote, now)
                if (merged.summary.hasConflicts) {
                    SyncPlan.Conflict(merged.summary)
                } else {
                    SyncPlan.UploadMerged(merged.garage, merged.summary)
                }
            }
            localChanged -> SyncPlan.Upload(local)
            remoteChanged -> SyncPlan.Download(remote)
            else -> SyncPlan.UpToDate
        }
    }
}
