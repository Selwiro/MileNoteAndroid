package com.milenote.android.domain.calc

import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.importRows
import com.milenote.android.domain.import.inferServiceNote
import com.milenote.android.domain.import.normalizeImport
import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web core.test：钱迹 / 小熊导入映射、备注识别、自定义分类。 */
class ImportMappingTest {

    @Test
    fun `qianji rejects income non car and foreign currency`() {
        val headers = listOf("date", "category", "money", "type", "currency", "key")
        val map = detectMap(headers)
        val items = normalizeImport(
            listOf(
                listOf("2026-10-01 12:30:00", "停车费", 25, "支出", "CNY", "a"),
                listOf("2026-10-01", "油费", 100, "收入", "CNY", "b"),
                listOf("2026-10-01", "餐饮", 100, "支出", "CNY", "c"),
                listOf("2026-10-01", "油费", 20, "支出", "USD", "d"),
            ),
            map,
            "qianji",
        )
        assertEquals(25.0, items[0].record.amount, 1e-9)
        assertEquals("", items[0].error)
        assertEquals("2026-10-01", items[0].record.date)
        assertEquals("a", items[0].record.sourceId)
        assertEquals("非支出记录，已跳过", items[1].error)
        assertEquals("请选择汽车费用分类", items[2].error)
        assertEquals("非人民币记录，请先换算", items[3].error)
        assertTrue(items.drop(1).all { it.error.isNotEmpty() })
    }

    @Test
    fun `bear sentinel rate paid amount priority and missed flag`() {
        val headers = listOf("日期时间", "总里程", "加油量", "机显金额", "实付金额", "加满", "漏记", "油耗")
        val items = normalizeImport(
            listOf(listOf("2026-01-01", 1000, 30, 250, 230, "是", "是", -1)),
            detectMap(headers),
            "bear",
        )
        val item = items[0]
        assertEquals("", item.error)
        assertEquals(230.0, item.record.amount, 1e-9)
        assertNull(item.record.reportedRate)
        assertEquals(true, item.record.missed)
        assertEquals("小熊油耗", item.record.source)
        assertEquals("油费", item.record.category)
        assertEquals(true, item.record.full)
    }

    @Test
    fun `bear payable discount and unit price fill missing amounts`() {
        val headers = listOf("日期时间", "加油量", "油价", "应付金额", "优惠金额", "实付金额")
        val items = normalizeImport(
            listOf(
                listOf("2026-01-01", 40, 7.5, 300, 20, null),
                listOf("2026-01-02", 40, null, null, null, 300),
            ),
            detectMap(headers),
            "bear",
        )
        val first = items[0].record
        assertEquals(280.0, first.amount, 1e-9)
        assertEquals(7.5, first.unitPrice!!, 1e-9)
        assertEquals(300.0, first.payable!!, 1e-9)
        val second = items[1].record
        assertEquals(7.5, second.unitPrice!!, 1e-9)
        assertEquals(300.0, second.payable!!, 1e-9)
        assertEquals(0.0, second.discount!!, 1e-9)
    }

    @Test
    fun `bear rate without liters becomes manual record`() {
        val headers = listOf("日期", "油耗", "金额")
        val items = normalizeImport(
            listOf(listOf("2026-01-01", 6.5, 300)),
            detectMap(headers),
            "bear",
        )
        assertEquals("", items[0].error)
        assertEquals("manual", items[0].record.kind)
        assertEquals(6.5, items[0].record.rate!!, 1e-9)
        assertNull(items[0].record.reportedRate)
    }

    @Test
    fun `infer service note extracts mileage and projects`() {
        val note = "表显15,000km，更换机油机滤、空调滤芯，刹车油"
        val result = inferServiceNote(Record(category = "保养", odometer = null, note = note))
        assertEquals(15000.0, result.odometer!!, 1e-9)
        assertEquals("机油机滤、空调滤芯、制动液", result.projects)
        assertEquals(note, result.note)

        assertEquals(
            15000.0,
            inferServiceNote(Record(category = "维修", note = "1.5万公里 补胎")).odometer!!,
            1e-9,
        )

        val explicit = inferServiceNote(Record(category = "保养", odometer = 12345.0, projects = "空气滤芯", note = note))
        assertEquals(12345.0, explicit.odometer!!, 1e-9)
        assertEquals("空气滤芯", explicit.projects)
    }

    @Test
    fun `infer service note ignores plans and ambiguity`() {
        var result = inferServiceNote(
            Record(category = "保养", note = "下次10000km更换机油；未更换火花塞；检查皮带；洗车"),
        )
        assertNull(result.odometer)
        assertEquals("洗车", result.projects)

        result = inferServiceNote(Record(category = "保养", note = "15000km，16000km，机油机滤"))
        assertNull(result.odometer)

        result = inferServiceNote(Record(category = "保养", note = "总里程16000km，间隔10000km，更换空气滤芯"))
        assertEquals(16000.0, result.odometer!!, 1e-9)
        assertEquals("空气滤芯", result.projects)

        assertNull(inferServiceNote(Record(category = "油费", note = "15000km 机油")).odometer)
    }

    @Test
    fun `infer service note via normalize import`() {
        val map = detectMap(listOf("日期", "分类", "金额", "备注"))
        val note = "表显15,000km，更换机油机滤、空调滤芯，刹车油"
        val imported = normalizeImport(
            listOf(listOf("2026-05-01", "维修保养", 300, note)),
            map,
            "qianji",
        )[0]
        assertEquals("", imported.error)
        assertEquals("保养", imported.record.category)
        assertEquals(15000.0, imported.record.odometer!!, 1e-9)
        assertTrue(imported.record.projects!!.contains("机油机滤"))
    }

    @Test
    fun `custom categories participate in import and validation`() {
        val record = Record(
            id = "custom",
            date = "2026-11-01",
            category = "充电费",
            amount = 66.0,
            kind = "expense",
        )
        assertEquals("", validateRecord(record, listOf("充电费")))
        assertTrue(validateRecord(record).isNotEmpty())

        val imported = normalizeImport(
            listOf(listOf("2026-11-01", "充电费", 88)),
            detectMap(listOf("日期", "分类", "金额")),
            "qianji",
            "",
            listOf("充电费"),
        )[0]
        assertEquals("", imported.error)
        assertEquals("充电费", imported.record.category)
        assertFalse(imported.record.category.isEmpty())
    }

    @Test
    fun `detect map picks first alias in priority order`() {
        val map = detectMap(listOf("金额", "实付金额", "日期", "未知列"))
        assertEquals(1, map["amount"])
        assertEquals(2, map["date"])
        assertNull(map["odometer"])
    }

    @Test
    fun `import rows flags invalid rows and skips transfers`() {
        val map = detectMap(listOf("日期", "分类", "金额", "类型"))
        val items = com.milenote.android.domain.import.importRows(
            listOf(
                listOf("2026-01-01", "停车费", 10, "支出"),
                listOf("bad-date", "停车费", 10, "支出"),
                listOf("2026-01-01", "停车费", -1, "支出"),
                listOf("2026-01-01", "油费", 100, "转账"),
            ),
            map,
            "qianji",
        )
        assertEquals("", items[0].error)
        assertEquals("日期无效", items[1].error)
        assertEquals("金额必须大于 0", items[2].error)
        assertEquals("非支出记录，已跳过", items[3].error)
        assertEquals(2, items[0].row)
    }

    @Test
    fun `fuel grade column imports known grades and drops unknown values`() {
        val items = normalizeImport(
            listOf(
                listOf("2026-01-01", "油费", 300, "95#"),
                listOf("2026-01-02", "油费", 300, " 92# "),
                listOf("2026-01-03", "油费", 300, "92号"),
                listOf("2026-01-04", "油费", 300, null),
            ),
            detectMap(listOf("日期", "分类", "金额", "燃油标号")),
            "qianji",
        )
        assertEquals("95#", items[0].record.fuelGrade)
        // 前后空白先 trim 再校验。
        assertEquals("92#", items[1].record.fuelGrade)
        // 非法标号不阻断导入，只把字段清空。
        assertEquals("", items[2].error)
        assertNull(items[2].record.fuelGrade)
        assertNull(items[3].record.fuelGrade)
    }

    @Test
    fun `fuel grade aliases map to the same column`() {
        assertEquals(3, detectMap(listOf("日期", "分类", "金额", "燃油标号"))["fuelGrade"])
        assertEquals(3, detectMap(listOf("日期", "分类", "金额", "标号"))["fuelGrade"])
        assertEquals(3, detectMap(listOf("日期", "分类", "金额", "油品"))["fuelGrade"])
        assertEquals(3, detectMap(listOf("日期", "分类", "金额", "汽油标号"))["fuelGrade"])
        assertEquals(3, detectMap(listOf("日期", "分类", "金额", "fuelGrade"))["fuelGrade"])
        assertNull(detectMap(listOf("日期", "分类", "金额"))["fuelGrade"])
    }

    @Test
    fun `exported csv row round trips fuel amount fields and grade`() {
        val csv = listOf(
            "\"日期\",\"地点\",\"项目\",\"分类\",\"金额\"," +
                "\"应付金额\",\"优惠金额\",\"油价\",\"燃油标号\"," +
                "\"总里程\",\"加油量\",\"加满\",\"漏记\",\"油耗\",\"行驶里程\",\"备注\",\"来源\"",
            "\"2026-11-01\",\"\",\"\",\"油费\",\"290\"," +
                "\"300\",\"10\",\"7.5\",\"95#\"," +
                "\"12345\",\"40\",\"是\",\"否\",\"\",\"\",\"\",\"手动记录\"",
        ).joinToString("\r\n") + "\r\n"
        val rows = parseDelimited(csv)
        val item = importRows(rows.drop(1), detectMap(rows.first()), "qianji").single()
        assertEquals("", item.error)
        assertEquals(290.0, item.record.amount, 1e-9)
        assertEquals(300.0, item.record.payable!!, 1e-9)
        assertEquals(10.0, item.record.discount!!, 1e-9)
        assertEquals(7.5, item.record.unitPrice!!, 1e-9)
        assertEquals("95#", item.record.fuelGrade)
    }
}
