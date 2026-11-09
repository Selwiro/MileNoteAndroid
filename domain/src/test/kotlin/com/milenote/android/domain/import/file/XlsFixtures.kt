package com.milenote.android.domain.import.file

/**
 * .xls 测试样本加载器。
 *
 * 样本是 2026-11-09 用 jxl 2.6.12 一次性生成后提交的静态二进制文件（`src/test/resources/xls/`），
 * 目的是让 domain 模块彻底移除 jxl（LGPL）依赖，同时保留对 BIFF8 各类记录/编码的回归覆盖：
 *
 * | 样本 | 覆盖点 |
 * |---|---|
 * | `sample.xls` | 字符串/数字/日期/布尔/空单元格、多工作表 |
 * | `rows4.xls` | 行数上限 |
 * | `dispatch.xls` | 文件签名分派 |
 * | `kitchen-sink.xls` | 自定义数字格式（百分比/千分位）、时间、公式缓存值、隐藏工作表 |
 * | `sst-continue.xls` | 共享字符串表跨 CONTINUE 记录（800 条中文长文本） |
 * | `all-hidden.xls` | 所有工作表隐藏时回退第一张 |
 * | `many-numbers.xls` | 整数/小数/负数（RK、MULRK、NUMBER 编码） |
 * | `builtin-date.xls` | 内置日期/时间格式（m/d/yy、h:mm:ss） |
 *
 * 需要新增样本时，用任意工具生成后放进同一目录即可，注意别把真实账本数据放进来。
 */
internal object XlsFixtures {

    fun load(name: String): ByteArray =
        XlsFixtures::class.java.getResourceAsStream("/xls/$name")?.use { it.readBytes() }
            ?: error("缺少测试样本 /xls/$name")
}
