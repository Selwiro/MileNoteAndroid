package com.milenote.android.domain.backup

import com.milenote.android.domain.d
import com.milenote.android.domain.demo.DemoData
import com.milenote.android.domain.model.Garage
import com.milenote.android.domain.model.HistoryBaseline
import com.milenote.android.domain.model.POWERTRAIN_EV
import com.milenote.android.domain.model.POWERTRAIN_FUEL
import com.milenote.android.domain.model.PlanBase
import com.milenote.android.domain.model.Record
import com.milenote.android.domain.model.Reminder
import com.milenote.android.domain.model.Vehicle
import com.milenote.android.domain.model.isEv
import com.milenote.android.domain.model.powertrainKey
import com.milenote.android.domain.model.blankLedger
import com.milenote.android.domain.calc.stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对应 Web garage.test：v1/v2 备份迁移、合并、校验、图片白名单、删除数据。 */
class BackupModelTest {

    private val record = Record(id = "r1", date = "2026-10-01", category = "停车费", amount = 100.0, kind = "expense")

    private fun simpleGarage(carId: String = "car-1"): Garage {
        val ledger = blankLedger().copy(id = carId)
        return Garage(version = 2, activeId = carId, theme = "blue", cars = listOf(ledger))
    }

    @Test
    fun `v2 encode decode round trip`() {
        val garage = simpleGarage().let { g ->
            g.copy(
                theme = "forest",
                cars = listOf(
                    g.cars[0].copy(
                        records = listOf(record),
                        reminders = listOf(
                            Reminder(
                                id = "t1",
                                title = "机油机滤",
                                presetKey = "oil",
                                projects = listOf("机油机滤"),
                                intervalKm = 10000.0,
                                intervalMonths = 12,
                                dueDate = "2027-10-01",
                                dueKm = 12000.0,
                                historyBaseline = HistoryBaseline(
                                    baseDate = "2026-01-01",
                                    baseKm = 1000.0,
                                    dueDate = "2027-01-01",
                                    dueKm = 11000.0,
                                ),
                            ),
                        ),
                        customCategories = listOf("充电费"),
                        servicePlan = listOf(),
                        servicePlanBase = PlanBase(date = "2026-01-01", km = 1000.0, includeFirst = true),
                        servicePlanConfigured = true,
                        serviceProjects = listOf("洗车"),
                        serviceProjectsByCategory = mapOf("保养" to listOf("天窗轨道润滑")),
                        vehicle = Vehicle(
                            name = "测试车",
                            plate = "沪A12345",
                            model = "Golf",
                            startKm = 1000.0,
                            currentKm = 5000.0,
                            fuelCostSource = "combined",
                            companionDate = "2026-01-01",
                            image = "https://example.com/car.png",
                            imageFit = "cover",
                        ),
                    ),
                ),
            )
        }
        val text = BackupCodec.encode(garage)
        assertTrue(text.contains("\"version\":2"))
        assertTrue(text.contains("\"activeId\""))
        assertTrue(text.contains("\"theme\":\"forest\""))
        assertTrue(text.contains("\"historyBaseline\""))
        val decoded = BackupCodec.decode(text)
        assertEquals(garage, decoded)
    }

    @Test
    fun `fuel grade survives backup round trip`() {
        val fuel = Record(
            id = "f1",
            date = "2026-10-01",
            category = "油费",
            amount = 300.0,
            liters = 40.0,
            fuelGrade = "95#",
        )
        val garage = simpleGarage().let { g ->
            g.copy(cars = listOf(g.cars[0].copy(records = listOf(fuel))))
        }
        assertEquals("95#", BackupCodec.decode(BackupCodec.encode(garage)).cars[0].records[0].fuelGrade)

        // 未指定标号的记录不写入字段（explicitNulls = false）。
        val withoutGrade = fuel.copy(id = "f2", fuelGrade = null)
        val garage2 = simpleGarage().let { g ->
            g.copy(cars = listOf(g.cars[0].copy(records = listOf(withoutGrade))))
        }
        assertFalse(BackupCodec.encode(garage2).contains("fuelGrade"))
    }

    @Test
    fun `v1 backup migrates to v2 single car`() {
        val v1 = """
            {"version":1,"vehicle":{"name":"旧车","startKm":1000,"currentKm":3000},
             "records":[{"id":"r1","date":"2026-10-01","category":"维修保养","amount":380,"kind":"expense"}],
             "reminders":[{"id":"t1","title":"保养","dueKm":5000}]}
        """.trimIndent()
        val garage = BackupCodec.decode(v1)
        assertEquals(2, garage.version)
        assertEquals(1, garage.cars.size)
        val car = garage.cars[0]
        assertTrue(car.id.isNotEmpty())
        assertEquals(car.id, garage.activeId)
        assertEquals("保养", car.records[0].category)
        assertEquals(380.0, stats(car).total, 1e-9)
        assertEquals(5000.0, car.reminders[0].dueKm!!, 1e-9)
        assertEquals("blue", garage.theme)
        assertEquals("旧车", car.vehicle.name)
    }

    @Test
    fun `v1 backup payload is distinguishable and restores into active car`() {
        val v1 = """
            {"version":1,"vehicle":{"name":"旧车"},
             "records":[{"id":"r1","date":"2026-10-01","category":"停车费","amount":100,"kind":"expense"}],
             "reminders":[]}
        """.trimIndent()
        assertTrue(BackupCodec.decodePayload(v1) is BackupPayload.LegacyLedger)

        var garage = simpleGarage().let {
            it.copy(cars = it.cars + blankLedger().copy(id = "car-2"))
        }
        garage = garage.copy(activeId = "car-2")
        garage = BackupCodec.restore(garage, v1)
        assertEquals(0, garage.cars[0].records.size)
        assertEquals(1, garage.cars[1].records.size)
        assertEquals("car-2", garage.activeId)

        // 重复恢复同一业务记录会被判重跳过
        val duplicate = v1.replace("\"r1\"", "\"r-different\"")
        garage = BackupCodec.restore(garage, duplicate)
        assertEquals(1, garage.cars[1].records.size)
    }

    @Test
    fun `v2 restore merges by car id and is idempotent`() {
        var current = simpleGarage().let {
            it.copy(cars = it.cars.map { car -> car.copy(records = listOf(record)) })
        }
        val incoming = simpleGarage().let { g ->
            g.copy(
                cars = listOf(
                    g.cars[0].copy(
                        records = listOf(
                            record,
                            Record(id = "r2", date = "2026-11-01", category = "保险", amount = 25.5),
                        ),
                    ),
                    blankLedger().copy(id = "car-3").copy(
                        records = listOf(record.copy(id = "r3", amount = 25.0)),
                    ),
                ),
            )
        }
        val json = BackupCodec.encode(incoming)
        current = BackupCodec.restore(current, json)
        current = BackupCodec.restore(current, json)
        assertEquals(2, current.cars.size)
        assertEquals(2, current.cars[0].records.size)
        assertEquals(1, current.cars[1].records.size)
        assertEquals(25.5, current.cars[0].records.first { it.id == "r2" }.amount, 1e-9)
    }

    @Test
    fun `normalize garage creates blank car and falls back active id`() {
        val garage = GarageOps.normalizeGarage(null)
        assertEquals(2, garage.version)
        assertEquals(1, garage.cars.size)
        assertEquals(garage.cars[0].id, garage.activeId)
        assertEquals("blue", garage.theme)
        assertEquals(0, garage.cars[0].records.size)

        val fallback = garage.copy(activeId = "missing")
        assertEquals(garage.cars[0].id, GarageOps.normalizeGarage(fallback).activeId)
    }

    @Test
    fun `invalid backups are rejected`() {
        assertThrows(BackupException::class.java) { BackupCodec.decode("not json") }
        assertThrows(BackupException::class.java) { BackupCodec.decode("[]") }
        assertThrows(BackupException::class.java) { BackupCodec.decode("{}") }
        assertThrows(BackupException::class.java) { BackupCodec.decode("""{"version":3}""") }

        val garage = simpleGarage()
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(garage.copy(cars = emptyList())))
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(garage.copy(cars = listOf(garage.cars[0], garage.cars[0]))))
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                BackupCodec.encode(
                    garage.copy(cars = listOf(garage.cars[0].copy(records = listOf(record.copy(amount = -1.0))))),
                ),
            )
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                BackupCodec.encode(
                    garage.copy(cars = listOf(garage.cars[0].copy(records = listOf(record, record)))),
                ),
            )
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                BackupCodec.encode(
                    garage.copy(
                        cars = listOf(garage.cars[0].copy(reminders = listOf(Reminder(id = "t", title = "提醒")))),
                    ),
                ),
            )
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                BackupCodec.encode(
                    garage.copy(
                        cars = listOf(
                            garage.cars[0].copy(
                                reminders = listOf(Reminder(id = "t", title = "提醒", dueKm = -1.0)),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                BackupCodec.encode(
                    garage.copy(cars = listOf(garage.cars[0].copy(vehicle = garage.cars[0].vehicle.copy(currentKm = -5.0)))),
                ),
            )
        }
        assertThrows(BackupException::class.java) {
            BackupCodec.decode(
                """
                {"version":2,"activeId":"","cars":[{"version":2,"id":"a","vehicle":{},"records":[],"reminders":[]}]}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `web shaped json with explicit nulls decodes`() {
        val json = """
            {"version":2,"activeId":"c1","theme":"blue","cars":[{"version":1,"id":"c1",
             "vehicle":{"name":"车","plate":"","model":"","startKm":null,"currentKm":0,"fuelCostSource":"bear"},
             "records":[{"id":"r1","date":"2026-01-01","category":"油费","amount":100,"odometer":null,
               "liters":null,"rate":null,"reportedRate":null,"distance":null,"full":true,"missed":false,
               "note":"","location":null,"projects":null,"source":"小熊油耗","sourceId":null,
               "kind":"expense","fuelCounted":null,"importedKeys":null,"time":null}],
             "reminders":[{"id":"t","title":"机油机滤","presetKey":"oil","projects":["机油机滤"],
               "firstOnly":false,"intervalKm":10000,"intervalMonths":12,"dueDate":"2027-01-01",
               "dueKm":10000,"baseDate":null,"baseKm":null,"done":false,"completedDate":null,
               "completedKm":null,"recordId":null,"historyGenerated":null,"historyBaseline":null,
               "historyRecordId":null,"source":null,"note":null}],
             "customCategories":null,"servicePlan":null,"servicePlanBase":null,
             "servicePlanConfigured":null,"serviceProjects":null,"serviceProjectsByCategory":null}]}
        """.trimIndent()
        val garage = BackupCodec.decode(json)
        val car = garage.cars[0]
        assertEquals("blue", garage.theme)
        assertEquals("c1", garage.activeId)
        assertNull(car.vehicle.startKm)
        assertNull(car.records[0].rate)
        assertNull(car.records[0].liters)
        assertEquals(true, car.records[0].full)
        assertEquals(12, car.reminders[0].intervalMonths)
        assertEquals("机油机滤", car.reminders[0].title)
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = """
            {"version":2,"activeId":"car-1","theme":"blue","futureField":true,
             "cars":[{"version":1,"id":"car-1","vehicle":{"name":"车","future":1},
                      "records":[{"id":"r","date":"2026-01-01","category":"停车费","amount":10,"kind":"expense","unknownX":2}],
                      "reminders":[],"customCategories":[]}]}
        """.trimIndent()
        val garage = BackupCodec.decode(json)
        assertEquals(1, garage.cars.size)
        assertEquals("车", garage.cars[0].vehicle.name)
        assertEquals(10.0, garage.cars[0].records[0].amount, 1e-9)
    }

    @Test
    fun `replace active only changes current car`() {
        var garage = GarageOps.normalizeGarage(null)
        val firstId = garage.cars[0].id
        garage = garage.copy(
            cars = garage.cars + blankLedger().copy(id = "second"),
            activeId = "second",
        )
        val second = garage.cars[1].copy(
            records = listOf(record),
            reminders = listOf(Reminder(id = "t2", title = "轮胎", dueKm = 10000.0)),
            vehicle = garage.cars[1].vehicle.copy(image = "https://example.com/car.png"),
        )
        garage = GarageOps.replaceActive(garage, second)
        assertEquals(0.0, stats(garage.cars[0]).total, 1e-9)
        assertEquals(100.0, stats(garage.cars[1]).total, 1e-9)
        assertEquals(0, garage.cars[0].reminders.size)
        assertNull(garage.cars[0].vehicle.image)
        assertEquals(firstId, garage.cars[0].id)
    }

    @Test
    fun `merge garage by car id appends unknown cars`() {
        var garage = GarageOps.normalizeGarage(null)
        garage = garage.copy(cars = garage.cars.map { it.copy(records = listOf(record)) })
        val backup = garage.copy(
            cars = garage.cars + blankLedger().copy(
                id = "second",
                records = listOf(record.copy(id = "r2", amount = 25.0)),
            ),
        )
        var merged = GarageOps.mergeGarage(garage, backup)
        merged = GarageOps.mergeGarage(merged, backup)
        assertEquals(2, merged.cars.size)
        assertEquals(1, merged.cars[0].records.size)
        assertEquals(1, merged.cars[1].records.size)
        assertEquals(25.0, stats(merged.cars[1]).total, 1e-9)
    }

    @Test
    fun `merge ledger keeps existing data and unions projects`() {
        val existing = blankLedger().copy(
            id = "car-1",
            customCategories = listOf("充电费"),
            records = listOf(record),
            reminders = listOf(Reminder(id = "t1", title = "保养", dueKm = 5000.0)),
            serviceProjects = listOf("洗车"),
            servicePlanConfigured = false,
        )
        val incoming = blankLedger().copy(
            id = "car-1",
            customCategories = listOf("洗车券"),
            records = listOf(
                record,
                Record(id = "r2", date = "2026-11-01", category = "保养", amount = 50.0),
            ),
            reminders = listOf(
                Reminder(id = "t1", title = "重复 id 不追加"),
                Reminder(id = "t2", title = "轮胎", dueKm = 10000.0),
            ),
            servicePlan = listOf(comilenotePlan()),
            servicePlanBase = PlanBase(date = "2026-01-01", km = 0.0, includeFirst = true),
            serviceProjects = listOf("机油机滤"),
            serviceProjectsByCategory = mapOf("保养" to listOf("天窗轨道润滑")),
            servicePlanConfigured = true,
        )
        val merged = GarageOps.mergeLedger(existing, incoming)
        assertEquals(listOf("充电费", "洗车券"), merged.customCategories)
        assertEquals(2, merged.records.size)
        assertEquals(2, merged.reminders.size)
        assertEquals(listOf("洗车", "机油机滤"), merged.serviceProjects)
        assertEquals(listOf("天窗轨道润滑"), merged.serviceProjectsByCategory!!["保养"])
        assertEquals(true, merged.servicePlanConfigured)
        assertNotNull(merged.servicePlan)
    }

    @Test
    fun `merge ledger replaces vehicle only for empty car`() {
        val empty = blankLedger()
        val incoming = blankLedger().copy(vehicle = Vehicle(name = "外来车"))
        assertEquals("外来车", GarageOps.mergeLedger(empty, incoming).vehicle.name)

        val nonEmpty = empty.copy(records = listOf(record))
        assertEquals("我的爱车", GarageOps.mergeLedger(nonEmpty, incoming).vehicle.name)
    }

    @Test
    fun `custom categories survive backup restore`() {
        val incoming = """
            {"version":1,"vehicle":{"name":"测试车"},
             "records":[{"id":"c1","date":"2026-11-01","category":"充电费","amount":80}],
             "reminders":[],"customCategories":["充电费"]}
        """.trimIndent()
        val migrated = BackupCodec.decode(incoming)
        assertEquals("充电费", migrated.cars[0].records[0].category)
        val merged = BackupCodec.restore(GarageOps.normalizeGarage(null), incoming)
        assertTrue(merged.cars[0].customCategories!!.contains("充电费"))
        assertEquals(80.0, merged.cars[0].records[0].amount, 1e-9)
    }

    @Test
    fun `safe image whitelist`() {
        assertEquals("https://example.com/car.png", GarageOps.safeImage("https://example.com/car.png"))
        assertEquals("data:image/webp;base64,YWJj", GarageOps.safeImage("data:image/webp;base64,YWJj"))
        assertEquals("", GarageOps.safeImage(null))
        assertEquals("", GarageOps.safeImage(""))
        assertThrows(BackupException::class.java) { GarageOps.safeImage("javascript:alert(1)") }
        assertThrows(BackupException::class.java) { GarageOps.safeImage("data:text/html;base64,YWJj") }
        assertThrows(BackupException::class.java) { GarageOps.safeImage("https://user:password@example.com/car.png") }
        assertThrows(BackupException::class.java) { GarageOps.safeImage("https://example.com/" + "a".repeat(2100)) }
    }

    @Test
    fun `delete garage data by scope rebuilds blank car`() {
        val garage = DemoData.createDemoGarage(d("2026-11-02"))
        val snapshot = BackupCodec.encode(garage)
        val other = garage.cars[1]

        val one = GarageOps.deleteGarageData(garage, "one", garage.activeId)
        assertEquals(1, one.cars.size)
        assertEquals(other, one.cars[0])
        assertEquals(other.id, one.activeId)
        assertEquals(snapshot, BackupCodec.encode(garage))

        val last = GarageOps.deleteGarageData(one, "one", other.id)
        assertEquals(1, last.cars.size)
        assertEquals(0, last.cars[0].records.size)
        assertEquals(0, last.cars[0].reminders.size)
        assertTrue(last.activeId != other.id)
        assertEquals(2, GarageOps.normalizeGarage(last).version)

        val all = GarageOps.deleteGarageData(garage, "all")
        assertEquals(1, all.cars.size)
        assertEquals(0, all.cars[0].records.size)
        assertEquals(0, all.cars[0].reminders.size)
        assertNull(all.cars[0].servicePlan)

        assertThrows(BackupException::class.java) { GarageOps.deleteGarageData(garage, "one", "missing") }
        assertThrows(BackupException::class.java) { GarageOps.deleteGarageData(garage, "unknown") }
    }

    @Test
    fun `encoding omits null optional fields like web undefined`() {
        val garage = simpleGarage()
        val text = BackupCodec.encode(garage)
        assertFalse(text.contains("\"startKm\""))
        assertFalse(text.contains("\"image\""))
        assertFalse(text.contains("\"customCategories\""))
        assertTrue(text.contains("\"records\":[]"))
        val car = BackupCodec.decode(text).cars[0]
        assertNull(car.vehicle.startKm)
        assertNull(car.customCategories)
        assertEquals(1, car.version)
    }

    @Test
    fun `powertrain and kwh survive backup round trip`() {
        val car = blankLedger(POWERTRAIN_EV).copy(
            id = "ev-car",
            records = listOf(
                Record(
                    id = "c1",
                    date = "2026-11-01",
                    category = "油费",
                    amount = 62.0,
                    odometer = 1000.0,
                    kwh = 40.0,
                    unitPrice = 1.55,
                    full = true,
                    kind = "expense",
                ),
            ),
        )
        val garage = Garage(version = 2, activeId = "ev-car", theme = "blue", cars = listOf(car))
        val text = BackupCodec.encode(garage)
        assertTrue(text.contains("\"powertrain\":\"ev\""))
        assertTrue(text.contains("\"kwh\":40"))
        // 纯电记录不写升数字段（null 省略）
        assertFalse(text.contains("\"liters\""))

        val decoded = BackupCodec.decode(text)
        val decodedCar = decoded.cars[0]
        assertEquals(POWERTRAIN_EV, decodedCar.vehicle.powertrain)
        assertTrue(decodedCar.isEv)
        assertEquals(40.0, decodedCar.records[0].kwh!!, 1e-9)
        assertNull(decodedCar.records[0].liters)
        assertEquals(1.55, decodedCar.records[0].unitPrice!!, 1e-9)
    }

    @Test
    fun `old backup without powertrain or kwh decodes as fuel`() {
        val json = """
            {"version":2,"activeId":"a","cars":[{"version":1,"id":"a",
             "vehicle":{"name":"旧车","currentKm":100,"fuelCostSource":"bear"},
             "records":[{"id":"r","date":"2026-01-01","category":"油费","amount":300,
               "liters":40,"odometer":1000,"full":true,"kind":"expense"}],
             "reminders":[]}]}
        """.trimIndent()
        val garage = BackupCodec.decode(json)
        // 缺字段 → 燃油语义，不新增键
        assertNull(garage.cars[0].vehicle.powertrain)
        assertFalse(garage.cars[0].isEv)
        assertEquals(POWERTRAIN_FUEL, garage.cars[0].vehicle.powertrainKey)
        assertEquals(40.0, garage.cars[0].records[0].liters!!, 1e-9)
        assertNull(garage.cars[0].records[0].kwh)

        // 缺省燃油车导出时不写 powertrain 键，老版本 App 仍可读
        val encoded = BackupCodec.encode(simpleGarage())
        assertFalse(encoded.contains("\"powertrain\""))
        assertFalse(encoded.contains("\"kwh\""))
    }

    @Test
    fun `unknown powertrain normalizes to fuel on decode`() {
        val json = """
            {"version":2,"activeId":"a","cars":[{"version":1,"id":"a",
             "vehicle":{"name":"怪车","currentKm":0,"powertrain":"PHEV"},
             "records":[],"reminders":[]}]}
        """.trimIndent()
        val garage = BackupCodec.decode(json)
        assertNull(garage.cars[0].vehicle.powertrain)
        assertFalse(garage.cars[0].isEv)
    }

    private fun comilenotePlan() = com.milenote.android.domain.model.PlanEntry(
        key = "oil",
        intervalKm = 7500.0,
        intervalMonths = 9,
    )
}
