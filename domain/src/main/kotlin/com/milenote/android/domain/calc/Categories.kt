package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger

/** 分类色板（Web 5 套皮肤，每套 8 色 + 第 9 类固定色）。 */
data class Theme(
    val id: String,
    val name: String,
    val swatch: String,
    /** 前 8 个分类的颜色（油费…汽车用品）。 */
    val colors: List<String>,
)

/**
 * 费用分类与配色（移植 Web `CATEGORIES` / `categoryOf` / `categoryOptions` / `THEMES`）。
 *
 * 代码口径为 9 类（文档写「八类」是历史差异，测试按 9 类断言）。
 */
object Categories {
    /** 9 个内置分类，顺序参与图表配色。 */
    val ALL: List<String> = listOf(
        "油费", "保险", "保养", "过路费", "停车费", "维修", "罚单", "汽车用品", "汽车美容",
    )

    /** 第 9 类「汽车美容」在各皮肤中的固定颜色。 */
    const val EXTRA_COLOR: String = "#b58ac4"

    /** 5 套皮肤。 */
    val THEMES: Map<String, Theme> = linkedMapOf(
        "blue" to Theme(
            "blue", "里程蓝", "#55758b",
            listOf("#55758b", "#7e9eaf", "#aec5d0", "#38566c", "#94aab7", "#c1d3dc", "#6d899d", "#dbe5ea"),
        ),
        "forest" to Theme(
            "forest", "森林绿", "#52846d",
            listOf("#789657", "#b1c78e", "#d1dda9", "#485f4b", "#d9bc91", "#9cae9b", "#c79779", "#e1daca"),
        ),
        "purple" to Theme(
            "purple", "丁香紫", "#8972bb",
            listOf("#8a76bd", "#b7a8d4", "#dbd2ec", "#665780", "#b7a1c5", "#c6b9d7", "#9175a8", "#e2dceb"),
        ),
        "orange" to Theme(
            "orange", "暖日橙", "#cd8952",
            listOf("#ca8a54", "#ddb289", "#efcfad", "#8e6647", "#d7b59b", "#e4ceba", "#bb8b71", "#eee2d4"),
        ),
        "graphite" to Theme(
            "graphite", "石墨灰", "#667986",
            listOf("#718592", "#9dacb7", "#c4ced5", "#465c6a", "#98a5aa", "#b5c0c5", "#899aa5", "#dce2e5"),
        ),
    )

    /** 旧分类「维修保养」统一归一为「保养」。 */
    fun normalize(category: String?): String =
        if (category == "维修保养") "保养" else category.orEmpty()

    /**
     * 是否是能源费分类（燃油车叫「油费」，纯电车显示为「电费」）。
     *
     * **存储值始终是「油费」**：9 类分类是全仓契约（配色、统计、CSV、备份、导入判重），
     * 纯电只在显示层按动力类型改名，不新增第 10 类。
     */
    fun isEnergy(category: String?): Boolean = normalize(category) == "油费"

    /**
     * 关键词归类（Web `categoryOf`）：精确匹配优先，随后按固定顺序做关键词匹配。
     * 未识别返回空串。
     */
    fun categoryOf(value: String?, customCategories: List<String> = emptyList()): String {
        val s = value.orEmpty()
        if (s.isEmpty()) return ""
        if (ALL.contains(s) || customCategories.contains(s)) return s
        return when {
            // 充电相关归能源费，但排除「充电宝 / 充电器 / 充电线 / 充电头」这类非车用支出。
            Regex("加油|汽油|柴油|燃油|油费|充电(?!宝|器|线|头)|电费|快充|慢充|补能").containsMatchIn(s) -> "油费"
            Regex("保险|车险|交强").containsMatchIn(s) -> "保险"
            Regex("保养|换油").containsMatchIn(s) -> "保养"
            Regex("高速|过路|过桥|ETC|通行", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "过路费"
            Regex("停车").containsMatchIn(s) -> "停车费"
            Regex("维修|修车|补胎").containsMatchIn(s) -> "维修"
            Regex("罚款|罚单|违章").containsMatchIn(s) -> "罚单"
            Regex("美容|洗车|打蜡|抛光|镀晶|内饰清洁").containsMatchIn(s) -> "汽车美容"
            Regex("汽车用品|车品|汽车装饰").containsMatchIn(s) -> "汽车用品"
            else -> ""
        }
    }

    /** 分类下拉选项：9 类 + 自定义 + 记录中已出现的分类（含旧分类归一）。 */
    fun options(ledger: Ledger): List<String> {
        val seen = LinkedHashSet<String>()
        seen.addAll(ALL)
        ledger.customCategories.orEmpty().forEach { if (it.isNotEmpty()) seen.add(it) }
        ledger.records.forEach { r ->
            val name = normalize(r.category)
            if (name.isNotEmpty()) seen.add(name)
        }
        return seen.toList()
    }

    /** 指定分类的颜色；未知分类走与 Web 相同的 31 进制 hash → HSL。 */
    fun color(themeId: String?, name: String): String {
        val theme = THEMES[themeId] ?: THEMES.getValue("blue")
        val palette = theme.colors + EXTRA_COLOR
        val index = ALL.indexOf(name)
        if (index >= 0) return palette[index]
        var hash = 0u
        for (ch in name) {
            hash = hash * 31u + ch.code.toUInt()
        }
        return "hsl(${hash % 360u} 40% 56%)"
    }
}
