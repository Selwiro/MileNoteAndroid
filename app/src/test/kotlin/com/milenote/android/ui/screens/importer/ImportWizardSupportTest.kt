package com.milenote.android.ui.screens.importer

import com.milenote.android.domain.backup.BackupException
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 导入向导的支撑纯函数：备份摘要解析、按动力类型过滤来源 / 字段顺序、编辑弹层的分类选项。
 *
 * 这些函数被 [ImportWizardState] 与界面直接调用，因此语言无关的断言为主
 * （分类 / 字段键是数据值，不翻译）。
 */
class ImportWizardSupportTest {

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    // ---------- inspectBackup ----------

    @Test
    fun `inspect backup counts v2 garage cars records and reminders`() {
        val text = """
            {"version":2,"activeId":"c1","theme":"blue","cars":[
              {"version":1,"id":"c1","vehicle":{"currentKm":0},
               "records":[{"id":"r1","date":"2026-01-05","category":"停车费","amount":12.5,"kind":"expense"}],
               "reminders":[]},
              {"version":1,"id":"c2","vehicle":{"currentKm":0},
               "records":[],"reminders":[{"id":"t1","title":"保养","dueDate":"2027-01-01","done":false}]}
            ]}
        """.trimIndent()

        val preview = inspectBackup(text)

        assertEquals(2, preview.version)
        assertEquals(2, preview.cars)
        assertEquals(1, preview.records)
        assertEquals(1, preview.reminders)
    }

    @Test
    fun `inspect backup reports legacy single car backups as version 1`() {
        val text = """
            {"version":1,"id":"legacy","vehicle":{"currentKm":0},
             "records":[{"id":"r1","date":"2026-01-05","category":"停车费","amount":12.5,"kind":"expense"}],
             "reminders":[]}
        """.trimIndent()

        val preview = inspectBackup(text)

        assertEquals(1, preview.version)
        assertEquals(1, preview.cars)
        assertEquals(1, preview.records)
        assertEquals(0, preview.reminders)
    }

    @Test
    fun `inspect backup rejects malformed json`() {
        assertThrows(BackupException::class.java) { inspectBackup("{not json") }
    }

    @Test
    fun `inspect backup rejects json without a version`() {
        assertThrows(BackupException::class.java) { inspectBackup("""{"cars":[]}""") }
    }

    // ---------- 来源与字段（按动力类型） ----------

    @Test
    fun `manual import sources hide bear for ev`() {
        val fuel = manualImportSources(ev = false)
        val ev = manualImportSources(ev = true)

        assertTrue(fuel.contains(ImportSource.BEAR))
        assertTrue(fuel.contains(ImportSource.QIANJI))
        assertFalse(ev.contains(ImportSource.BEAR))
        assertTrue(ev.contains(ImportSource.QIANJI))
    }

    // ---------- 来源格式限制（内容判定） ----------

    private val xlsxBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)
    private val xlsBytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
    private val jsonBytes = """[{"key":"qj1","date":"2026-01-05","money":12.5}]""".toByteArray()
    private val csvBytes = "日期,金额\n2026-01-05,12".toByteArray()

    @Test
    fun `bear import only accepts xls workbooks`() {
        assertNull(sourceFileError(ImportSource.BEAR, xlsBytes))
        assertNotNull(sourceFileError(ImportSource.BEAR, xlsxBytes))
        assertNotNull(sourceFileError(ImportSource.BEAR, jsonBytes))
        assertNotNull(sourceFileError(ImportSource.BEAR, csvBytes))
    }

    @Test
    fun `qianji import accepts xlsx or json content whatever the extension`() {
        assertNull(sourceFileError(ImportSource.QIANJI, xlsxBytes))
        assertNull(sourceFileError(ImportSource.QIANJI, jsonBytes))
        // 钱迹的 JSON 导出也可能存成 .txt：按内容识别，BOM + 前后空白也接受。
        assertNull(sourceFileError(ImportSource.QIANJI, "\uFEFF  [{\"key\":\"qj1\"}]".toByteArray()))
        assertNotNull(sourceFileError(ImportSource.QIANJI, xlsBytes))
        assertNotNull(sourceFileError(ImportSource.QIANJI, csvBytes))
    }

    @Test
    fun `backup import keeps content based validation`() {
        assertNull(sourceFileError(ImportSource.BACKUP, csvBytes))
    }

    @Test
    fun `import field order hides the other powertrain fields`() {
        val fuel = importFieldOrder(ev = false)
        val ev = importFieldOrder(ev = true)

        assertTrue(fuel.contains("liters"))
        assertTrue(fuel.contains("fuelGrade"))
        assertFalse(fuel.contains("kwh"))
        assertTrue(ev.contains("kwh"))
        assertFalse(ev.contains("liters"))
        assertFalse(ev.contains("fuelGrade"))
        // 两边共有的字段顺序保持一致（取 fuel 的前几个公共字段）。
        assertTrue(fuel.indexOf("date") < fuel.indexOf("odometer"))
        assertTrue(ev.indexOf("date") < ev.indexOf("odometer"))
    }

    @Test
    fun `import field labels switch units between fuel and ev`() {
        val fuel = importFieldLabels(ev = false)
        val ev = importFieldLabels(ev = true)

        assertTrue(fuel.containsKey("liters"))
        assertFalse(fuel.containsKey("kwh"))
        assertTrue(ev.containsKey("kwh"))
        assertFalse(ev.containsKey("liters"))
        assertFalse(ev.containsKey("fuelGrade"))
        assertTrue(fuel.getValue("unitPrice").isNotEmpty())
        assertTrue(ev.getValue("unitPrice").isNotEmpty())
        // 中文下燃油用「油价」、纯电用「电价」。
        assertTrue(fuel.getValue("unitPrice").contains("油"))
        assertTrue(ev.getValue("unitPrice").contains("电"))
    }

    // ---------- 编辑弹层的分类选项 ----------

    @Test
    fun `edit category options include standard custom and current category`() {
        val options = editCategoryOptions(
            current = "改装",
            customCategories = listOf("改装", "洗车卡"),
        )

        assertTrue(options.any { it.first == "停车费" })
        assertTrue(options.any { it.first == "改装" })
        assertTrue(options.any { it.first == "洗车卡" })
        // 值是数据值，不做翻译。
        assertEquals("改装", options.first { it.first == "改装" }.first)
        // 当前值即便不在自定义分类里也保留，避免编辑时丢分类。
        val orphan = editCategoryOptions(current = "旧分类", customCategories = emptyList())
        assertTrue(orphan.any { it.first == "旧分类" })
    }
}
