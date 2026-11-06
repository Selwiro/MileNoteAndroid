package com.milenote.android.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 对应 Web core.test：「陪伴天数包含起始日，跨年闰日正确，拒绝未来及无效日期」。 */
class CompanionDaysTest {

    @Test
    fun `inclusive leap and invalid dates`() {
        assertEquals(1, Companion.days("2026-11-01", "2026-11-01"))
        assertEquals(2, Companion.days("2025-12-31", "2026-01-01"))
        assertEquals(3, Companion.days("2024-02-28", "2024-03-01"))
        assertEquals(2, Companion.days("2025-03-01", "2025-03-02"))
        assertNull(Companion.days("2026-02-30", "2026-11-01"))
        assertNull(Companion.days("2026-11-02", "2026-11-01"))
        assertNull(Companion.days("", "2026-11-01"))
        assertNull(Companion.days(null, "2026-11-01"))
        assertNull(Companion.days("2026-1-1", "2026-11-01"))
    }

    @Test
    fun `leap year full span`() {
        assertEquals(367, Companion.days("2024-01-01", "2025-01-01"))
        assertEquals(366, Companion.days("2024-01-01", "2024-12-31"))
    }
}
