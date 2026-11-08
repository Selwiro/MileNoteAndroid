package com.milenote.android.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milenote.android.domain.i18n.DomainI18n
import java.util.Locale

/** 界面语言偏好（DataStore 持久化；不随账本备份）。 */
enum class AppLanguage { SYSTEM, ZH, EN }

/**
 * 应用内轻量 i18n。
 *
 * 中文原文即 key，英文在调用点就地给出：`tr("设置", "Settings")`。
 * [english] 是 Compose 快照状态：切换语言后，组合里读过它的 `tr()` 会自动重组，
 * 因此不需要把语言作为参数在组件树里层层传递。
 *
 * 只用于**用户可见文案**；分类名 / 皮肤 id / presetKey 等数据值不要翻译
 * （显示映射见 [DataLabels]）。
 */
object I18n {

    /** 当前是否以英文渲染。 */
    var english by mutableStateOf(false)
        private set

    /** 由宿主（MainActivity）在语言偏好 / 系统语言变化时调用。 */
    fun applyEnglish(value: Boolean) {
        if (english == value) return
        english = value
        // domain 层（纯 Kotlin）用独立开关；在 domain 生成文案前同步。
        DomainI18n.setEnglish(value)
    }
}

/** 静态文案：显示英文时返回 [en]，否则返回中文原文 [zh]。 */
fun tr(zh: String, en: String): String = if (I18n.english) en else zh

/**
 * 带占位符的文案：[zh] / [en] 里的 `%s` / `%d` 数量与顺序必须一致，参数按顺序传入。
 *
 * ```kotlin
 * tr("共 %d 条记录", "%d records", count)
 * ```
 */
fun tr(zh: String, en: String, vararg args: Any?): String =
    String.format(Locale.ROOT, if (I18n.english) en else zh, *args)
