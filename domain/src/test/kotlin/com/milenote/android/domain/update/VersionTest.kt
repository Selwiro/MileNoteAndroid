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
}
