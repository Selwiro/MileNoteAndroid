package com.milenote.android.platform.webdav

import com.milenote.android.data.webdav.WebDavRemoteMeta
import com.milenote.android.i18n.tr
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/**
 * WebDAV 同步失败：`message` 为可直接展示给用户的中文提示。
 *
 * 约定：所有异常信息**不得**包含密码明文。
 */
open class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 带 HTTP 状态码的异常（内部使用，便于对 409/412 等做差异化处理）。 */
internal class WebDavHttpException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : WebDavException(message, cause)

/** 一次 WebDAV 请求所需的凭据（密码只在内存中使用，不写日志）。 */
data class WebDavCredentials(
    val url: String,
    val username: String,
    val password: String,
    val allowHttp: Boolean,
)

/** GET 成功的内容 + 元信息。 */
data class WebDavContent(
    val bytes: ByteArray,
    val meta: WebDavRemoteMeta,
)

/**
 * WebDAV 客户端抽象（便于测试注入假实现）。
 *
 * 只覆盖同步所需的三个操作：读元信息（HEAD）、读内容（GET）、写内容（PUT）。
 * 所有方法失败时抛 [WebDavException]（中文消息）；`404` 不抛：
 * - [head] 返回 `exists = false` 的元信息；
 * - [get] 返回 `null`（调用方映射为 `WebDavFetchResult.NotFound`）。
 */
interface WebDavClient {
    /** HEAD 读取远端元信息；服务器不支持 HEAD（405/501）时内部退化为 `GET Range: bytes=0-0`。 */
    suspend fun head(credentials: WebDavCredentials): WebDavRemoteMeta

    /** GET 读取内容；不存在返回 `null`。 */
    suspend fun get(credentials: WebDavCredentials): WebDavContent?

    /**
     * PUT 上传内容。
     *
     * @param ifMatchEtag 非空时发送 `If-Match`（值需为**规范化后**的 ETag，不带引号）；
     * @param ifNoneMatchAny 为 true 时发送 `If-None-Match: *`（仅当远端应不存在时）。
     * 两者都为 null 表示无条件覆盖（`force`）。
     * 409（父目录不存在）时自动逐级 MKCOL 后重试一次。
     */
    suspend fun put(
        credentials: WebDavCredentials,
        bytes: ByteArray,
        ifMatchEtag: String?,
        ifNoneMatchAny: Boolean,
    ): WebDavRemoteMeta
}

/** URL 校验与目录 URL 推导（纯 Kotlin，可单元测试）。 */
object WebDavUrl {

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /**
     * 校验用户填写的 WebDAV **文件** URL。
     *
     * 规则：必须是 http/https 绝对地址、必须有主机名、必须形如「目录/文件名」（不能以 `/` 结尾）；
     * `http://` 仅在 [allowHttp] 为 true 时允许。
     *
     * @return 去除首尾空白后的地址。
     * @throws WebDavException 校验失败（中文提示）。
     */
    fun validate(fileUrl: String, allowHttp: Boolean): String {
        val url = fileUrl.trim()
        if (url.isEmpty()) throw WebDavException(tr("请填写 WebDAV 文件地址", "Enter the WebDAV file URL"))
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: throw WebDavException(tr("地址格式不正确，请填写完整的 http(s) 文件地址", "Invalid URL — enter a full http(s) file URL"))
        if (scheme != "http" && scheme != "https") {
            throw WebDavException(tr("只支持 http 或 https 地址", "Only http or https URLs are supported"))
        }
        if (scheme == "http" && !allowHttp) {
            throw WebDavException(tr("请使用 HTTPS 地址，或在设置中允许 HTTP", "Use an HTTPS URL, or allow HTTP in Settings"))
        }
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw WebDavException(
                tr(
                    "地址格式不正确，请检查是否有多余空格或特殊字符",
                    "Invalid URL — check for extra spaces or special characters",
                ),
                e,
            )
        }
        if (uri.host.isNullOrBlank()) {
            throw WebDavException(tr("地址缺少服务器名，请填写完整的文件地址", "URL is missing the server name — enter a full file URL"))
        }
        val path = uri.path.orEmpty()
        if (path.isBlank()) {
            throw WebDavException(
                tr(
                    "请填写完整的文件地址（需包含文件名，例如 /dav/milenote.json）",
                    "Enter a full file URL (must include a file name, e.g. /dav/milenote.json)",
                ),
            )
        }
        if (path.endsWith("/")) {
            throw WebDavException(
                tr(
                    "请填写完整的文件地址（以文件名结尾，不能以 / 结尾）",
                    "Enter a full file URL (must end with a file name, not /)",
                ),
            )
        }
        return url
    }

    /**
     * 由文件 URL 推导需要保证存在的**父目录** URL 列表，按从浅到深排列。
     *
     * 例：`https://host/a/b/c.json` → `[https://host/a/, https://host/a/b/]`。
     */
    fun ancestorDirectories(fileUrl: String): List<String> {
        val uri = try {
            URI(fileUrl)
        } catch (e: Exception) {
            return emptyList()
        }
        val rawPath = uri.rawPath.orEmpty()
        if (rawPath.isEmpty() || !rawPath.startsWith("/")) return emptyList()
        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return emptyList()
        val base = buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) append(':').append(uri.port)
        }
        val dirs = mutableListOf<String>()
        val accumulated = StringBuilder()
        for (segment in segments.dropLast(1)) {
            accumulated.append('/').append(segment)
            dirs += base + accumulated + "/"
        }
        return dirs
    }
}

/** ETag 规范化工具：统一处理 `W/` 弱标记与引号，便于持久化与弱比较。 */
object Etag {

    /** 规范化：去空白、去 `W/` 前缀、去外层双引号；空串返回 null。 */
    fun normalize(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("W/", ignoreCase = true)) value = value.substring(2).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        return value.ifBlank { null }
    }

    /** 回写为 HTTP 头值：补上外层双引号（If-Match 必须是强/弱 ETag 形式）。 */
    fun toHeader(normalized: String?): String? {
        val value = normalized?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.startsWith("\"")) value else "\"$value\""
    }

    /** 弱比较（忽略大小写与引号差异）。 */
    fun weakEquals(a: String?, b: String?): Boolean =
        normalize(a)?.equals(normalize(b), ignoreCase = true) == true
}

/**
 * 基于 OkHttp 的 WebDAV 客户端。
 *
 * 特性：
 * - Basic Auth（每次请求携带，密码只存在于内存字符串）；
 * - 超时：connect 15s / read 20s / write 20s；
 * - ETag 规范化，条件请求支持 `If-Match` 与 `If-None-Match: *`；
 * - `409 Conflict` 时逐级 `MKCOL` 父目录（忽略 405 已存在）后重试一次；
 * - 失败统一映射为中文 [WebDavException]。
 *
 * 线程：所有方法内部切换到 IO 线程，可在任意协程上下文调用。
 */
class OkHttpWebDavClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WebDavClient {

    override suspend fun head(credentials: WebDavCredentials): WebDavRemoteMeta = withContext(io) {
        validate(credentials)
        execute("HEAD") {
            val request = baseRequest(credentials).head().build()
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> metaFrom(response)
                    404, 410 -> notFoundMeta()
                    405, 501 -> headViaRangeGet(credentials)
                    else -> throw statusException(response.code)
                }
            }
        }
    }

    override suspend fun get(credentials: WebDavCredentials): WebDavContent? = withContext(io) {
        validate(credentials)
        execute("GET") {
            val request = baseRequest(credentials).get().build()
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> WebDavContent(bytes = response.body.bytes(), meta = metaFrom(response))
                    404, 410 -> null
                    else -> throw statusException(response.code)
                }
            }
        }
    }

    override suspend fun put(
        credentials: WebDavCredentials,
        bytes: ByteArray,
        ifMatchEtag: String?,
        ifNoneMatchAny: Boolean,
    ): WebDavRemoteMeta = withContext(io) {
        validate(credentials)
        try {
            putOnce(credentials, bytes, ifMatchEtag, ifNoneMatchAny)
        } catch (e: WebDavHttpException) {
            if (e.status != 409) throw e
            // 父目录不存在：逐级创建后重试一次（服务器可能不允许 MKCOL，失败则抛原始 409 提示）。
            ensureDirectories(credentials)
            putOnce(credentials, bytes, ifMatchEtag, ifNoneMatchAny)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------------------

    private fun validate(credentials: WebDavCredentials) {
        // 再校验一次，防止调用方绕过 Repository.save（例如配置被直接注入）。
        WebDavUrl.validate(credentials.url, credentials.allowHttp)
    }

    private fun baseRequest(credentials: WebDavCredentials): Request.Builder {
        val builder = Request.Builder().url(credentials.url)
        if (credentials.username.isNotEmpty() || credentials.password.isNotEmpty()) {
            builder.header(
                "Authorization",
                Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8),
            )
        }
        return builder.header("Accept", "*/*")
    }

    private fun notFoundMeta(): WebDavRemoteMeta =
        WebDavRemoteMeta(exists = false, etag = null, size = 0L, lastModifiedMs = null)

    /** HEAD 不受支持（405/501）时，用 `GET Range: bytes=0-0` 退化探测存在性与元信息。 */
    private fun headViaRangeGet(credentials: WebDavCredentials): WebDavRemoteMeta {
        val request = baseRequest(credentials)
            .get()
            .header("Range", "bytes=0-0")
            .build()
        return http.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> {
                    val meta = metaFrom(response)
                    // 206 时 Content-Length 只是 1 字节，用 Content-Range 的总长度还原真实大小。
                    val total = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.trim()
                        ?.toLongOrNull()
                    if (response.code == 206 && total != null && total >= 0) {
                        meta.copy(size = total)
                    } else {
                        meta
                    }
                }
                404, 410 -> notFoundMeta()
                else -> throw statusException(response.code)
            }
        }
    }

    private fun putOnce(
        credentials: WebDavCredentials,
        bytes: ByteArray,
        ifMatchEtag: String?,
        ifNoneMatchAny: Boolean,
    ): WebDavRemoteMeta {
        val body = RequestBody.create(JSON_MEDIA_TYPE, bytes, 0, bytes.size)
        val request = baseRequest(credentials)
            .put(body)
            .apply {
                Etag.toHeader(ifMatchEtag)?.let { header("If-Match", it) }
                if (ifNoneMatchAny) header("If-None-Match", "*")
            }
            .build()
        return http.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> metaFrom(response, fallbackSize = bytes.size.toLong())
                409 -> throw WebDavHttpException(
                    409,
                    tr(
                        "远端目录不存在或不允许创建，请检查 WebDAV 地址是否正确",
                        "Remote directory is missing or cannot be created — check the WebDAV URL",
                    ),
                )
                412 -> throw WebDavException(
                    tr("远端文件已被其他设备修改，请重新同步", "Remote file was modified by another device — sync again"),
                )
                else -> throw statusException(response.code)
            }
        }
    }

    /** 逐级创建父目录（MKCOL）；405/301/409 视为已存在或无需创建，忽略。 */
    private fun ensureDirectories(credentials: WebDavCredentials) {
        for (directoryUrl in WebDavUrl.ancestorDirectories(credentials.url)) {
            val request = baseRequest(credentials)
                .url(directoryUrl)
                .method("MKCOL", null)
                .build()
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299, 301, 302, 405, 409 -> Unit
                    else -> throw statusException(response.code)
                }
            }
        }
    }

    private fun metaFrom(response: Response, fallbackSize: Long? = null): WebDavRemoteMeta {
        // PUT 响应里的 Content-Length 是「响应体长度」（通常为 0），不是文件大小；
        // 因此调用方传入的 fallbackSize（实际上传字节数）优先。
        val size = fallbackSize
            ?: response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            ?: response.body.contentLength().takeIf { it >= 0 }
            ?: 0L
        return WebDavRemoteMeta(
            exists = true,
            etag = Etag.normalize(response.header("ETag")),
            size = size,
            lastModifiedMs = parseHttpDateMillis(response.header("Last-Modified")),
        )
    }

    /** 统一执行并把 IOException 映射为中文 [WebDavException]。 */
    private inline fun <T> execute(operation: String, block: () -> T): T = try {
        block()
    } catch (e: WebDavException) {
        throw e
    } catch (e: IOException) {
        throw mapNetworkError(operation, e)
    }

    private fun mapNetworkError(operation: String, error: IOException): WebDavException = when (error) {
        is SocketTimeoutException -> WebDavException(
            networkMessage(operation, "连接服务器超时，请检查网络后重试", "connection timed out — check your network and retry"),
            error,
        )
        is UnknownHostException -> WebDavException(
            networkMessage(operation, "无法解析服务器地址，请检查地址和网络", "cannot resolve the server address — check the URL and network"),
            error,
        )
        is SSLException -> WebDavException(
            networkMessage(
                operation,
                "HTTPS 证书校验失败，请检查服务器证书或改用正确地址",
                "HTTPS certificate validation failed — check the server certificate or use the correct URL",
            ),
            error,
        )
        else -> WebDavException(
            networkMessage(
                operation,
                "网络错误（${error.message ?: "请稍后重试"}）",
                "network error (${error.message ?: "try again later"})",
            ),
            error,
        )
    }

    /** 网络错误前缀：中文 `HEAD 失败：…` / 英文 `HEAD failed: …`。 */
    private fun networkMessage(operation: String, zh: String, en: String): String =
        "$operation ${tr("失败：$zh", "failed: $en")}"

    /** HTTP 状态码 → 中文异常。 */
    private fun statusException(code: Int): WebDavException = when {
        code == 401 || code == 403 -> WebDavHttpException(
            code,
            tr("认证失败，请检查用户名和应用密码是否正确", "Authentication failed — check the username and app password"),
        )
        code == 404 || code == 410 -> WebDavHttpException(
            code,
            tr("远端文件不存在", "Remote file does not exist"),
        )
        code == 405 || code == 501 -> WebDavHttpException(
            code,
            tr(
                "服务器不支持该操作（HTTP %d），请确认地址指向 WebDAV 文件",
                "Server does not support this operation (HTTP %d) — make sure the URL points to a WebDAV file",
                code,
            ),
        )
        code == 409 -> WebDavHttpException(
            code,
            tr("远端目录不存在或冲突（HTTP 409）", "Remote directory is missing or conflicts (HTTP 409)"),
        )
        code == 412 -> WebDavHttpException(
            code,
            tr("远端文件已被其他设备修改，请重新同步", "Remote file was modified by another device — sync again"),
        )
        code == 413 -> WebDavHttpException(
            code,
            tr("备份文件过大，服务器拒绝接收", "Backup file is too large — the server rejected it"),
        )
        code == 423 -> WebDavHttpException(
            code,
            tr("远端文件被服务器锁定，请稍后重试", "Remote file is locked by the server — try again later"),
        )
        code == 507 -> WebDavHttpException(
            code,
            tr("远端存储空间不足", "Remote storage is full"),
        )
        code in 500..599 -> WebDavHttpException(
            code,
            tr("服务器错误（HTTP %d），请稍后重试", "Server error (HTTP %d) — try again later", code),
        )
        else -> WebDavHttpException(
            code,
            tr("服务器返回异常状态（HTTP %d），请稍后重试", "Server returned an unexpected status (HTTP %d) — try again later", code),
        )
    }

    private fun parseHttpDateMillis(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    companion object {

        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

        /** 默认 OkHttp 客户端：connect 15s / read 20s / write 20s。 */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
