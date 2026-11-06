package com.milenote.android.data.webdav

import com.milenote.android.data.garage.GarageRepository
import com.milenote.android.domain.backup.BackupCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自动同步结果。 */
data class AutoSyncOutcome(
    val kind: Kind,
    val message: String,
) {
    enum class Kind { SUCCESS, CONFLICT, ERROR, SKIPPED }
}

/**
 * WebDAV 自动同步引擎（前台打开应用与后台 Worker 共用）。
 *
 * 与手动「立即同步」同一套计划器：
 * - 只有本地有修改 → 上传；
 * - 只有远端有修改 → 下载覆盖本地；
 * - 两边都有修改 → 不自动覆盖，返回冲突，由用户手动处理。
 */
class WebDavAutoSyncEngine(
    private val garage: GarageRepository,
    private val webDav: WebDavRepository,
) {
    private val mutex = Mutex()

    suspend fun sync(recordAutoResult: Boolean = true): AutoSyncOutcome = mutex.withLock {
        if (!webDav.canSync()) {
            return@withLock AutoSyncOutcome(AutoSyncOutcome.Kind.SKIPPED, "未配置 WebDAV，跳过自动同步")
        }
        if (garage.demoMode.value) {
            return@withLock AutoSyncOutcome(AutoSyncOutcome.Kind.SKIPPED, "演示账本不自动同步")
        }
        try {
            garage.load()
            val localJson = garage.exportBackup()
            val localBytes = localJson.toByteArray(Charsets.UTF_8)
            val localHash = webDav.sha256(localBytes)
            val sync = webDav.syncState.value
            val outcome = when (val fetched = webDav.fetch()) {
                is WebDavFetchResult.NotFound -> {
                    val plan = webDav.plan(localChanged = true, remoteChanged = false, remoteExists = false)
                    if (plan == Plan.UPLOAD) {
                        val meta = webDav.upload(localBytes)
                        webDav.markSynced(meta.etag, localHash, localHash)
                        AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, "远端无备份，已上传本地账本")
                    } else {
                        AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, "已是最新")
                    }
                }
                is WebDavFetchResult.Found -> {
                    val remoteHash = webDav.sha256(fetched.bytes)
                    val remoteChanged = if (sync.lastEtag != null) {
                        sync.lastSyncAt == null || fetched.meta.etag != sync.lastEtag
                    } else {
                        sync.lastSyncAt == null || remoteHash != sync.lastRemoteHash
                    }
                    val localChanged = sync.lastLocalHash == null || localHash != sync.lastLocalHash
                    when (webDav.plan(localChanged, remoteChanged, remoteExists = true)) {
                        Plan.UPLOAD -> {
                            val meta = webDav.upload(localBytes)
                            webDav.markSynced(meta.etag, localHash, localHash)
                            AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, "已自动上传本地修改")
                        }
                        Plan.DOWNLOAD -> {
                            val text = fetched.bytes.toString(Charsets.UTF_8)
                            garage.replace(BackupCodec.decode(text))
                            val newLocal = garage.exportBackup()
                            val newHash = webDav.sha256(newLocal.toByteArray(Charsets.UTF_8))
                            webDav.markSynced(fetched.meta.etag, newHash, remoteHash)
                            AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, "已自动下载远端更新")
                        }
                        Plan.CONFLICT -> AutoSyncOutcome(
                            AutoSyncOutcome.Kind.CONFLICT,
                            "本地与远端都有修改，已暂停自动同步，请手动处理冲突",
                        )
                        Plan.NONE -> AutoSyncOutcome(AutoSyncOutcome.Kind.SUCCESS, "已是最新")
                    }
                }
            }
            if (recordAutoResult) webDav.markAutoSyncResult(outcome.message)
            outcome
        } catch (t: Throwable) {
            val message = "自动同步失败：${t.message ?: "网络或服务器错误"}"
            if (recordAutoResult) runCatching { webDav.markAutoSyncResult(message) }
            AutoSyncOutcome(AutoSyncOutcome.Kind.ERROR, message)
        }
    }
}
