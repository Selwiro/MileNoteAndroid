package com.milenote.android.ui.screens.records

/**
 * 费用明细多选的集合规则（纯函数，便于单测；界面层只持有状态）。
 *
 * 多选始终以「当前筛选结果」为准：筛选变化时用 [pruneSelection] 把已经看不见的记录
 * 从选中集合里去掉，保证「已选 N 条」与实际会改动的记录一致。
 */

/** 点按一条记录：已选则取消，未选则加入。 */
internal fun toggleSelection(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

/** 只保留仍在当前筛选结果里的选中项。 */
internal fun pruneSelection(selected: Set<String>, visibleIds: Set<String>): Set<String> =
    selected.intersect(visibleIds)

/**
 * 是否已全选当前筛选结果。
 *
 * 结果为空时返回 false（不把「没有记录」当成已全选，避免出现「取消全选」这种无效动作）。
 */
internal fun isAllSelected(selected: Set<String>, visibleIds: Set<String>): Boolean =
    visibleIds.isNotEmpty() && visibleIds.all { it in selected }

/** 「全选 / 取消全选」按钮的下一状态：已全选则清空，否则选中全部可见记录。 */
internal fun toggleSelectAll(selected: Set<String>, visibleIds: Set<String>): Set<String> =
    if (isAllSelected(selected, visibleIds)) emptySet() else visibleIds
