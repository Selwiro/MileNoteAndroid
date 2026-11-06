package com.milenote.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes

/**
 * 二级页签（统计 / 明细、待办与记录 / 计划）：三个页签页共用的唯一版式。
 *
 * 统一口径（改动前油耗分析、费用统计、维修保养各写了一份，位置与间距不一致）：
 * - 页签固定在页面顶栏下方，行上下各 6dp，与顶栏 / 内容的关系三页一致；
 * - 页面用 `Column { SecondaryTabs(...); LazyColumn(...) }` 承载内容，
 *   LazyColumn 的 `contentPadding.top` 统一取 [MileNoteDimens.CardGap]，
 *   让页签与下方第一张卡片的间距、以及卡片之间的间距保持一致；
 * - 页签等宽、44dp 高、圆角 [MileNoteShapes.medium]，选中态 `primaryContainer` + 1dp 主色描边。
 *
 * @param labels 页签文案，按显示顺序传入。
 * @param selected 当前选中下标。
 * @param onSelect 点击页签回调。
 */
@Composable
fun SecondaryTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MileNoteDimens.PageHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(MileNoteShapes.medium)
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .border(
                        width = 1.dp,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = MileNoteShapes.medium,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
