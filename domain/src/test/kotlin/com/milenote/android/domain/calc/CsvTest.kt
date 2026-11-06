package com.milenote.android.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Test

/** 对应 Web core.test：带引号、逗号、换行和 BOM 的 CSV 解析。 */
class CsvTest {

    @Test
    fun `quoted comma newline and bom`() {
        assertEquals(
            listOf(listOf("日期", "备注"), listOf("2026-01-01", "加油,含优惠\n第二行")),
            parseDelimited("\uFEFF日期,备注\r\n2026-01-01,\"加油,含优惠\n第二行\""),
        )
    }

    @Test
    fun `escaped quotes delimiters and blank rows`() {
        assertEquals(
            listOf(listOf("a", "b\"c", "d"), listOf("1", "2", "3")),
            parseDelimited("a,\"b\"\"c\",d\n\n1;2;3".replace(';', ',')),
        )
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            parseDelimited("a\tb\r\nc\td\r\n"),
        )
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            parseDelimited("a;b\nc;d"),
        )
    }

    @Test
    fun `empty text and rows of blanks`() {
        assertEquals(emptyList<List<String>>(), parseDelimited(""))
        assertEquals(emptyList<List<String>>(), parseDelimited("\n\n"))
        assertEquals(1, parseDelimited("a,b\n").size)
    }
}
