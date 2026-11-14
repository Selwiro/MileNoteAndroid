package com.milenote.android.data.webdav

import com.milenote.android.data.garage.GarageRepository
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.domain.backup.MergeSummary
import com.milenote.android.domain.backup.SyncDecision
import com.milenote.android.domain.backup.SyncPlan
import com.milenote.android.domain.model.Garage
import com.milenote.android.i18n.tr
import com.milenote.android.platform.webdav.WebDavPreconditionFailedException
import kotlinx.coroutines.sync.withLock

/** 自动同步结果。 */
data class AutoSyncOutcome(
    val kind: Kind,
    val message: String,
) {
    enum class Kind {
        SUCCESS,
        /** 两边都有改动，已自动记录级合并（合并结果已上传）。 */
        MERGED,
        CONFLICT,
        ERROR,
        SKIPPED,

        /** 远端账本已加密，但本机没有保存加密密码：不自动覆盖，提示用户去设置页输入。 */
        NEEDS_PASSWORD,
    }
}

/**
 * WebDAV 自动同步引擎（前台打开应用与本地修改后防抖共用）。
 *
 * 同步语义（T019 之后）：
 * - 只有本地有修改 → 带条件上传；
 * - 只有远端有修改 → 下载覆盖本地；
 * - **两边都有修改 → 记录级三向合并（基线 = 上次同步时的内容）后自动上传**，不再直接停手；
 * - 同一条记录两边都改且 `updatedAt` 相同（毫秒级撞车）才返回 [AutoSyncOutcome.Kind.CONFLICT]；
 * - 删除靠墓碑传播，旧设备不会把删掉的记录同步回来。
 *
 * 上传一律带 `If-Match`（本次刚 fetch 到的 ETag）。遇到 412 说明判定与写入之间远端又被改过：
 * **重新 fetch → 重新合并 → 再上传**，最多重试 [MAX_PRECONDITION_RETRIES] 次，避免过期 ETag 死锁。
 *
 * 同步比较的 hash 一律用**明文 JSON**（[WebDavRepository.plainHash]）：加密信封每次 salt/iv 随机，
 * 密文 hash 每次都变，不能用于判断内容是否变化。
 *
 * 安全闸：远端是加密文件而本机没有加密密码时**绝不上传明文**（避免静默降级成明文）。
 *
 * 退避：连续失败 30s → 60s → 120s（上限 [MAX_BACKOFF_MS]），进程内记录；
 * 应用回前台（`resetBackoff`）、手动同步、任意一次成功都会清零，不会出现「一次失败后长期不同步」。
 */
class WebDavAutoSyncEngine(
    private val garage: GarageRepository,
    private val webDav: WebDavRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 连续失败次数与下次允许重试的时间（进程内退避，避免每次编辑都重试失败的网络请求）。 */
    private var consecutiveFailures = 0
    private var nextAttemptAtMillis = 0L

    /** 退避时长：30s 起逐次翻倍，上限 2 分钟（原 15 分钟太长，用户观感像「再也不同步」）。 */
    private fun backoffMillis(failures: Int): Long =
        minOf(BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 2), MAX_BACKOFF_MS)

    /** 清零退避（应用回前台 / 手动同步 / 同步成功时调用）。 */
    fun resetBackoff() {
        consecutiveFailures = 0
        nextAttemptAtMillis = 0L
    }

    /**
     * 执行一次同步。
     *
     * @param recordAutoResult 是否把本次结果写入「上次自动同步」（手动同步传 false，避免覆盖自动同步记录）。
     * @param ignoreBackoff 手动同步传 true：无视上次失败的退避，立即尝试。
     */
    suspend fun sync(recordAutoResult: Boolean = true, ignoreBackoff: Boolean = false): AutoSyncOutcome =
        webDav.syncMutex.withLock {
            skipReason(ignoreBackoff)?.let { return@withLock it }
            runWithRetries(recordAutoResult)
        }

    /** 带 412 重试的同步循环：每次重试都是一次全新的 fetch + 判定 + 合并。 */
    private suspend fun runWithRetries(recordAutoResult: Boolean): AutoSyncOutcome {
        var attempt = 0
        while (true) {
            attempt++
            val result = runCatching { runPass() }
            val failure = result.exceptionOrNull()
            if (failure == null) {
                consecutiveFailures = 0
                nextAttemptAtMillis = 0L
                val outcome = result.getOrThrow()
                webDav.recordSyncOutcome(
                    problem = outcome.problemOrNull(),
                    autoResult = outcome.message.takeIf { recordAutoResult },
                )
                return outcome
            }
            // 412：远端在「读出 → 写回」之间又被别的设备改了，重新走一遍 fetch + 合并。
            if (failure is WebDavPreconditionFailedException && attempt <= MAX_PRECONDITION_RETRIES) {
                continue
            }
            // 本地在同步过程中又被改过：丢弃这次基于旧快照的判断，重读重算（不能覆盖用户的新修改）。
            if (failure is LocalChangedDuringSyncException && attempt <= MAX_LOCAL_CHANGE_RETRIES) {
                continue
            }
            return handleFailure(failure, recordAutoResult)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 前置判断
    // ---------------------------------------------------------------------------------------

    private fun skipReason(ignoreBackoff: Boolean): AutoSyncOutcome? {
        if (garage.persistenceBlocked.value) {
            return AutoSyncOutcome(
                AutoSyncOutcome.Kind.SKIPPED,
                tr(
                    "本机数据读取异常，已暂停同步以保护数据",
                    "Local data could not be read — sync paused to protect your data",
                ),
            )
        }
        if (!ignoreBackoff) {
            val waitMillis = nextAttemptAtMillis - clock()
            if (consecutiveFailures > 0 && waitMillis > 0) {
                return AutoSyncOutcome(
                    AutoSyncOutcome.Kind.SKIPPED,
                    tr(
                        "上次同步失败，%d 秒后自动重试",
                        "Last sync failed — retrying in %d s",
                        (waitMillis / 1000).coerceAtLeast(1),
                    ),
                )
            }
        }
        if (!webDav.canSync()) {
            return AutoSyncOutcome(
                AutoSyncOutcome.Kind.SKIPPED,
                tr("未配置 WebDAV，跳过自动同步", "WebDAV not configured — skipping auto sync"),
            )
        }
        if (garage.demoMode.value) {
            return AutoSyncOutcome(
                AutoSyncOutcome.Kind.SKIPPED,
                tr("演示模式不自动同步", "The demo data is not auto-synced"),
            )
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // 一次完整同步
    // ---------------------------------------------------------------------------------------

    private suspend fun runPass(): AutoSyncOutcome {
        val local = garage.load()
        val localJson = BackupCodec.encode(local)
        val localHash = webDav.plainHash(localJson)
        val state = webDav.syncState.value

        val fetch = webDav.fetchLedger()
        if (fetch is WebDavFetchResult.PasswordRequired) {
            return AutoSyncOutcome(
                AutoSyncOutcome.Kind.NEEDS_PASSWORD,
                tr(
                    "远端数据已加密，请到 设置 → WebDAV 同步 输入加密密码",
                    "The remote data is encrypted — enter the encryption password in Settings → WebDAV sync",
                ),
            )
        }
        val found = fetch as? WebDavFetchResult.Found
        val remotePlain = found?.ledger?.text
        val remoteHash = remotePlain?.let { webDav.plainHash(it) }
        val remoteChanged = when {
            found == null -> false
            state.lastSyncAt == null -> true
            // 优先比 ETag；但 GET 没返回 ETag（少数服务器只在 HEAD/PUT 返回）时退回比明文 hash。
            found.meta.etag != null && state.lastEtag != null -> found.meta.etag != state.lastEtag
            else -> remoteHash != state.lastRemoteHash
        }
        val localChanged = state.lastLocalHash == null || localHash != state.lastLocalHash

        val remoteGarage = remotePlain?.let { BackupCodec.decode(it) }
        val baseGarage = runCatching { webDav.readBaseline()?.let { BackupCodec.decode(it) } }.getOrNull()
        val plan = SyncDecision.decide(
            local = local,
            remote = remoteGarage,
            base = baseGarage,
            localChanged = localChanged,
            remoteChanged = remoteChanged,
            now = clock(),
        )
        return when (plan) {
            SyncPlan.UpToDate -> {
                if (found != null) webDav.saveBaseline(localJson)
                success(tr("已是最新", "Already up to date"))
            }
            is SyncPlan.Upload -> upload(
                garageToUpload = plan.garage,
                found = found,
                action = if (found == null) {
                    tr("远端无备份，已上传本地数据", "No remote backup — uploaded the local data")
                } else {
                    tr("已自动上传本地修改", "Local changes uploaded automatically")
                },
            )
            is SyncPlan.Download -> download(local, plan.garage, found)
            is SyncPlan.UploadMerged -> uploadMerged(local, plan.garage, plan.summary, found)
            is SyncPlan.Conflict -> AutoSyncOutcome(
                AutoSyncOutcome.Kind.CONFLICT,
                SyncMessages.conflict(plan.summary),
            )
        }
    }

    /**
     * 用远端覆盖本地。
     *
     * 覆盖前用 [GarageRepository.replaceIfUnchanged] 校验「本地仍是同步开始时的快照」：
     * 同步期间用户又保存了记录时不覆盖，抛 [LocalChangedDuringSyncException] 让外层重算。
     */
    private suspend fun download(
        local: Garage,
        remoteGarage: Garage,
        found: WebDavFetchResult.Found?,
    ): AutoSyncOutcome {
        // found 一定存在（远端没有文件时不会走到 Download）；保守处理避免空指针。
        if (found == null) return success(tr("已是最新", "Already up to date"))
        val applied = garage.replaceIfUnchanged(local, remoteGarage)
            ?: throw LocalChangedDuringSyncException()
        val json = BackupCodec.encode(applied)
        val localHash = webDav.plainHash(json)
        webDav.markSynced(found.meta.etag, localHash, webDav.plainHash(found.ledger.text))
        webDav.saveBaseline(json)
        return success(tr("已自动下载远端更新", "Remote updates downloaded automatically"))
    }

    private suspend fun upload(
        garageToUpload: Garage,
        found: WebDavFetchResult.Found?,
        action: String,
    ): AutoSyncOutcome {
        if (found?.ledger is RemoteLedger.Decrypted && !webDav.hasEncryptionPassword()) {
            return blockedPlainUpload()
        }
        val json = BackupCodec.encode(garageToUpload)
        val meta = uploadWithPrecondition(json, found)
        val hash = webDav.plainHash(json)
        webDav.markSynced(meta.etag, hash, hash)
        webDav.saveBaseline(json)
        return success(action)
    }

    private suspend fun uploadMerged(
        local: Garage,
        merged: Garage,
        summary: MergeSummary,
        found: WebDavFetchResult.Found?,
    ): AutoSyncOutcome {
        if (found?.ledger is RemoteLedger.Decrypted && !webDav.hasEncryptionPassword()) {
            return blockedPlainUpload()
        }
        // 先把合并结果落到本机（写快照可回滚），再上传；上传失败下次会重新合并。
        // 只有「本地仍是同步开始时的快照」才写，避免把同步期间用户刚保存的修改覆盖掉。
        val applied = garage.replaceIfUnchanged(local, merged)
            ?: throw LocalChangedDuringSyncException()
        val json = BackupCodec.encode(applied)
        val meta = uploadWithPrecondition(json, found)
        val hash = webDav.plainHash(json)
        webDav.markSynced(meta.etag, hash, hash)
        webDav.saveBaseline(json)
        return AutoSyncOutcome(AutoSyncOutcome.Kind.MERGED, SyncMessages.merged(summary))
    }

    /**
     * 带条件的上传：`If-Match` 用**本次刚 fetch 到的 ETag**（而不是可能过期的 `lastEtag`）。
     *
     * 服务器不返回 ETag 而远端又存在时无法做条件写，只能无条件覆盖（内容已在本次同步里核对过）。
     */
    private suspend fun uploadWithPrecondition(json: String, found: WebDavFetchResult.Found?): WebDavRemoteMeta {
        val etag = found?.meta?.etag
        val force = found != null && etag == null
        return webDav.uploadLedger(json, force = force, expectedEtag = etag)
    }

    private fun blockedPlainUpload(): AutoSyncOutcome = AutoSyncOutcome(
        AutoSyncOutcome.Kind.NEEDS_PASSWORD,
        tr(
            "远端数据已加密，请到 设置 → WebDAV 同步 输入加密密码（已阻止明文上传）",
            "The remote data is encrypted — enter the encryption password in Settings → WebDAV sync (plain upload blocked)",
        ),
    )

    private fun success(message: String): AutoSyncOutcome =
        AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, message)

    // ---------------------------------------------------------------------------------------
    // 失败处理
    // ---------------------------------------------------------------------------------------

    private suspend fun handleFailure(t: Throwable, recordAutoResult: Boolean): AutoSyncOutcome {
        consecutiveFailures += 1
        nextAttemptAtMillis = clock() + backoffMillis(consecutiveFailures)
        val message = if (t is WebDavPreconditionFailedException) {
            tr(
                "远端文件正在被其他设备修改，已自动重试 %d 次仍失败，请稍后重试",
                "The remote file keeps changing on another device — retried %d times, try again later",
                MAX_PRECONDITION_RETRIES,
            )
        } else if (t is LocalChangedDuringSyncException) {
            tr(
                "同步期间本地又保存了新修改，已放弃这次覆盖，稍后会自动重试",
                "Local edits kept arriving during sync — this overwrite was skipped and will be retried",
            )
        } else {
            tr(
                "自动同步失败：%s",
                "Auto sync failed: %s",
                t.message ?: tr("网络或服务器错误", "Network or server error"),
            )
        }
        val problem = WebDavProblem(WebDavProblemKind.ERROR, message, clock())
        runCatching {
            webDav.recordSyncOutcome(problem, autoResult = message.takeIf { recordAutoResult })
        }
        return AutoSyncOutcome(AutoSyncOutcome.Kind.ERROR, message)
    }

    /**
     * 结果为异常时返回持久化的异常快照（供「我的」页跨会话显示），成功 / 跳过时为 null。
     *
     * 「跳过」不算异常：未配置、演示账本、本机账本读取异常（另有提示）都在更早的分支返回，
     * 而「上次失败、N 秒后重试」的跳过由上一次失败已经记录过异常。
     */
    private fun AutoSyncOutcome.problemOrNull(): WebDavProblem? {
        val kind = when (this.kind) {
            AutoSyncOutcome.Kind.SUCCESS,
            AutoSyncOutcome.Kind.MERGED,
            AutoSyncOutcome.Kind.SKIPPED,
            -> return null
            AutoSyncOutcome.Kind.ERROR -> WebDavProblemKind.ERROR
            AutoSyncOutcome.Kind.CONFLICT -> WebDavProblemKind.CONFLICT
            AutoSyncOutcome.Kind.NEEDS_PASSWORD -> WebDavProblemKind.NEEDS_PASSWORD
        }
        return WebDavProblem(kind, message, clock())
    }

    /** 同步过程中本地账本又被改过（用户刚保存了一笔）：本次判断作废，重读重算。 */
    private class LocalChangedDuringSyncException : Exception()

    private companion object {
        /** 412 后最多再重试几次（总共 3 次尝试）。 */
        const val MAX_PRECONDITION_RETRIES = 2

        /** 本地改动打断同步时最多再重算几次。 */
        const val MAX_LOCAL_CHANGE_RETRIES = 2

        const val BACKOFF_BASE_MS = 30_000L

        /** 退避上限 2 分钟（原 15 分钟太长）。 */
        const val MAX_BACKOFF_MS = 2L * 60_000L
    }
}
