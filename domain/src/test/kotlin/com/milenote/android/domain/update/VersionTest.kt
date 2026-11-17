package com.milenote.android.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `newer versions compare by numeric segments`() {
        assertTrue(isNewerVersion("0.31.0", "0.30.6"))
        assertTrue(isNewerVersion("v0.31.0", "0.30.6"))
        assertTrue(isNewerVersion("1.0.0", "0.30.6"))
        assertTrue(isNewerVersion("0.30.10", "0.30.6"))
        assertFalse(isNewerVersion("0.30.6", "0.30.6"))
        assertFalse(isNewerVersion("v0.30.5", "0.30.6"))
        assertFalse(isNewerVersion("0.30.6-beta", "0.30.6"))
    }

    @Test
    fun `release outranks its prereleases and rc numbers order`() {
        // 1.0.0-rc5 用户必须能被提示升到正式版 1.0.0（旧实现把 rc5 的 5 当第四段数字，判断反了）。
        assertTrue(isNewerVersion("1.0.0", "1.0.0-rc5"))
        assertTrue(isNewerVersion("v1.0.0", "1.0.0-rc5"))
        assertFalse(isNewerVersion("1.0.0-rc5", "1.0.0"))
        assertTrue(isNewerVersion("1.0.0-rc6", "1.0.0-rc5"))
        assertFalse(isNewerVersion("1.0.0-rc5", "1.0.0-rc6"))
        assertFalse(isNewerVersion("1.0.0-rc5", "1.0.0-rc5"))
        assertTrue(isNewerVersion("1.0.0-rc5", "0.44.1"))
        // 后缀不是数字（-beta）也能比：同号预发布之间不互相判新。
        assertFalse(isNewerVersion("1.0.0-beta", "1.0.0-rc5"))
        assertTrue(isNewerVersion("1.0.1-beta", "1.0.0"))
    }
}
