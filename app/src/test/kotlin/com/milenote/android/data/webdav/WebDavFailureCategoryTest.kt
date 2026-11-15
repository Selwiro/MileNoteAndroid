package com.milenote.android.data.webdav

import com.milenote.android.platform.webdav.WebDavCryptoException
import com.milenote.android.platform.webdav.WebDavException
import com.milenote.android.platform.webdav.WebDavHttpException
import com.milenote.android.platform.webdav.WebDavPreconditionFailedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同步失败归一类别（UX6）：只看异常类型 / HTTP 状态码，不看文案（文案会随界面语言变）。
 */
class WebDavFailureCategoryTest {

    @Test
    fun `http status codes map to auth permission and not found`() {
        assertEquals(WebDavFailureCategory.AUTH, classifyWebDavFailure(WebDavHttpException(401, "401")))
        assertEquals(WebDavFailureCategory.PERMISSION, classifyWebDavFailure(WebDavHttpException(403, "403")))
        assertEquals(WebDavFailureCategory.NOT_FOUND, classifyWebDavFailure(WebDavHttpException(404, "404")))
        // 409 无权限冲突 / 500 服务端错误：都归「其他」，由引擎按更细逻辑处理
        assertEquals(WebDavFailureCategory.OTHER, classifyWebDavFailure(WebDavHttpException(500, "500")))
    }

    @Test
    fun `network exceptions map to network`() {
        assertEquals(WebDavFailureCategory.NETWORK, classifyWebDavFailure(SocketTimeoutException("timeout")))
        assertEquals(WebDavFailureCategory.NETWORK, classifyWebDavFailure(UnknownHostException("dav.example.com")))
        assertEquals(WebDavFailureCategory.NETWORK, classifyWebDavFailure(ConnectException("refused")))
        assertEquals(WebDavFailureCategory.NETWORK, classifyWebDavFailure(IOException("broken pipe")))
    }

    @Test
    fun `crypto and precondition failures are separated`() {
        assertEquals(WebDavFailureCategory.ENCRYPTION, classifyWebDavFailure(WebDavCryptoException("bad password")))
        assertEquals(
            WebDavFailureCategory.OTHER,
            classifyWebDavFailure(WebDavPreconditionFailedException("precondition")),
        )
    }

    @Test
    fun `wrapped causes are unwrapped`() {
        val wrapped = WebDavException("sync failed", IOException("network down"))
        assertEquals(WebDavFailureCategory.NETWORK, classifyWebDavFailure(wrapped))
        val nested = WebDavException("outer", WebDavException("inner", WebDavHttpException(401, "401")))
        assertEquals(WebDavFailureCategory.AUTH, classifyWebDavFailure(nested))
    }

    @Test
    fun `unknown or null errors are other`() {
        assertEquals(WebDavFailureCategory.OTHER, classifyWebDavFailure(null))
        assertEquals(WebDavFailureCategory.OTHER, classifyWebDavFailure(IllegalStateException("?")))
        assertEquals(WebDavFailureCategory.OTHER, classifyWebDavFailure(WebDavException("plain")))
    }
}
