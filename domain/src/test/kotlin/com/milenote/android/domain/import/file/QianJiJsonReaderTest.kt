package com.milenote.android.domain.import.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QianJiJsonReaderTest {

    private val reader = QianJiJsonReader()

    @Test
    fun `reads a standard qianji array with typed values`() {
        val json = """
            [
              {"key":"qj1","date":"2026-10-21 11:22:22","category":"停车费","type":"支出",
               "money":150.0,"currency":"CNY","asset":1700000000000,"remark":"包月",
               "hasbx":0,"username":null,"billflag":null,"sourceid":""},
              {"key":"qj2","date":"2026-10-20 13:50:58","category":"过路费","type":"支出",
               "money":69.0,"currency":"CNY","remark":"示例行程"}
            ]
        """.trimIndent()

        val data = reader.read(json.toByteArray(), "钱迹导出.txt")

        assertEquals(
            listOf(
                "key", "date", "category", "type", "money", "currency",
                "asset", "remark", "hasbx", "username", "billflag", "sourceid",
            ),
            data.headers,
        )
        assertEquals(2, data.rows.size)
        assertEquals("qj1", data.rows[0][0])
        assertEquals("2026-10-21 11:22:22", data.rows[0][1])
        assertEquals(150.0, data.rows[0][4])
        assertEquals("CNY", data.rows[0][5])
        assertEquals(1700000000000.0, data.rows[0][6])
        assertEquals(0.0, data.rows[0][8])
        assertNull(data.rows[0][9])
        assertNull(data.rows[0][10])
        assertEquals("", data.rows[0][11])
        // 第二条缺 asset/username 等字段，补 null。
        assertNull(data.rows[1][6])
        assertEquals(69.0, data.rows[1][4])
        assertEquals("钱迹", data.activeSheet)
        assertEquals(listOf("钱迹"), data.sheetNames)
        assertEquals("钱迹导出.txt", data.fileName)
    }

    @Test
    fun `union headers keep first appearance order when rows miss fields`() {
        val json = """[{"key":"a","date":"2026-10-01 10:00:00"},{"money":20.0,"key":"b","category":"油费"}]"""

        val data = reader.read(json.toByteArray())

        assertEquals(listOf("key", "date", "money", "category"), data.headers)
        assertEquals(listOf<Any?>("b", null, 20.0, "油费"), data.rows[1])
    }

    @Test
    fun `reads numbers booleans and nulls without stringifying`() {
        val json = """[{"key":"a","money":150,"hasbx":1,"paid":true,"remark":null}]"""

        val row = reader.read(json.toByteArray()).rows[0]

        assertEquals(150.0, row[1])
        assertEquals(1.0, row[2])
        assertEquals(true, row[3])
        assertNull(row[4])
    }

    @Test
    fun `supports a single object as one row`() {
        val json = """{"key":"qj1","money":12.5,"tags":["a","b"]}"""

        val data = reader.read(json.toByteArray(), "单条.json")

        assertEquals(listOf("key", "money", "tags"), data.headers)
        assertEquals(1, data.rows.size)
        assertEquals("qj1", data.rows[0][0])
        assertEquals(12.5, data.rows[0][1])
        // 嵌套数组保留成 JSON 字符串，不丢数据。
        assertEquals("[\"a\",\"b\"]", data.rows[0][2])
    }

    @Test
    fun `supports wrapped record arrays`() {
        val wrapped = """{"code":0,"data":{"list":[{"key":"qj1","money":1.0}]}}"""

        val data = reader.read(wrapped.toByteArray())

        assertEquals(listOf("key", "money"), data.headers)
        assertEquals(1, data.rows.size)
        assertEquals("qj1", data.rows[0][0])
    }

    @Test
    fun `supports utf8 bom`() {
        val json = """[{"key":"qj1","money":1.0}]"""
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray()

        val data = reader.read(bytes, "账单.txt")

        assertEquals(listOf("key", "money"), data.headers)
        assertEquals(1.0, data.rows[0][1])
    }

    @Test
    fun `rejects bad json`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("这不是 JSON".toByteArray(), "坏.json")
        }
        assertTrue(exception.message!!.contains("JSON"))
    }

    @Test
    fun `rejects empty input`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(ByteArray(0), "空.txt")
        }
        assertTrue(exception.message!!.contains("文件是空的"))
    }

    @Test
    fun `rejects empty record array`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("[]".toByteArray(), "空账单.json")
        }
        assertTrue(exception.message!!.contains("没有可导入"))
    }

    @Test
    fun `rejects non object records`() {
        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read("[1,2]".toByteArray(), "坏.json")
        }
        assertTrue(exception.message!!.contains("不是对象"))
    }

    @Test
    fun `enforces the row limit`() {
        val json = """[{"key":"a"},{"key":"b"},{"key":"c"}]"""

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            QianJiJsonReader(maxDataRows = 2).read(json.toByteArray())
        }
        assertTrue(exception.message!!.contains("单次最多导入 2 行"))
    }

    @Test
    fun `enforces the default twenty thousand row limit`() {
        val json = buildString {
            append('[')
            for (index in 1..20_001) {
                if (index > 1) append(',')
                append("{\"key\":\"qj$index\"}")
            }
            append(']')
        }

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            QianJiJsonReader().read(json.toByteArray())
        }
        assertTrue(exception.message!!.contains("20,000"))
    }

    @Test
    fun `rejects files over ten megabytes`() {
        val bytes = ByteArray((SpreadsheetLimits.MAX_FILE_BYTES + 1).toInt())

        val exception = assertThrows(SpreadsheetReadException::class.java) {
            reader.read(bytes, "超大.json")
        }
        assertTrue(exception.message!!.contains("文件超过 10 MB"))
    }
}
