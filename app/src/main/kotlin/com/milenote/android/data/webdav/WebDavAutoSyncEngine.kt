package com.milenote.android.data.webdav

import com.milenote.android.data.garage.GarageRepository
import com.milenote.android.domain.backup.BackupCodec
import com.milenote.android.i18n.tr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自动同步结果。 */
data class AutoSyncOutcome(
    val kind: Kind,
    val message: String,
) {
    enum class Kind {
        SUCCESS,
        CONFLICT,
        ERROR,
        SKIPPED,

        /** 远端账本已加密，但本机没有保存加密密码：不自动覆盖，提示用户去设置页输入。 */
        NEEDS_PASSWORD,
    }
}

/**
 * WebDAV 自动同步引擎（前台打开应用与后台 Worker 共用）。
 *
 * 与手动「立即同步」同一套计划器：
 * - 只有本地有修改 → 上传；
 * - 只有远端有修改 → 下载覆盖本地；
 * - 两边都有修改 → 不自动覆盖，返回冲突，由用户手动处理。
 *
 * 同步比较的 hash 一律用**明文 JSON**（[WebDavRepository.plainHash]）：加密信封每次 salt/iv 随机，
 * 密文 hash 每次都变，不能用于判断内容是否变化。
 *
 * 安全闸：远端是加密文件而本机没有加密密码时**绝不上传明文**（避免静默降级成明文），
 * 返回 [AutoSyncOutcome.Kind.NEEDS_PASSWORD] 让用户先到设置页输入密码。
 */
class WebDavAutoSyncEngine(
    private val garage: GarageRepository,
    private val webDav: WebDavRepository,
) {
    private val mutex = Mutex()

    suspend fun sync(recordAutoResult: Boolean = true): AutoSyncOutcome = mutex.withLock {
        if (!webDav.canSync()) {
            return@withLock AutoSyncOutcome(
                AutoSyncOutcome.Kind.SKIPPED,
                tr("未配置 WebDAV，跳过自动同步", "WebDAV not configured — skipping auto sync"),
            )
        }
        if (garage.demoMode.value) {
            return@withLock AutoSyncOutcome(
                AutoSyncOutcome.Kind.SKIPPED,
                tr("演示账本不自动同步", "The demo ledger is not auto-synced"),
            )
        }
        try {
            garage.load()
            val localJson = garage.exportBackup()
            val localHash = webDav.plainHash(localJson)
            val sync = webDav.syncState.value
            val outcome = when (val fetched = webDav.fetchLedger()) {
                WebDavFetchResult.NotFound -> {
                    val plan = webDav.plan(localChanged = true, remoteChanged = false, remoteExists = false)
                    if (plan == Plan.UPLOAD) {
                        val meta = webDav.uploadLedger(localJson)
                        webDav.markSynced(meta.etag, localHash, localHash)
                        AutoSyncOutcome(
                            AutoSyncOutcome.Kind.SUCCESS,
                            tr("远端无备份，已上传本地账本", "No remote backup — uploaded the local ledger"),
                        )
                    } else {
                        AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, tr("已是最新", "Already up to date"))
                    }
                }
                WebDavFetchResult.PasswordRequired -> AutoSyncOutcome(
                    AutoSyncOutcome.Kind.NEEDS_PASSWORD,
                    tr(
                        "远端账本已加密，请到 设置 → WebDAV 同步 输入加密密码",
                        "The remote ledger is encrypted — enter the encryption password in Settings → WebDAV sync",
                    ),
                )
                is WebDavFetchResult.Found -> {
                    val remotePlain = fetched.ledger.text
                    val remoteHash = webDav.plainHash(remotePlain)
                    val remoteEncrypted = fetched.ledger is RemoteLedger.Decrypted
                    val remoteChanged = if (sync.lastEtag != null) {
                        sync.lastSyncAt == null || fetched.meta.etag != sync.lastEtag
                    } else {
                        sync.lastSyncAt == null || remoteHash != sync.lastRemoteHash
                    }
                    val localChanged = sync.lastLocalHash == null || localHash != sync.lastLocalHash

                    /** 上传前的防降级安全闸：远端加密而本机无加密密码时只提示，不上传明文。 */
                    suspend fun uploadLocal(action: String): AutoSyncOutcome {
                        if (remoteEncrypted && !webDav.hasEncryptionPassword()) {
                            return AutoSyncOutcome(
                                AutoSyncOutcome.Kind.NEEDS_PASSWORD,
                                tr(
                                    "远端账本已加密，请到 设置 → WebDAV 同步 输入加密密码（已阻止明文上传）",
                                    "The remote ledger is encrypted — enter the encryption password in Settings → WebDAV sync (plain upload blocked)",
                                ),
                            )
                        }
                        val meta = webDav.uploadLedger(localJson)
                        webDav.markSynced(meta.etag, localHash, localHash)
                        return AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, action)
                    }

                    when (webDav.plan(localChanged, remoteChanged, remoteExists = true)) {
                        Plan.UPLOAD -> uploadLocal(tr("已自动上传本地修改", "Local changes uploaded automatically"))
                        Plan.DOWNLOAD -> {
                            garage.replace(BackupCodec.decode(remotePlain))
                            val newLocal = garage.exportBackup()
                            val newHash = webDav.plainHash(newLocal)
                            webDav.markSynced(fetched.meta.etag, newHash, remoteHash)
                            AutoSyncOutcome(
                                AutoSyncOutcome.Kind.SUCCESS,
                                tr("已自动下载远端更新", "Remote updates downloaded automatically"),
                            )
                        }
                        Plan.CONFLICT -> AutoSyncOutcome(
                            AutoSyncOutcome.Kind.CONFLICT,
                            tr(
                                "本地与远端都有修改，已暂停自动同步，请手动处理冲突",
                                "Both local and remote changed — auto sync paused, resolve the conflict manually",
                            ),
                        )
                        Plan.NONE -> AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, tr("已是最新", "Already up to date"))
                    }
                }
            }
            if (recordAutoResult) webDav.markAutoSyncResult(outcome.message)
            outcome
        } catch (t: Throwable) {
            val message = tr(
                "自动同步失败：%s",
                "Auto sync failed: %s",
                t.message ?: tr("网络或服务器错误", "Network or server error"),
            )
            if (recordAutoResult) runCatching { webDav.markAutoSyncResult(message) }
            AutoSyncOutcome(AutoSyncOutcome.Kind.ERROR, message)
        }
    }
}
