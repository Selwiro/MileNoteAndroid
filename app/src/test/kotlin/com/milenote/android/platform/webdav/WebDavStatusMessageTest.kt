package com.milenote.android.platform.webdav

import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP 状态码文案（审计 B28）：401 与 403 不能共用「认证失败」。
 *
 * - 401 = 用户名/应用密码不对，去改密码有用；
 * - 403 = 密码没错但目录不可写（只读共享、没有上传权限），去改密码没用。
 */
class WebDavStatusMessageTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    @Test
    fun `401 asks for credentials`() {
        val text = webDavStatusMessage(401)
        assertTrue(text, text.contains("认证失败"))
        assertTrue(text, text.contains("密码"))
        assertFalse("401 不应提到权限/只读：$text", text.contains("权限不足"))
        assertFalse("401 不应提到权限/只读：$text", text.contains("只读"))
    }

    @Test
    fun `403 points at permissions instead of the password`() {
        val text = webDavStatusMessage(403)
        assertTrue(text, text.contains("权限不足"))
        assertTrue(text, text.contains("只读"))
        assertFalse("403 不应再提认证失败：$text", text.contains("认证失败"))
    }

    @Test
    fun `401 and 403 have different English wording too`() {
        I18n.applyEnglish(true)
        val unauthorized = webDavStatusMessage(401)
        val forbidden = webDavStatusMessage(403)

        assertTrue(unauthorized, unauthorized.contains("Authentication failed"))
        assertTrue(forbidden, forbidden.contains("permission denied"))
        assertTrue("403 英文应提醒只读共享：$forbidden", forbidden.contains("read-only"))
        assertFalse(forbidden, forbidden.contains("Authentication failed"))
    }
}
