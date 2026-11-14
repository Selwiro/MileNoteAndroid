package com.milenote.android.data.webdav

import android.content.Context
import com.milenote.android.domain.backup.BackupException
import com.milenote.android.domain.backup.LedgerCrypto
import com.milenote.android.i18n.tr
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * WebDAV 同步门面：配置（密码加密存储）+ 同步状态 + 远端账本读写。
 *
 * 远端格式由配置决定：保存了账本加密密码 → 上传 `drive-ledger-encrypted` v1 信封（AES-256-GCM），
 * 否则上传明文 JSON。下载时两种格式都能识别：明文直接返回，信封用本机保存的密码解密；
 * 本机没有加密密码且远端是信封时返回 [WebDavFetchResult.PasswordRequired]，由 UI 引导输入密码。
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
    private val baselineStore = SyncBaselineStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 同步互斥：手动「立即同步 / 上传 / 下载 / 冲突合并」与前台自动同步**共用同一把锁**。
     *
     * 修复前手动路径没有锁，可能与自动同步并发 PUT/GET，`markSynced` 也会乱序 →
     * 远端可能被旧状态覆盖。所有真正读写远端的流程都应包在 `syncMutex.withLock { }` 里。
     */
    val syncMutex = Mutex()

    /**
     * 最近一次 fetch 到的「加密信封」原始字节（内存缓存，不落库）。
     *
     * 远端需要密码但本机没有时 [fetchLedger] 返回 [WebDavFetchResult.PasswordRequired]，
     * UI 拿到用户输入的密码后可先用它本地试解密：密码错误时不落库、不重发网络请求。
     */
    @Volatile
    private var encryptedRemoteCache: ByteArray? = null

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

    /** 是否已开启加密上传（= 已保存账本加密密码）。 */
    fun hasEncryptionPassword(): Boolean = config.value?.hasEncryptionPassword == true

    /**
     * 保存配置。
     *
     * @param url WebDAV 文件完整地址；`http://` 仅当 [allowHttp] 为 true 时允许。
     * @param username Basic Auth 用户名。
     * @param password 密码明文；**null 或空白表示保留已保存的密码**；首次配置必须提供。
     * @param allowHttp 是否允许明文 HTTP。
     * @param encryptionPassword 账本加密密码明文；**null 或空白表示保留已保存的加密密码**。
     * @param enableEncryption 是否开启加密上传；false 时清除本机保存的加密密码（远端文件不动）。
     * @throws WebDavException URL 非法 / 首次未填应用密码 / 开启加密但无可用密码或密码过短。
     */
    suspend fun save(
        url: String,
        username: String,
        password: String?,
        allowHttp: Boolean,
        encryptionPassword: String?,
        enableEncryption: Boolean,
    ) {
        val normalizedUrl = WebDavUrl.validate(url, allowHttp)
        val normalizedUsername = username.trim()
        val previous = configStore.config.first()
        configStore.save(
            fileUrl = normalizedUrl,
            username = normalizedUsername,
            password = password,
            allowHttp = allowHttp,
            encryptionPassword = encryptionPassword,
            enableEncryption = enableEncryption,
        )
        // 服务器地址或账号变化后，旧 ETag/hash 不再可靠，清空同步状态避免误判。
        if (previous == null ||
            previous.fileUrl != normalizedUrl ||
            previous.username != normalizedUsername
        ) {
            syncStateStore.clear()
            // 换了远端文件，旧基线不再对应当前远端，必须一起清掉（否则三向合并会拿错 base）。
            withContext(Dispatchers.IO) { baselineStore.clear() }
        }
    }

    /** 清除配置与同步状态（两个密码密文一并删除）。 */
    suspend fun clear() {
        configStore.clear()
        syncStateStore.clear()
        withContext(Dispatchers.IO) { baselineStore.clear() }
        encryptedRemoteCache = null
    }

    /**
     * 上次同步基线（三向合并的 base，明文 JSON）；没有基线时返回 null。
     *
     * 基线读不出来（文件损坏 / 首次升级）时调用方按「无基线」降级合并，不会丢数据。
     */
    suspend fun readBaseline(): String? = withContext(Dispatchers.IO) { baselineStore.read() }

    /** 保存本次同步后「两边一致」的明文 JSON 作为新基线。 */
    suspend fun saveBaseline(plainJson: String) {
        withContext(Dispatchers.IO) { baselineStore.write(plainJson) }
    }

    /** 清除基线（换服务器 / 清除配置；由 [save] / [clear] 内部调用）。 */
    suspend fun clearBaseline() {
        withContext(Dispatchers.IO) { baselineStore.clear() }
    }

    /**
     * 测试连接（HEAD，服务器不支持时自动退化为 GET Range）。
     *
     * @return 远端元信息；**404 不抛异常**，返回 `exists = false`。
     * @throws WebDavException 未配置 / 认证失败 / 网络或服务器错误（中文）。
     */
    suspend fun testConnection(): WebDavRemoteMeta = client.head(requireCredentials())

    /**
     * 下载远端账本。
     *
     * - 明文文件：直接返回 [RemoteLedger.Plain]；
     * - 加密信封 + 本机已保存密码：解密后返回 [RemoteLedger.Decrypted]；
     * - 加密信封 + 本机没有密码：返回 [WebDavFetchResult.PasswordRequired]（原始字节缓存在内存，供 UI 校验密码）；
     * - 加密信封 + 已保存密码错误/文件损坏：抛 [WebDavException]（文案来自 [LedgerCrypto]）。
     *
     * @return 远端有文件时 [WebDavFetchResult.Found]；404 时 [WebDavFetchResult.NotFound]。
     */
    suspend fun fetchLedger(): WebDavFetchResult {
        val content = client.get(requireCredentials()) ?: run {
            encryptedRemoteCache = null
            return WebDavFetchResult.NotFound
        }
        val text = content.bytes.toString(Charsets.UTF_8)
        if (!LedgerCrypto.isEncrypted(text)) {
            encryptedRemoteCache = null
            return WebDavFetchResult.Found(
                bytes = content.bytes,
                meta = content.meta,
                ledger = RemoteLedger.Plain(text),
            )
        }
        encryptedRemoteCache = content.bytes
        val password = configStore.readEncryptionPassword()
            ?: return WebDavFetchResult.PasswordRequired
        val plain = try {
            LedgerCrypto.decryptIfEncrypted(text, password)
        } catch (e: BackupException) {
            throw WebDavException(
                e.message ?: tr("解密失败：密码不正确或文件已损坏", "Decryption failed: wrong password or corrupted file"),
                e,
            )
        }
        encryptedRemoteCache = null
        return WebDavFetchResult.Found(
            bytes = content.bytes,
            meta = content.meta,
            ledger = RemoteLedger.Decrypted(plain),
        )
    }

    /** 最近一次 [fetchLedger] 得到的加密信封原始字节；没有缓存时为 null。 */
    fun cachedEncryptedRemoteBytes(): ByteArray? = encryptedRemoteCache

    /**
     * 上传本地账本（[plainJson] 是 [com.milenote.android.domain.backup.BackupCodec.encode] 的明文 JSON）。
     *
     * 条件请求策略：
     * - `force = false`（默认）：`expectedEtag`（本次刚 fetch 到的 ETag）优先，其次 `syncState.lastEtag`
     *   → `If-Match`；两者都没有 → `If-None-Match: *`（只允许新建，避免覆盖其他设备已存在的文件）；
     * - `force = true`：不带条件，直接覆盖远端（用户确认「用本地覆盖远端」、或服务器不返回 ETag 时使用）。
     *
     * **412 重试**：自动同步遇到 412 会重新 fetch 并把新 ETag 通过 [expectedEtag] 传进来重试，
     * 避免一直拿过期 `lastEtag` 死锁（T019）。
     *
     * 已保存加密密码时上传加密信封，否则上传明文。上传的是**明文 JSON**，加密/明文由配置决定，
     * 调用方不再自己处理字节。
     *
     * @return 上传后的远端元信息（服务器未返回 ETag 时 `etag = null`）。
     * @throws WebDavException 加密已开启但密码读不出 / 加密失败 / 412 / 401/403 / 409 / 网络错误等。
     */
    suspend fun uploadLedger(
        plainJson: String,
        force: Boolean = false,
        expectedEtag: String? = null,
    ): WebDavRemoteMeta {
        val credentials = requireCredentials()
        val cfg = configStore.config.first()
            ?: throw WebDavException(tr("请先配置 WebDAV 服务器", "Configure the WebDAV server first"))
        val encryptionPassword = if (cfg.hasEncryptionPassword) {
            configStore.readEncryptionPassword() ?: throw WebDavException(
                tr("加密密码读取失败，请重新设置加密密码", "Could not read the encryption password — please set it again"),
            )
        } else {
            null
        }
        val bytes = if (encryptionPassword != null) {
            val encrypted = try {
                LedgerCrypto.encrypt(plainJson, encryptionPassword)
            } catch (e: BackupException) {
                throw WebDavException(e.message ?: tr("数据加密失败", "Could not encrypt the data"), e)
            }
            encrypted.toByteArray(Charsets.UTF_8)
        } else {
            plainJson.toByteArray(Charsets.UTF_8)
        }
        val ifMatchEtag = when {
            force -> null
            !expectedEtag.isNullOrEmpty() -> expectedEtag
            else -> syncState.value.lastEtag
        }
        return client.put(
            credentials = credentials,
            bytes = bytes,
            ifMatchEtag = ifMatchEtag,
            ifNoneMatchAny = !force && ifMatchEtag == null,
        )
    }

    /**
     * 保存/更新账本加密密码（= 开启加密上传）；长度按 [LedgerCrypto.MIN_PASSWORD_LENGTH] 校验。
     *
     * 用于「在另一台设备输入密码成功解密后自动记住密码」（设计 D4），避免后续上传把远端降级成明文。
     *
     * @throws WebDavException 密码短于 8 个字符。
     */
    suspend fun saveEncryptionPassword(password: String) {
        configStore.saveEncryptionPassword(password)
    }

    /**
     * 计算明文 JSON 的 SHA-256（十六进制小写），同步状态比较的统一口径。
     *
     * 加密后每次 salt/iv 随机、密文 hash 每次都会变，因此本地与远端一律用**明文**计算 hash。
     */
    fun plainHash(text: String): String = plainLedgerHash(text)

    /**
     * 记录一次成功同步（同时清空上次异常）。
     *
     * 成功即代表「同步恢复正常」：「我的」页状态行的异常提示随之消失；
     * 自动同步的「上次时间 / 结果」沿用已存值，不会被手动同步抹掉。
     *
     * @param etag 本次同步后远端 ETag（服务器不提供则为 null）。
     * @param localHash 本次同步的本地**明文**内容 SHA-256（[plainHash] 计算）。
     * @param remoteHash 本次同步的远端**明文**内容 SHA-256（用于兼容无 ETag 的服务器）。
     */
    suspend fun markSynced(etag: String?, localHash: String, remoteHash: String?) {
        // 读持久层的**最新**值（而不是可能滞后的 StateFlow），避免把刚写入的 etag/hash 覆盖回旧值。
        val current = syncStateStore.state.first()
        syncStateStore.save(current.afterSynced(etag, localHash, remoteHash, System.currentTimeMillis()))
    }

    /**
     * 记录一次同步的结果与健康度（自动同步与手动同步共用）。
     *
     * @param problem 本次同步的异常；null = 恢复正常（清空上次异常）。
     * @param autoResult 自动同步的结果说明；null = 本次不是自动同步，保留原有「上次自动同步」记录。
     */
    suspend fun recordSyncOutcome(problem: WebDavProblem?, autoResult: String? = null) {
        val current = syncStateStore.state.first()
        syncStateStore.save(current.afterSyncOutcome(problem, autoResult, System.currentTimeMillis()))
    }

    /** 仅清空同步状态（例如用户选择「重新同步」）。 */
    suspend fun clearSyncState() {
        syncStateStore.clear()
    }

    /** 同步计划器快捷入口（等价于 [WebDavSyncPlanner.plan]）。 */
    fun plan(localChanged: Boolean, remoteChanged: Boolean, remoteExists: Boolean): Plan =
        WebDavSyncPlanner.plan(localChanged, remoteChanged, remoteExists)

    private suspend fun requireCredentials(): WebDavCredentials {
        val cfg = configStore.config.first() ?: throw WebDavException(tr("请先配置 WebDAV 服务器", "Configure the WebDAV server first"))
        if (!cfg.hasPassword) throw WebDavException(tr("请先填写应用密码", "Enter the app password first"))
        val password = configStore.readPassword()
            ?: throw WebDavException(tr("密码读取失败，请重新输入应用密码", "Could not read the password — please re-enter the app password"))
        return WebDavCredentials(
            url = cfg.fileUrl,
            username = cfg.username,
            password = password,
            allowHttp = cfg.allowHttp,
        )
    }
}
