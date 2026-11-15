package com.milenote.android.platform.webdav

/** 一次 PUT 的条件请求参数：`If-Match` 优先，其次 `If-None-Match: *`，都没有表示无条件覆盖。 */
data class WebDavUploadCondition(
    val ifMatchEtag: String?,
    val ifNoneMatchAny: Boolean,
)

/**
 * 计算上传的条件请求头。
 *
 * **不能回退到「上次同步的 ETag」**：远端文件可能已经被删除（404），此时再发
 * `If-Match: <旧 ETag>` 按 RFC 必然 412，且每次重试都会重复同样的 412，同步会永久卡死。
 * 只有在本次同步**确实 fetch 到** `expectedEtag` 时才用 `If-Match`；拿不到就按
 * 「远端应不存在」发 `If-None-Match: *`，远端真不存在时才能安全新建。
 *
 * @param force 用户确认「用本地覆盖远端」或服务器不返回 ETag：无条件覆盖。
 * @param expectedEtag 本次同步刚 fetch 到的 ETag；null / 空白表示远端没有可信 ETag。
 */
fun webDavUploadCondition(force: Boolean, expectedEtag: String?): WebDavUploadCondition = when {
    force -> WebDavUploadCondition(ifMatchEtag = null, ifNoneMatchAny = false)
    !expectedEtag.isNullOrBlank() -> WebDavUploadCondition(ifMatchEtag = expectedEtag, ifNoneMatchAny = false)
    else -> WebDavUploadCondition(ifMatchEtag = null, ifNoneMatchAny = true)
}
