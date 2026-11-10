package com.milenote.android.platform

import com.milenote.android.domain.calc.parseDelimited
import com.milenote.android.domain.import.detectMap
import com.milenote.android.domain.import.importRows
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_PHEV
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Vehicle
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 费用 CSV 导出（纯 JVM）：在「金额」后补齐「应付金额 / 优惠金额 / 油价 / 燃油标号」，
 * 并验证导出的表头与行能被导入流程原样回灌。
 */
class LedgerExportsTest {

    private val expectedHeaders = listOf(
        "日期", "地点", "项目", "分类", "金额",
        "应付金额", "优惠金额", "油价", "燃油标号",
        "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源",
    )

    private fun ledger(record: Record): Ledger = Ledger(id = "car-test", records = listOf(record))

    private fun fuelRecord(): Record = Record(
        id = "r-fuel",
        date = "2026-11-01",
        category = "油费",
        amount = 290.0,
        payable = 300.0,
        discount = 10.0,
        unitPrice = 7.5,
        liters = 40.0,
        odometer = 12345.0,
        full = true,
        fuelGrade = "95#",
        source = "手动记录",
    )

    private fun csvText(ledger: Ledger): String =
        String(LedgerExports.csv(ledger), StandardCharsets.UTF_8).removePrefix("\uFEFF")

    private fun evLedger(record: Record): Ledger = Ledger(
        id = "car-ev",
        vehicle = Vehicle(name = "纯电车", powertrain = POWERTRAIN_EV),
        records = listOf(record),
    )

    private fun evRecord(): Record = Record(
        id = "r-charge",
        date = "2026-11-01",
        category = "油费",
        amount = 28.8,
        payable = 28.8,
        discount = 0.0,
        unitPrice = 0.64,
        kwh = 45.0,
        odometer = 12345.0,
        full = true,
        source = "手动记录",
    )

    @Test
    fun `csv header inserts four fuel columns after amount`() {
        val headers = parseDelimited(csvText(ledger(fuelRecord()))).first()
        assertEquals(expectedHeaders, headers)
    }

    @Test
    fun `csv row exports payable discount unit price and fuel grade`() {
        val row = parseDelimited(csvText(ledger(fuelRecord())))[1]
        assertEquals("2026-11-01", row[0])
        assertEquals("油费", row[3])
        assertEquals("290", row[4])
        assertEquals("300", row[5])
        assertEquals("10", row[6])
        assertEquals("7.5", row[7])
        assertEquals("95#", row[8])
        // 其余列顺序不变：总里程 / 加油量 / 加满 / 来源。
        assertEquals("12345", row[9])
        assertEquals("40", row[10])
        assertEquals("是", row[11])
        assertEquals("手动记录", row[16])
    }

    @Test
    fun `empty fuel fields export as empty cells`() {
        val parking = Record(
            id = "r-park",
            date = "2026-11-02",
            category = "停车费",
            amount = 25.0,
            source = "手动记录",
        )
        val row = parseDelimited(csvText(ledger(parking)))[1]
        assertEquals("25", row[4])
        assertEquals("", row[5])
        assertEquals("", row[6])
        assertEquals("", row[7])
        assertEquals("", row[8])
    }

    @Test
    fun `exported csv imports back keeping the four fuel fields`() {
        val rows = parseDelimited(csvText(ledger(fuelRecord())))
        val item = importRows(rows.drop(1), detectMap(rows.first()), "qianji").single()
        assertEquals("", item.error)
        assertEquals("2026-11-01", item.record.date)
        assertEquals(290.0, item.record.amount, 1e-9)
        assertEquals(300.0, item.record.payable!!, 1e-9)
        assertEquals(10.0, item.record.discount!!, 1e-9)
        assertEquals(7.5, item.record.unitPrice!!, 1e-9)
        assertEquals("95#", item.record.fuelGrade)
        assertEquals(12345.0, item.record.odometer!!, 1e-9)
        assertEquals(40.0, item.record.liters!!, 1e-9)
    }

    @Test
    fun `ev csv header switches energy columns and keeps 17 columns`() {
        val headers = parseDelimited(csvText(evLedger(evRecord()))).first()
        assertEquals(
            listOf(
                "日期", "地点", "项目", "分类", "金额",
                "应付金额", "优惠金额", "电价", "标号",
                "总里程", "充电量", "充满", "漏记", "能耗", "行驶里程", "备注", "来源",
            ),
            headers,
        )
    }

    @Test
    fun `ev csv row exports kwh and imports back`() {
        val rows = parseDelimited(csvText(evLedger(evRecord())))
        val row = rows[1]
        assertEquals("油费", row[3])
        assertEquals("0.64", row[7])
        assertEquals("", row[8])
        assertEquals("45", row[10])
        assertEquals("是", row[11])

        val item = importRows(rows.drop(1), detectMap(rows.first()), "qianji").single()
        assertEquals("", item.error)
        assertEquals(45.0, item.record.kwh!!, 1e-9)
        assertEquals(null, item.record.liters)
        assertEquals(0.64, item.record.unitPrice!!, 1e-9)
        assertTrue(item.record.full)
    }

    // ---- 插混：18 列 = 燃油 17 列 + 末尾「充电量」 ----

    private fun phevLedger(): Ledger = Ledger(
        id = "car-phev",
        vehicle = Vehicle(name = "插混", powertrain = POWERTRAIN_PHEV),
        records = listOf(
            Record(
                id = "r-phev-fuel",
                date = "2026-11-01",
                category = "油费",
                amount = 290.0,
                payable = 300.0,
                discount = 10.0,
                unitPrice = 7.5,
                liters = 40.0,
                odometer = 12345.0,
                full = true,
                fuelGrade = "95#",
                source = "手动记录",
            ),
            Record(
                id = "r-phev-charge",
                date = "2026-11-02",
                category = "油费",
                amount = 28.8,
                unitPrice = 0.64,
                kwh = 45.0,
                odometer = 12500.0,
                full = true,
                source = "手动记录",
            ),
        ),
    )

    @Test
    fun `phev csv header appends charging column and neutral unit price`() {
        val headers = parseDelimited(csvText(phevLedger())).first()
        assertEquals(
            listOf(
                "日期", "地点", "项目", "分类", "金额",
                "应付金额", "优惠金额", "单价", "燃油标号",
                "总里程", "加油量", "加满", "漏记", "油耗", "行驶里程", "备注", "来源", "充电量",
            ),
            headers,
        )
        assertEquals(18, headers.size)
    }

    @Test
    fun `phev csv rows write fuel and charging amounts mutually exclusively`() {
        val rows = parseDelimited(csvText(phevLedger()))
        val charge = rows.first { it[0] == "2026-11-02" }
        val fuel = rows.first { it[0] == "2026-11-01" }
        // 充电行：单价 = 电价，加油量空，标号空，末尾充电量有值
        assertEquals("0.64", charge[7])
        assertEquals("", charge[8])
        assertEquals("", charge[10])
        assertEquals("45", charge[17])
        // 加油行：单价 = 油价，加油量有值，标号有值，末尾充电量空
        assertEquals("7.5", fuel[7])
        assertEquals("95#", fuel[8])
        assertEquals("40", fuel[10])
        assertEquals("", fuel[17])
    }

    @Test
    fun `phev csv imports back splitting fuel and charging rows`() {
        val rows = parseDelimited(csvText(phevLedger()))
        val items = importRows(rows.drop(1), detectMap(rows.first()), "qianji")
        assertTrue(items.all { it.error.isEmpty() })
        val fuel = items.first { it.record.date == "2026-11-01" }.record
        assertEquals(40.0, fuel.liters!!, 1e-9)
        assertEquals(null, fuel.kwh)
        assertEquals("95#", fuel.fuelGrade)
        val charge = items.first { it.record.date == "2026-11-02" }.record
        assertEquals(45.0, charge.kwh!!, 1e-9)
        assertEquals(null, charge.liters)
        assertEquals(null, charge.fuelGrade)
    }

    @Test
    fun `csv keeps BOM CRLF and quoted cells`() {
        val bytes = LedgerExports.csv(ledger(fuelRecord()))
        assertTrue(bytes.size > 3)
        assertTrue(
            bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
        )
        val text = String(bytes, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF\"日期\""))
        assertTrue(text.contains("\r\n"))
        assertTrue(text.endsWith("\r\n"))
    }
}
