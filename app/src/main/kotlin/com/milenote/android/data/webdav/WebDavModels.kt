package com.milenote.android.data.webdav

import java.security.MessageDigest

/**
 * WebDAV 配置的**只读视图**（不含密码明文）。
 *
 * @property fileUrl 远端 WebDAV **文件**完整地址（如 `https://dav.example.com/dav/milenote.json`）。
 * @property username Basic Auth 用户名。
 * @property allowHttp 是否允许明文 `http://`（默认 false，仅 https）。
 * @property hasPassword 是否已保存密码（密码密文存放于 Keystore 加密的 DataStore 中，不对外暴露）。
 * @property hasEncryptionPassword 是否已保存账本加密密码（= 加密上传已开启）。
 */
data class WebDavConfigState(
    val fileUrl: String,
    val username: String,
    val allowHttp: Boolean,
    val hasPassword: Boolean,
    val hasEncryptionPassword: Boolean = false,
)

/**
 * 远端文件元信息。
 *
 * @property exists 远端文件是否存在。
 * @property etag 规范化后的 ETag（已去 `W/` 前缀与外层双引号；服务器不返回时为 null）。
 * @property size 字节数（服务器未给 Content-Length 时为 0）。
 * @property lastModifiedMs 最后修改时间（毫秒时间戳；无/无法解析时为 null）。
 */
data class WebDavRemoteMeta(
    val exists: Boolean,
    val etag: String? = null,
    val size: Long = 0L,
    val lastModifiedMs: Long? = null,
)

/**
 * 同步异常类别（**跨会话**展示用的稳定口径）。
 *
 * 只持久化名称、不持久化本地化文案：[message][WebDavProblem.message] 是异常发生时的界面语言，
 * 「我的」页每次渲染时按 [kind][WebDavProblem.kind] 重新取当前语言短标签。
 */
enum class WebDavProblemKind {
    /** 网络 / 服务器 / 权限等错误：本次同步没有成功。 */
    ERROR,

    /** 本地与远端都有修改，自动同步已暂停，等待用户手动选择保留哪一边。 */
    CONFLICT,

    /** 远端账本已加密而本机没有加密密码：已阻止明文上传，等待用户输入密码。 */
    NEEDS_PASSWORD,
}

/**
 * 最近一次同步异常快照（任何一次同步成功后被清空）。
 *
 * @property kind 异常类别（稳定口径，界面据此显示「同步失败 / 同步冲突 / 同步需密码」）。
 * @property message 面向用户的说明（异常发生时的界面语言，用于设置页详情）。
 * @property at 异常发生时刻（epoch 毫秒）。
 */
data class WebDavProblem(
    val kind: WebDavProblemKind,
    val message: String,
    val at: Long,
)

/**
 * 上次同步状态（用于三向比较与条件请求）。
 *
 * @property lastEtag 上次同步成功时的远端 ETag（服务器不提供 ETag 时为 null）。
 * @property lastLocalHash 上次同步时**本地明文**备份内容的 SHA-256（十六进制小写）。
 * @property lastRemoteHash 上次同步时**远端明文**内容的 SHA-256；用于兼容不返回 ETag 的服务器。
 * @property lastSyncAt 上次同步成功的时间戳（毫秒）。
 */
data class WebDavSyncState(
    val lastEtag: String? = null,
    val lastLocalHash: String? = null,
    val lastRemoteHash: String? = null,
    val lastSyncAt: Long? = null,
    /** 最近一次自动同步时间（毫秒）。 */
    val lastAutoSyncAt: Long? = null,
    /** 最近一次自动同步结果（成功/冲突/失败的中文说明）。 */
    val lastAutoSyncResult: String? = null,
    /** 最近一次同步异常类别（[WebDavProblemKind] 名称）；正常时为 null。 */
    val lastProblemKind: String? = null,
    /** 最近一次同步异常说明；正常时为 null。 */
    val lastProblemMessage: String? = null,
    /** 最近一次同步异常时刻（毫秒）；正常时为 null。 */
    val lastProblemAt: Long? = null,
) {
    /**
     * 最近一次同步异常；正常时为 null。
     *
     * 类别名无法识别（旧版本写入 / 数据被改）但仍有说明时按 [WebDavProblemKind.ERROR] 处理，
     * 宁可在「我的」页多提醒一次，也不要静默藏掉一次失败。
     */
    val problem: WebDavProblem?
        get() {
            val message = lastProblemMessage ?: return null
            val kind = lastProblemKind
                ?.let { name -> WebDavProblemKind.entries.firstOrNull { it.name == name } }
                ?: WebDavProblemKind.ERROR
            return WebDavProblem(kind = kind, message = message, at = lastProblemAt ?: 0L)
        }
}

/**
 * 一次**成功**同步后的新状态：写入 ETag / hash / 时间，并清空上次异常。
 *
 * 纯函数（时间戳由调用方传入）便于单测：成功即代表「同步恢复正常」，
 * 「我的」页状态行的异常提示随之消失；「上次自动同步」记录保持不变（手动同步不会抹掉它）。
 */
fun WebDavSyncState.afterSynced(
    etag: String?,
    localHash: String,
    remoteHash: String?,
    now: Long,
): WebDavSyncState = copy(
    lastEtag = etag,
    lastLocalHash = localHash,
    lastRemoteHash = remoteHash,
    lastSyncAt = now,
    lastProblemKind = null,
    lastProblemMessage = null,
    lastProblemAt = null,
)

/**
 * 一次同步结果后的新状态：失败 / 冲突 / 缺密码写入异常，成功（problem = null）清空异常。
 *
 * 纯函数（时间戳由调用方传入）便于单测。
 *
 * @param problem 本次同步的异常；null = 正常。
 * @param autoResult 自动同步的结果说明；null = 本次不是自动同步，保留原有「上次自动同步」记录。
 */
fun WebDavSyncState.afterSyncOutcome(
    problem: WebDavProblem?,
    autoResult: String?,
    now: Long,
): WebDavSyncState = copy(
    lastAutoSyncAt = if (autoResult != null) now else lastAutoSyncAt,
    lastAutoSyncResult = autoResult ?: lastAutoSyncResult,
    lastProblemKind = problem?.kind?.name,
    lastProblemMessage = problem?.message,
    lastProblemAt = problem?.at,
)

/**
 * 远端账本内容（已解密/明文）。
 *
 * - [Plain]：远端文件本身就是明文 JSON（旧文件 / 未开启加密）；
 * - [Decrypted]：远端是 `drive-ledger-encrypted` 加密信封，已用本机保存的密码解密。
 *
 * [text] 一律为**明文 JSON**，同步状态比较必须基于它（加密每次 salt/iv 随机，密文 hash 会变）。
 */
sealed interface RemoteLedger {
    val text: String

    data class Plain(override val text: String) : RemoteLedger

    data class Decrypted(override val text: String) : RemoteLedger
}

/**
 * fetch 的结果。
 *
 * - [Found]：远端有文件，[Found.bytes] 是远端**原始**字节（可能是加密信封），[Found.ledger] 是明文内容；
 * - [NotFound]：远端没有文件；
 * - [PasswordRequired]：远端是加密信封，但本机没有已保存的加密密码 → 需要用户输入。
 */
sealed interface WebDavFetchResult {
    data class Found(
        val bytes: ByteArray,
        val meta: WebDavRemoteMeta,
        val ledger: RemoteLedger,
    ) : WebDavFetchResult

    data object NotFound : WebDavFetchResult

    data object PasswordRequired : WebDavFetchResult
}

/**
 * 明文 JSON 内容的 SHA-256（十六进制小写），同步状态比较的统一口径。
 *
 * 加密后每次 salt/iv 随机，密文 hash 每次都变；只用明文 hash 才能判断「内容是否真的变了」。
 */
fun plainLedgerHash(text: String): String = Sha256.hex(text.toByteArray(Charsets.UTF_8))

/** SHA-256 工具（用于本地/远端内容比对；十六进制小写）。 */
object Sha256 {
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            builder.append(HEX[(byte.toInt() shr 4) and 0x0F])
            builder.append(HEX[byte.toInt() and 0x0F])
        }
        return builder.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
