package com.milenote.android.domain.update

/**
 * 比较版本号：忽略前缀 `v`；主版本按数字段逐位比较，后缀（`-rc5` / `-beta`）只决定同号内的先后。
 * `0.31.0` > `0.30.6`，`0.30.6` 与 `v0.30.6` 相等。
 *
 * **正式版高于同号预发布**：`1.0.0` > `1.0.0-rc5`（1.0 审查后新增；
 * 以前把 `-rc5` 里的 5 当第四段数字，正式版反而比 rc 小，rc 用户收不到 1.0.0 的更新提示）。
 * 两个预发布之间比后缀里的数字：`1.0.0-rc6` > `1.0.0-rc5`。
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = parseVersion(latest)
    val b = parseVersion(current)
    for (i in 0 until maxOf(a.base.size, b.base.size)) {
        val x = a.base.getOrElse(i) { 0 }
        val y = b.base.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    if (a.prerelease != b.prerelease) return !a.prerelease
    return a.preNumber > b.preNumber
}

/** 拆成「主版本数字段 + 是否预发布 + 后缀数字」。 */
private class ParsedVersion(val base: List<Int>, val prerelease: Boolean, val preNumber: Int)

private fun parseVersion(value: String): ParsedVersion {
    val cleaned = value.trim().removePrefix("v").removePrefix("V")
    val dash = cleaned.indexOf('-')
    val head = if (dash >= 0) cleaned.substring(0, dash) else cleaned
    val tail = if (dash >= 0) cleaned.substring(dash + 1) else ""
    return ParsedVersion(
        base = head.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() },
        prerelease = tail.isNotEmpty(),
        preNumber = tail.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.lastOrNull()?.toIntOrNull() ?: 0,
    )
}
