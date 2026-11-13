package com.milenote.android.ui.screens.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.milenote.android.i18n.tr
import com.milenote.android.ui.icons.MileNoteIcons
import java.time.LocalDate
import java.time.YearMonth

/**
 * 报告的周期选择弹层：月报给「年 + 12 个月宫格」，年报给年份列表。
 *
 * 未来期置灰（不能选），有记录的年 / 月带一个小圆点，避免用户在一堆空格子里试。
 *
 * @param kind 当前视角（[com.milenote.android.domain.calc.ReportKind.MONTH] / `YEAR`）
 * @param selectedYear 打开时定位到的年份
 * @param selectedMonth 打开时定位到的月份（年报忽略）
 * @param firstYear 账本首条记录年（用作年份列表下界）
 * @param monthsWithData 有记录的 `YYYY-MM`，画小圆点
 * @param yearsWithData 有记录的年份，画小圆点
 */
@Composable
internal fun ReportPeriodPicker(
    isMonth: Boolean,
    selectedYear: Int,
    selectedMonth: Int,
    firstYear: Int,
    monthsWithData: Set<String>,
    yearsWithData: Set<Int>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (year: Int, month: Int) -> Unit,
) {
    var year by remember { mutableIntStateOf(selectedYear) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isMonth) tr("选择月份", "Pick a month") else tr("选择年份", "Pick a year"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                YearStepper(
                    year = year,
                    canPrev = year > firstYear,
                    canNext = year < today.year,
                    onPrev = { year -= 1 },
                    onNext = { year += 1 },
                )
                if (isMonth) {
                    MonthGrid(
                        year = year,
                        selectedMonth = if (year == selectedYear) selectedMonth else 0,
                        today = today,
                        monthsWithData = monthsWithData,
                        onPick = { month -> onSelect(year, month) },
                    )
                } else {
                    YearList(
                        from = firstYear,
                        to = today.year,
                        selected = year,
                        yearsWithData = yearsWithData,
                        onPick = { picked -> onSelect(picked, 1) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

@Composable
private fun YearStepper(
    year: Int,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onPrev, enabled = canPrev) {
            Icon(MileNoteIcons.Back, contentDescription = tr("上一年", "Previous year"))
        }
        Text(
            text = tr("%d 年", "%d", year),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(MileNoteIcons.Forward, contentDescription = tr("下一年", "Next year"))
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int,
    selectedMonth: Int,
    today: LocalDate,
    monthsWithData: Set<String>,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..12).chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { month ->
                    val future = YearMonth.of(year, month).isAfter(YearMonth.from(today))
                    MonthCell(
                        month = month,
                        selected = month == selectedMonth,
                        hasData = monthsWithData.contains("%04d-%02d".format(year, month)),
                        enabled = !future,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(month) },
                    )
                }
                // 最后一行补齐占位，保持四列等宽。
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MonthCell(
    month: Int,
    selected: Boolean,
    hasData: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        selected -> scheme.primaryContainer
        enabled -> scheme.surfaceContainerHigh
        else -> scheme.surfaceContainerLow
    }
    val content = when {
        selected -> scheme.onPrimaryContainer
        enabled -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = tr("%d 月", "%d", month),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .then(
                        if (hasData) Modifier.background(scheme.primary, CircleShape)
                        else Modifier,
                    ),
            )
        }
    }
}

@Composable
private fun YearList(
    from: Int,
    to: Int,
    selected: Int,
    yearsWithData: Set<Int>,
    onPick: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (to downTo from).forEach { year ->
            val isSelected = year == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onPick(year) }
                    .background(
                        if (isSelected) scheme.primaryContainer else scheme.surfaceContainerLow,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("%d 年", "%d", year),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (yearsWithData.contains(year)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(scheme.primary, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (isSelected) {
                    Icon(
                        MileNoteIcons.Check,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
