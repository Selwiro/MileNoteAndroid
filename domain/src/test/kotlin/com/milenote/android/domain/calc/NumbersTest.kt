package com.milenote.android.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Numbers.parse` 的千分位口径（B24）：只有「逗号后正好 3 位数字」才当千分位删掉。
 *
 * 旧实现把 `,` / `，` 一律抹掉，欧洲写法 `12,50`（12 块 5 毛）会被读成 1250 元（×100）。
 * 现在这类写法解析失败 → 导入时走「金额无法识别」的错误行，比静默改值安全。
 */
class NumbersTest {

    @Test
    fun `thousands comma before decimals is stripped`() {
        // 1,234.56 → 1234.56（逗号后 3 位数字、后面是小数点）
        assertEquals(1234.56, Numbers.parse("1,234.56")!!, 1e-9)
        assertEquals(1234567.89, Numbers.parse("1,234,567.89")!!, 1e-9)
        assertEquals(1234.0, Numbers.parse("1,234")!!, 1e-9)
    }

    @Test
    fun `decimal comma stays and fails to parse`() {
        // 欧洲写法：12,50 是 12.5 元，不是 1250 元；保留原样 → 解析失败
        assertNull(Numbers.parse("12,50"))
        assertNull(Numbers.parse("12，50"))
        assertNull(Numbers.parse("1.234,56"))
    }

    @Test
    fun `currency symbol and spaces are still ignored`() {
        assertEquals(1234.0, Numbers.parse("¥1,234")!!, 1e-9)
        assertEquals(1234.5, Numbers.parse(" ￥ 1,234.5 ")!!, 1e-9)
        assertEquals(1234.0, Numbers.parse("1 234")!!, 1e-9)
    }

    @Test
    fun `malformed grouping is not silently rewritten`() {
        // 分组不是 3 位（1,2345 / 12,34,567）就不猜，交给上层按错误行处理
        assertNull(Numbers.parse("1,2345"))
        assertNull(Numbers.parse("12,34,567"))
        // 正常数值与空值行为不变
        assertEquals(42.0, Numbers.parse("42")!!, 1e-9)
        assertNull(Numbers.parse(""))
        assertNull(Numbers.parse("  "))
        assertNull(Numbers.parse("abc"))
    }
}
