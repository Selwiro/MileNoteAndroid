package com.milenote.android.data.webdav

import com.milenote.android.platform.webdav.WebDavCryptoException
import com.milenote.android.platform.webdav.WebDavHttpException
import com.milenote.android.platform.webdav.WebDavPreconditionFailedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 同步失败的归一类别（UX6）。
 *
 * 「我的」页只写「同步失败」时用户不知道该干什么；这里把异常折成几类可行动的失败：
 * 认证、权限、文件不存在、网络、加密密码，其余归 [OTHER]。界面据此给一句说明 + 一个动作按钮。
 */
enum class WebDavFailureCategory {
    /** 401：用户名 / 应用密码不对。 */
    AUTH,

    /** 403：目录不可写、共享只读。 */
    PERMISSION,

    /** 404：远端文件不存在（可能被别的设备删了）。 */
    NOT_FOUND,

    /** 断网、超时、域名解析失败。 */
    NETWORK,

    /** 加密密码不对 / 远端已加密但本机没有密码。 */
    ENCRYPTION,

    /** 其它（含 412 冲突重试失败、服务端 5xx 等）。 */
    OTHER,
}

/**
 * 从异常链归一同步失败类别（纯函数，单测直接覆盖）。
 *
 * 只看**类型 + HTTP 状态码**，不看文案：异常文案会随界面语言变化，按文案判类会漂。
 * 异常被包装过（`cause`）也继续往下找，最多看 4 层，避免异常链成环。
 */
fun classifyWebDavFailure(error: Throwable?): WebDavFailureCategory {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 4) {
        when (current) {
            is WebDavHttpException -> return when (current.status) {
                401 -> WebDavFailureCategory.AUTH
                403 -> WebDavFailureCategory.PERMISSION
                404 -> WebDavFailureCategory.NOT_FOUND
                else -> WebDavFailureCategory.OTHER
            }
            is WebDavCryptoException -> return WebDavFailureCategory.ENCRYPTION
            is WebDavPreconditionFailedException -> return WebDavFailureCategory.OTHER
            is SocketTimeoutException, is UnknownHostException, is ConnectException ->
                return WebDavFailureCategory.NETWORK
            // WebDavException 不是 IOException；这里只兜真正的 IO 失败（连接被重置等）。
            is IOException -> return WebDavFailureCategory.NETWORK
            else -> {}
        }
        current = current.cause
        depth++
    }
    return WebDavFailureCategory.OTHER
}
