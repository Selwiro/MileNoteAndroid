package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录校验：充电量 kWh 的有效性，以及纯电记录沿用能源费金额/手填能耗规则。 */
class ValidateTest {

    private fun evRecord(
        amount: Double = 62.0,
        kwh: Double? = 40.0,
        kind: String = "expense",
        rate: Double? = null,
        distance: Double? = null,
    ): Record = Record(
        id = "ev",
        date = "2026-11-01",
        category = "油费",
        amount = amount,
        kwh = kwh,
        kind = kind,
        rate = rate,
        distance = distance,
    )

    @Test
    fun `valid ev record passes`() {
        assertEquals("", validateRecord(evRecord()))
        // 只填金额、不填充电量也合法
        assertEquals("", validateRecord(evRecord(kwh = null)))
    }

    @Test
    fun `negative or non finite kwh is rejected`() {
        val message = "里程、加油量/充电量、能耗必须是有效非负数"
        assertEquals(message, validateRecord(evRecord(kwh = -1.0)))
        assertEquals(message, validateRecord(evRecord(kwh = Double.NaN)))
        assertEquals(message, validateRecord(evRecord(kwh = Double.POSITIVE_INFINITY)))
        // 0 度不算非法（可能是一笔补差价记录），只有负数/非有限值被拒
        assertEquals("", validateRecord(evRecord(kwh = 0.0)))
    }

    @Test
    fun `ev manual consumption requires a positive rate`() {
        val manual = evRecord(amount = 0.0, kwh = 40.0, kind = "manual", distance = 100.0)
        assertEquals("请填写有效油耗/电耗", validateRecord(manual))
        assertEquals(
            "",
            validateRecord(manual.copy(rate = 6.5)),
        )
    }

    @Test
    fun `ev amount must still be positive unless service or manual`() {
        assertTrue(validateRecord(evRecord(amount = 0.0)).isNotEmpty())
        assertEquals("", validateRecord(evRecord(amount = 0.0, kind = "manual", rate = 6.0, distance = 100.0)))
    }
}
