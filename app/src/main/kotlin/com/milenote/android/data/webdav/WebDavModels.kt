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
