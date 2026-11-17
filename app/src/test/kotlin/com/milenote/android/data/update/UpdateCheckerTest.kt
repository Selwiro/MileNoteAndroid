package com.milenote.android.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `notes drop versionCode marker and keep full markdown body`() {
        // 正文首行 versionCode 是给官网读的机器标记，不应出现在给用户看的发版说明里；
        // 同时说明不能再按 600 字硬截断（截断会把 Markdown 语法切一半）。
        val longBody = buildString {
            append("versionCode: 94\n\n## 本次更新\n- 第一条说明\n")
            repeat(40) { append("- 第 ${it + 1} 条较长的更新说明，用来验证没有按字数截断。\n") }
        }
        val json = """[{"tag_name":"v0.37.0","html_url":"https://example.com/release",
            "body":${escapeJson(longBody)}}]"""

        val info = UpdateChecker.parseReleases(json).single()

        assertTrue(info.notes.startsWith("## 本次更新"))
        assertFalse(info.notes.contains("versionCode"))
        assertTrue("说明被截断了：${info.notes.length}", info.notes.length > 600)
    }

    @Test
    fun `notes strip html comments and blank edges`() {
        val json = """[{"tag_name":"v0.37.0","html_url":"https://example.com/release",
            "body":"\n\n<!-- 维护者备注 -->\n## 本次更新\n- 一条\n"}]"""
        assertEquals("## 本次更新\n- 一条", UpdateChecker.parseReleases(json).single().notes)
    }

    private fun escapeJson(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    // ---------- 渠道筛选（1.0 审查 #14） ----------

    private fun release(version: String, prerelease: Boolean = false, draft: Boolean = false) = UpdateInfo(
        version = version,
        pageUrl = "https://example.com/$version",
        apkUrl = null,
        notes = "",
        prerelease = prerelease,
        draft = draft,
    )

    @Test
    fun `stable build never gets offered a newer prerelease`() {
        val releases = listOf(release("1.0.0"), release("1.1.0-beta", prerelease = true))

        val selected = UpdateChecker.selectUpdate(releases, "1.0.0")

        assertEquals("1.0.0", selected?.version)
        assertFalse(com.milenote.android.domain.update.isNewerVersion(selected!!.version, "1.0.0"))
    }

    @Test
    fun `beta build can still upgrade to a newer prerelease`() {
        val releases = listOf(release("0.44.1"), release("1.0.0"), release("1.1.0-beta", prerelease = true))

        val selected = UpdateChecker.selectUpdate(releases, "0.44.1")

        assertEquals("1.1.0-beta", selected?.version)
    }

    @Test
    fun `drafts are ignored`() {
        val releases = listOf(release("1.2.0", draft = true), release("1.0.1"))

        assertEquals("1.0.1", UpdateChecker.selectUpdate(releases, "1.0.0")?.version)
    }

    @Test
    fun `parse marks prerelease and draft flags`() {
        val json = """
            [{"tag_name":"v1.1.0-beta","html_url":"https://example.com/b","prerelease":true,"draft":false,"assets":[]},
             {"tag_name":"v1.2.0","html_url":"https://example.com/d","prerelease":false,"draft":true,"assets":[]}]
        """.trimIndent()
        val parsed = UpdateChecker.parseReleases(json)

        assertTrue(parsed.first { it.version == "1.1.0-beta" }.prerelease)
        assertTrue(parsed.first { it.version == "1.2.0" }.draft)
    }

    @Test
    fun `prerelease channel detection`() {
        assertTrue(UpdateChecker.isPrereleaseVersion("0.44.1"))
        assertTrue(UpdateChecker.isPrereleaseVersion("1.1.0-beta"))
        assertFalse(UpdateChecker.isPrereleaseVersion("1.0.0"))
    }
}
