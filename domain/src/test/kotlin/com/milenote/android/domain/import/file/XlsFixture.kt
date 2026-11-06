package com.milenote.android.domain.import.file

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import jxl.Workbook

/** 测试用 .xls 生成器：用 jxl 写一个 BIFF8 文件。 */
internal object XlsFixture {

    /** 用 jxl 生成 .xls；[configure] 里调用 `workbook.createSheet(...)` 并写入单元格。 */
    fun build(configure: (jxl.write.WritableWorkbook) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val workbook = Workbook.createWorkbook(output)
        try {
            configure(workbook)
            workbook.write()
        } finally {
            workbook.close()
        }
        return output.toByteArray()
    }

    /** 本地时区当天零点对应的 [Date]，用于写日期单元格。 */
    fun date(year: Int, month: Int, day: Int): Date =
        Date.from(LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant())
}
