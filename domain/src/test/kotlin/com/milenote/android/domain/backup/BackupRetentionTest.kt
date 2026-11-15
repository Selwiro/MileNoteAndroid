package com.milenote.android.domain.backup

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F4：固定目录备份的文件名规则与保留策略。 */
class BackupRetentionTest {

    @Test
    fun `backup file name matches the export naming`() {
        assertEquals("里程志备份-2026-11-15.json", backupFileName(LocalDate.of(2026, 11, 15)))
        assertTrue(isBackupFileName("里程志备份-2026-11-15.json"))
        assertTrue(isBackupFileName("里程志备份-2026-01-02.json"))
    }

    @Test
    fun `other json files are never treated as backups`() {
        assertFalse(isBackupFileName("milenote-2026-11-15.json"))
        assertFalse(isBackupFileName("里程志备份-2026-11-15.json.bak"))
        assertFalse(isBackupFileName("里程志备份-2026-11-15(1).json"))
        assertFalse(isBackupFileName("里程志-2026-11-15.json"))
        assertFalse(isBackupFileName("里程志备份-2026-13-40.json"))
    }

    @Test
    fun `backup date parses valid names only`() {
        assertEquals(LocalDate.of(2026, 11, 15), backupDate("里程志备份-2026-11-15.json"))
        assertNull(backupDate("里程志备份-2026-02-30.json"))
        assertNull(backupDate("随机文件.json"))
    }

    @Test
    fun `keeps the newest n backups and returns older ones oldest first`() {
        val names = listOf(
            "里程志备份-2026-11-01.json",
            "里程志备份-2026-11-03.json",
            "里程志备份-2026-11-02.json",
            "里程志备份-2026-11-05.json",
            "里程志备份-2026-11-04.json",
        )
        assertEquals(
            listOf("里程志备份-2026-11-01.json", "里程志备份-2026-11-02.json"),
            backupsToDelete(names, keep = 3),
        )
    }

    @Test
    fun `ignores unrelated files and keeps them untouched`() {
        val names = listOf(
            "照片.jpg",
            "其他备份.json",
            "里程志备份-2026-11-10.json",
            "里程志备份-2026-11-11.json",
        )
        assertEquals(listOf("里程志备份-2026-11-10.json"), backupsToDelete(names, keep = 1))
    }

    @Test
    fun `keep below one still keeps the newest`() {
        val names = listOf("里程志备份-2026-11-10.json", "里程志备份-2026-11-11.json")
        assertEquals(listOf("里程志备份-2026-11-10.json"), backupsToDelete(names, keep = 0))
        assertEquals(emptyList<String>(), backupsToDelete(names, keep = 5))
        assertEquals(emptyList<String>(), backupsToDelete(emptyList<String>(), keep = 3))
    }

    @Test
    fun `duplicate dates count once`() {
        val names = listOf(
            "里程志备份-2026-11-11.json",
            "里程志备份-2026-11-11.json",
            "里程志备份-2026-11-10.json",
        )
        assertEquals(listOf("里程志备份-2026-11-10.json"), backupsToDelete(names, keep = 1))
    }
}
