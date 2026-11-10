package com.milenote.android.data.update

import com.milenote.android.domain.update.isNewerVersion
import com.milenote.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** GitHub Release 里的版本信息。 */
data class UpdateInfo(
    val version: String,
    val pageUrl: String,
    val apkUrl: String?,
    val notes: String,
)

/** 检查更新的结果。 */
sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 从 GitHub Releases 检查最新版本（无需登录、无新增权限，只读公开 API）。
 *
 * 只做“检查 + 打开下载页”；APK 安装由系统浏览器/下载管理器完成，不申请安装未知应用权限。
 */
object UpdateChecker {

    // 用 releases 列表而不是 /latest：beta 期的 Release 会标成 prerelease，
    // /releases/latest 会直接 404；列表接口能同时覆盖正式版与预发布版。
    private const val API_URL =
        "https://api.github.com/repos/Selwiro/MileNoteAndroid/releases?per_page=20"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MileNoteAndroid")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        403, 429 -> tr("GitHub 暂时限制了访问，请稍后再试", "GitHub rate limit reached — try again later")
                        404 -> tr("GitHub 上还没有发布版本", "No releases published yet")
                        else -> tr("检查失败（HTTP %d）", "Check failed (HTTP %d)", response.code)
                    }
                    return@withContext UpdateCheckResult.Failed(message)
                }
                val body = response.body?.string().orEmpty()
                val releases = parseReleases(body)
                if (releases.isEmpty()) {
                    return@withContext UpdateCheckResult.Failed(
                        tr("GitHub 上还没有发布版本", "No releases published yet"),
                    )
                }
                val info = releases.fold(releases.first()) { best, next ->
                    if (isNewerVersion(next.version, best.version)) next else best
                }
                if (isNewerVersion(info.version, currentVersion)) {
                    UpdateCheckResult.Available(info)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (t: Throwable) {
            UpdateCheckResult.Failed(
                tr(
                    "网络不可用或无法访问 GitHub：%s",
                    "Network unavailable or GitHub unreachable: %s",
                    t.message ?: tr("未知错误", "Unknown error"),
                ),
            )
        }
    }

    /** 从 GitHub releases 列表 JSON 里取每个 Release 的版本、页面、APK 资产与更新说明。 */
    internal fun parseReleases(text: String): List<UpdateInfo> = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val root = element.jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val pageUrl = root["html_url"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tag.isBlank() || pageUrl.isBlank()) return@mapNotNull null
            val version = tag.removePrefix("v").removePrefix("V")
            val notes = cleanNotes(root["body"]?.jsonPrimitive?.content.orEmpty())
            val apkUrl: String? = runCatching<String?> {
                root["assets"]?.jsonArray
                    ?.mapNotNull { it.jsonObject }
                    ?.firstOrNull { asset ->
                        asset["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true
                    }
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
            UpdateInfo(version = version, pageUrl = pageUrl, apkUrl = apkUrl, notes = notes)
        }
    }.getOrDefault(emptyList())

    /**
     * 整理 GitHub Release 正文，供检查更新弹层展示。
     *
     * - 去掉正文首行给官网读取的 `versionCode: n` 机器标记（不是给用户看的）；
     * - 去掉成行的 HTML 注释；
     * - 不做字符数硬截断：弹层改成可滚动 + Markdown 渲染后，截断只会把语法切一半。
     */
    internal fun cleanNotes(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace("\r\n", "\n")
            .split('\n')
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("versionCode:", ignoreCase = true) ||
                    (trimmed.startsWith("<!--") && trimmed.endsWith("-->"))
            }
            .joinToString("\n")
            .trim()
    }
}
