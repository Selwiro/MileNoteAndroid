package com.milenote.android.domain.i18n

import java.util.Locale

/**
 * domain 层（纯 Kotlin，无 Compose）的英文开关。
 *
 * 由 app 层 `com.milenote.android.i18n.I18n.setEnglish()` 同步。domain 在生成
 * 提示文案（校验错误、导入警告、备份异常等）时调用 [tr]。
 * 开关关闭时 [tr] 原样返回中文，因此 JVM 单测行为与历史一致。
 */
object DomainI18n {
    @Volatile
    var english: Boolean = false
        private set

    fun setEnglish(value: Boolean) {
        english = value
    }
}

/** 静态文案（domain）：英文模式返回 [en]，否则返回中文 [zh]。 */
fun tr(zh: String, en: String): String = if (DomainI18n.english) en else zh

/** 带占位符文案：`%s` / `%d` 数量与顺序在两种语言里必须一致。 */
fun tr(zh: String, en: String, vararg args: Any?): String =
    String.format(Locale.ROOT, if (DomainI18n.english) en else zh, *args)
