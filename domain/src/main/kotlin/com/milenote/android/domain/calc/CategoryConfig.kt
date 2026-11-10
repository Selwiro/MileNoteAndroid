package com.milenote.android.domain.calc

import com.milenote.android.domain.model.Ledger

/**
 * 费用分类与项目库的用户配置：隐藏集合 + 显示名映射。
 *
 * 语义（2026-11-10 用户确认）：**只改显示名与新建候选，不改写历史记录里的分类值/项目文字**。
 * 账本存储值永远是规范中文值（`Categories.ALL` 九类契约、配色、统计、CSV、备份、导入判重都依赖它），
 * 因此这里只读配置，不提供任何“改写记录”的函数。
 */
object CategoryConfig {

    /** 不可删除的四个分类（可改名）：油费（纯电显示电费、插混显示能源）/ 保险 / 保养 / 维修。 */
    val PROTECTED: List<String> = listOf("油费", "保险", "保养", "维修")

    /** 分类是否受保护（不可隐藏；`维修保养` 归一为 `保养`）。 */
    fun isProtected(name: String?): Boolean = Categories.normalize(name) in PROTECTED

    /** 被隐藏的内置分类（存储值）。 */
    fun hiddenCategories(ledger: Ledger): Set<String> =
        ledger.hiddenCategories.orEmpty()
            .map { Categories.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /** 分类显示名：用户改名优先（未改名原样返回；纯电/插混的能源名由 DataLabels 再映射）。 */
    fun categoryLabel(ledger: Ledger, name: String?): String {
        val raw = Categories.normalize(name)
        return ledger.categoryLabels.orEmpty()[raw] ?: raw
    }

    /**
     * 新建记录 / 导入分类下拉的可选分类：九类 + 自定义 + 历史分类，再减去隐藏项。
     * 受保护分类即使被误写进 hidden 也强制保留。
     */
    fun pickOptions(ledger: Ledger): List<String> {
        val hidden = hiddenCategories(ledger)
        return Categories.options(ledger).filter { it !in hidden || it in PROTECTED }
    }

    /** 指定分类下被隐藏的项目原始文字。 */
    fun hiddenProjects(ledger: Ledger, category: String?): Set<String> {
        val cat = Categories.normalize(category)
        return ledger.hiddenServiceProjects.orEmpty()[cat].orEmpty().toSet()
    }

    /** 全部分类下被隐藏的项目（跨分类去重）。 */
    fun allHiddenProjects(ledger: Ledger): Set<String> =
        ledger.hiddenServiceProjects.orEmpty().values.flatten().toSet()

    /** 项目显示名：用户改名优先，否则原值（英文映射由 DataLabels 处理）。 */
    fun projectLabel(ledger: Ledger, name: String?): String {
        val raw = name.orEmpty()
        return ledger.serviceProjectLabels.orEmpty()[raw] ?: raw
    }
}
