package com.milenote.android.data.settings

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份时间纯函数（[formatBackupAt]）与 [AppSettings.lastBackupAt] 默认值。
 *
 * 时间格式固定 `yyyy-MM-dd HH:mm`：4 位年、24 小时制、ASCII 数字，与界面语言无关，
 * 时区显式传入保证可复现。
 */
class BackupTimeTest {

    private val utc = ZoneOffset.UTC
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `format pads month day hour and minute to two digits`() {
        val millis = Instant.parse("2026-01-05T04:07:00Z").toEpochMilli()
        assertEquals("2026-01-05 04:07", formatBackupAt(millis, utc))
    }

    @Test
    fun `format keeps a fixed four digit year and handles epoch zero`() {
        assertEquals("2026-11-09 13:05", formatBackupAt(Instant.parse("2026-11-09T13:05:00Z").toEpochMilli(), utc))
        assertEquals("1970-01-01 00:00", formatBackupAt(0L, utc))
    }

    @Test
    fun `format uses 24 hour clock instead of am pm`() {
        assertEquals("2026-11-09 23:59", formatBackupAt(Instant.parse("2026-11-09T23:59:00Z").toEpochMilli(), utc))
        assertEquals("2026-11-09 15:00", formatBackupAt(Instant.parse("2026-11-09T15:00:00Z").toEpochMilli(), utc))
    }

    @Test
    fun `format converts the same instant into the requested zone`() {
        val millis = Instant.parse("2026-01-05T04:07:00Z").toEpochMilli()
        assertEquals("2026-01-05 12:07", formatBackupAt(millis, shanghai))
        assertEquals("2026-01-04 23:07", formatBackupAt(millis, newYork))
    }

    @Test
    fun `format follows daylight saving time of the requested zone`() {
        val summer = Instant.parse("2026-07-01T16:30:00Z").toEpochMilli()
        assertEquals("2026-07-01 12:30", formatBackupAt(summer, newYork))
        assertEquals("2026-07-02 00:30", formatBackupAt(summer, shanghai))
    }

    @Test
    fun `format stays ascii when the default locale uses other digits`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val millis = Instant.parse("2026-11-09T01:30:00Z").toEpochMilli()
            assertEquals("2026-11-09 09:30", formatBackupAt(millis, shanghai))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `format output always matches the fixed pattern`() {
        val text = formatBackupAt(Instant.parse("2026-03-08T06:09:00Z").toEpochMilli(), shanghai)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(text))
    }

    @Test
    fun `app settings defaults last backup at to null`() {
        assertNull(AppSettings().lastBackupAt)
        assertNull(AppSettings(darkMode = DarkMode.DARK).lastBackupAt)
    }

    @Test
    fun `app settings keeps an explicit last backup timestamp`() {
        val millis = Instant.parse("2026-11-09T01:30:00Z").toEpochMilli()
        assertEquals(millis, AppSettings(lastBackupAt = millis).lastBackupAt)
        assertEquals(millis, AppSettings().copy(lastBackupAt = millis).lastBackupAt)
    }
}
