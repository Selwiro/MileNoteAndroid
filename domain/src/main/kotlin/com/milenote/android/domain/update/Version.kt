package com.milenote.android.domain.update

/**
 * 比较版本号：忽略前缀 `v` 与非数字后缀（如 `-beta`），按数字段逐位比较。
 * `0.31.0` > `0.30.6`，`0.30.6` 与 `v0.30.6` 相等。
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(value: String): List<Int> =
    value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split(Regex("[^0-9]+"))
        .filter { it.isNotEmpty() }
        .mapNotNull { it.toIntOrNull() }
