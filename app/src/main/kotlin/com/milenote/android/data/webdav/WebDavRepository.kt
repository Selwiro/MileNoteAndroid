package com.milenote.android.data.webdav

import android.content.Context
import com.milenote.android.platform.webdav.OkHttpWebDavClient
import com.milenote.android.platform.webdav.WebDavClient
import com.milenote.android.platform.webdav.WebDavCredentials
import com.milenote.android.platform.webdav.WebDavException
import com.milenote.android.platform.webdav.WebDavUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * WebDAV 同步门面：配置（密码加密存储）+ 同步状态 + 远端读写。
 *
 * 典型接入方式（ViewModel 持有一个实例）：
 * ```kotlin
 * val repo = WebDavRepository(context)
 *
 * // 设置页：保存配置（password 传 null 表示沿用已保存密码）
 * repo.save(url, username, password, allowHttp)
 *
 * // 测试连接：404 不抛异常，返回 exists = false
 * val meta = repo.testConnection()
 *
 * // 同步：本地备份字节 → 算 hash → 看远端 → 计划器决策
 * val localBytes = repo.sha256(backupJson.toByteArray())
 * val state = repo.syncState.value
 * val result = repo.fetch()
 * when (result) {
 *     is WebDavFetchResult.Found -> {
 *         val remoteHash = Sha256.hex(result.bytes)
 *         val localChanged = localBytes != state.lastLocalHash
 *         val remoteChanged = result.meta.etag?.let { it != state.lastEtag } ?: (remoteHash != state.lastRemoteHash)
 *         when (WebDavSyncPlanner.plan(localChanged, remoteChanged, result.meta.exists)) {
 *             Plan.UPLOAD -> { val m = repo.upload(bytes); repo.markSynced(m.etag, localHash, remoteHash) }
 *             Plan.DOWNLOAD -> { saveToLocal(result.bytes); repo.markSynced(result.meta.etag, remoteHash, remoteHash) }
 *             Plan.CONFLICT -> showConflictDialog()
 *             Plan.NONE -> Unit
 *         }
 *     }
 *     WebDavFetchResult.NotFound -> {
 *         val m = repo.upload(bytes) // 首次上传，自动 If-None-Match: *
 *         repo.markSynced(m.etag, localHash, null)
 *     }
 * }
 * ```
 *
 * 线程安全：所有 suspend 方法内部由客户端切换到 IO 线程；`config`/`syncState` 为热流，
 * 在构造时即开始预热（读取 DataStore），VM 可直接 `collectAsStateWithLifecycle`。
 */
class WebDavRepository(
    context: Context,
    private val client: WebDavClient = OkHttpWebDavClient(),
) {

    private val appContext = context.applicationContext
    private val configStore = WebDavConfigStore(appContext)
    private val syncStateStore = WebDavSyncStateStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前配置（不含密码明文）；未配置为 null。 */
    val config: StateFlow<WebDavConfigState?> = configStore.config
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 上次同步状态（ETag + 三个 hash + 时间）。 */
    val syncState: StateFlow<WebDavSyncState> = syncStateStore.state
        .stateIn(scope, SharingStarted.Eagerly, WebDavSyncState())

    /** 是否有可用配置（地址已填）。 */
    val isConfigured: Boolean get() = config.value != null

    /** 是否具备同步条件（有配置且有密码）。 */
    fun canSync(): Boolean = config.value?.hasPassword == true

    /**
     * 保存配置。
     *
     * @param url WebDAV 文件完整地址；`http://` 仅当 [allowHttp] 为 true 时允许。
     * @param username Basic Auth 用户名。
     * @param password 密码明文；**null 或空白表示保留已保存的密码**；首次配置必须提供。
     * @param allowHttp 是否允许明文 HTTP。
     * @throws WebDavException URL 非法 / 首次未填密码时抛中文异常。
     */
    suspend fun save(
        url: String,
        username: String,
        password: String?,
        allowHttp: Boolean,
    ) {
        val normalizedUrl = WebDavUrl.validate(url, allowHttp)
        val normalizedUsername = username.trim()
        val previous = configStore.config.first()
        configStore.save(normalizedUrl, normalizedUsername, password, allowHttp)
        // 服务器地址或账号变化后，旧 ETag/hash 不再可靠，清空同步状态避免误判。
        if (previous == null ||
            previous.fileUrl != normalizedUrl ||
            previous.username != normalizedUsername
        ) {
            syncStateStore.clear()
        }
    }

    /** 清除配置与同步状态（密码密文一并删除）。 */
    suspend fun clear() {
        configStore.clear()
        syncStateStore.clear()
    }

    /**
     * 测试连接（HEAD，服务器不支持时自动退化为 GET Range）。
     *
     * @return 远端元信息；**404 不抛异常**，返回 `exists = false`。
     * @throws WebDavException 未配置 / 认证失败 / 网络或服务器错误（中文）。
     */
    suspend fun testConnection(): WebDavRemoteMeta = client.head(requireCredentials())

    /**
     * 下载远端备份。
     *
     * @return 远端有文件时 [WebDavFetchResult.Found]；404 时 [WebDavFetchResult.NotFound]。
     */
    suspend fun fetch(): WebDavFetchResult {
        val content = client.get(requireCredentials()) ?: return WebDavFetchResult.NotFound
        return WebDavFetchResult.Found(bytes = content.bytes, meta = content.meta)
    }

    /**
     * 上传本地备份。
     *
     * 条件请求策略：
     * - `force = false`（默认）：有 `syncState.lastEtag` → `If-Match`；没有 → `If-None-Match: *`
     *   （只允许新建，避免覆盖其他设备已存在的文件；412 由调用方引导先同步）；
     * - `force = true`：不带条件，直接覆盖远端（用户确认「用本地覆盖远端」时使用）。
     *
     * @return 上传后的远端元信息（服务器未返回 ETag 时 `etag = null`）。
     * @throws WebDavException 412（远端已被其他设备修改）/ 401/403 / 409 / 网络错误等。
     */
    suspend fun upload(bytes: ByteArray, force: Boolean = false): WebDavRemoteMeta {
        val credentials = requireCredentials()
        val lastEtag = syncState.value.lastEtag
        return client.put(
            credentials = credentials,
            bytes = bytes,
            ifMatchEtag = if (force) null else lastEtag,
            ifNoneMatchAny = !force && lastEtag == null,
        )
    }

    /**
     * 记录一次成功同步。
     *
     * @param etag 本次同步后远端 ETag（服务器不提供则为 null）。
     * @param localHash 本次同步的本地内容 SHA-256（[sha256] 计算）。
     * @param remoteHash 本次同步的远端内容 SHA-256（用于兼容无 ETag 的服务器）。
     */
    suspend fun markSynced(etag: String?, localHash: String, remoteHash: String?) {
        syncStateStore.save(
            WebDavSyncState(
                lastEtag = etag,
                lastLocalHash = localHash,
                lastRemoteHash = remoteHash,
                lastSyncAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 记录一次自动同步的结果（不改变 ETag/hash 同步状态）。 */
    suspend fun markAutoSyncResult(result: String) {
        val current = syncState.value
        syncStateStore.save(
            current.copy(
                lastAutoSyncAt = System.currentTimeMillis(),
                lastAutoSyncResult = result,
            ),
        )
    }

    /** 仅清空同步状态（例如用户选择「重新同步」）。 */
    suspend fun clearSyncState() {
        syncStateStore.clear()
    }

    /** 计算内容 SHA-256（十六进制小写），用于与同步状态比较。 */
    fun sha256(bytes: ByteArray): String = Sha256.hex(bytes)

    /** 同步计划器快捷入口（等价于 [WebDavSyncPlanner.plan]）。 */
    fun plan(localChanged: Boolean, remoteChanged: Boolean, remoteExists: Boolean): Plan =
        WebDavSyncPlanner.plan(localChanged, remoteChanged, remoteExists)

    private suspend fun requireCredentials(): WebDavCredentials {
        val cfg = configStore.config.first() ?: throw WebDavException("请先配置 WebDAV 服务器")
        if (!cfg.hasPassword) throw WebDavException("请先填写应用密码")
        val password = configStore.readPassword()
            ?: throw WebDavException("密码读取失败，请重新输入应用密码")
        return WebDavCredentials(
            url = cfg.fileUrl,
            username = cfg.username,
            password = password,
            allowHttp = cfg.allowHttp,
        )
    }
}
