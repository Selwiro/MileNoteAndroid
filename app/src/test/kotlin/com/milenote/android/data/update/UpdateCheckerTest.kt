package com.milenote.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `parse latest release with apk asset`() {
        val json = """
            [{
              "tag_name": "v0.31.0",
              "html_url": "https://github.com/Selwiro/MileNoteAndroid/releases/tag/v0.31.0",
              "body": "更新说明",
              "assets": [
                {"name": "milenote-0.31.0-android.apk",
                 "browser_download_url": "https://example.com/milenote-0.31.0-android.apk"},
                {"name": "milenote-0.31.0-android.apk.sha256",
                 "browser_download_url": "https://example.com/sha256"}
              ]
            }]
        """.trimIndent()
        val info = UpdateChecker.parseReleases(json).single()
        assertEquals("0.31.0", info.version)
        assertEquals("https://github.com/Selwiro/MileNoteAndroid/releases/tag/v0.31.0", info.pageUrl)
        assertEquals("https://example.com/milenote-0.31.0-android.apk", info.apkUrl)
        assertEquals("更新说明", info.notes)
    }

    @Test
    fun `parse release without apk falls back to page url`() {
        val info = UpdateChecker.parseReleases(
            """[{"tag_name":"0.31.0","html_url":"https://example.com/release","assets":[]}]""",
        ).single()
        assertNull(info.apkUrl)
        assertEquals("https://example.com/release", info.pageUrl)
    }
}
