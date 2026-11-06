package com.milenote.android.data.webdav

import java.security.MessageDigest

/**
 * WebDAV 配置的**只读视图**（不含密码明文）。
 *
 * @property fileUrl 远端 WebDAV **文件**完整地址（如 `https://dav.example.com/dav/milenote.json`）。
 * @property username Basic Auth 用户名。
 * @property allowHttp 是否允许明文 `http://`（默认 false，仅 https）。
 * @property hasPassword 是否已保存密码（密码密文存放于 Keystore 加密的 DataStore 中，不对外暴露）。
 */
data class WebDavConfigState(
    val fileUrl: String,
    val username: String,
    val allowHttp: Boolean,
    val hasPassword: Boolean,
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
 * @property lastLocalHash 上次同步时**本地**备份内容的 SHA-256（十六进制小写）。
 * @property lastRemoteHash 上次同步时**远端**内容的 SHA-256；用于兼容不返回 ETag 的服务器。
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

/** fetch 的结果：远端有文件返回内容 + 元信息，否则 [NotFound]。 */
sealed interface WebDavFetchResult {
    data class Found(val bytes: ByteArray, val meta: WebDavRemoteMeta) : WebDavFetchResult

    data object NotFound : WebDavFetchResult
}

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
