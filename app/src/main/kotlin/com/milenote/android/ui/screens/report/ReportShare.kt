package com.milenote.android.ui.screens.report

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.milenote.android.domain.calc.ReportData
import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.domain.format.Format
import com.milenote.android.domain.model.Ledger
import com.milenote.android.domain.model.energyProfile
import com.milenote.android.i18n.DataLabels
import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.platform.FileExporter
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.theme.moneyLarge
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 分享报告的页内遮罩（**不用 ModalBottomSheet**：M3 弹层是独立 window，PixelCopy 抓不到卡片像素）。
 *
 * 遮罩与卡片都在报告页同一个 window 里，截取卡片矩形就是干净的一张竖版图：
 * PNG 写到 `cacheDir/exports/`，`file_paths.xml` 已配该目录，FileProvider 直接分享 / SAF 保存。
 * 捕获失败时提示并退回文本分享。
 */
@Composable
internal fun ReportShareOverlay(
    data: ReportData,
    ledger: Ledger,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) }
    // 卡片在窗口里的像素范围（PixelCopy 用）。放在组合内即可，读到时一定已布局。
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    val fileName = reportFileName(data)

    fun shareText() {
        val bytes = buildReportText(data, ledger).toByteArray(Charsets.UTF_8)
        FileExporter.shareText(context, "$fileName.txt", "text/plain", bytes)
    }

    fun capture(onReady: (Bitmap) -> Unit) {
        if (busy) return
        val activity = context.findActivity()
        val rect = cardBounds
        if (activity == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
            error = tr("图片生成失败，已改为分享文本", "Image could not be generated; sharing text instead")
            shareText()
            return
        }
        busy = true
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        // 卡片已经完整显示在 sheet 里：直接拷这一块窗口像素，不依赖 Compose 的 layer 录制。
        PixelCopy.request(
            activity.window,
            rect,
            bitmap,
            { result ->
                busy = false
                if (result == PixelCopy.SUCCESS) {
                    onReady(bitmap)
                } else {
                    error = tr("图片生成失败，已改为分享文本", "Image could not be generated; sharing text instead")
                    shareText()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val pending = pendingImage
        if (uri != null && pending != null) {
            runCatching { FileExporter.writeTo(context, uri, pngBytes(pending)) }
                .onFailure {
                    error = tr("写入图片失败，请换一个位置重试", "Could not save the image; try another location")
                }
        }
        pendingImage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // 吞掉卡片区域的点击，避免点在卡上把遮罩关掉。
                .clickable(onClick = {}),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tr("分享报告", "Share report"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // 按钮放在预览卡上方：卡高固定，小屏也能先点到动作。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        capture { bitmap ->
                            pendingImage = bitmap
                            saveLauncher.launch("$fileName.png")
                        }
                    },
                    enabled = !busy,
                ) { Text(tr("保存图片", "Save image")) }
                TextButton(
                    onClick = {
                        capture { bitmap ->
                            FileExporter.shareText(context, "$fileName.png", "image/png", pngBytes(bitmap))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(tr("分享图片", "Share image")) }
                TextButton(onClick = { shareText() }, enabled = !busy) {
                    Text(tr("分享文本", "Share text"))
                }
            }
            error?.let { message ->
                MessageBar(message = message, kind = MessageKind.WARNING)
            }
            // 分享卡固定 1× 字号：系统 1.3× 时卡片会变高、被 sheet 裁掉，图片排版也不该跟着系统字号走。
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1f),
            ) {
                ReportShareCard(
                    data = data,
                    ledger = ledger,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            cardBounds = Rect(
                                bounds.left.roundToInt(),
                                bounds.top.roundToInt(),
                                bounds.right.roundToInt(),
                                bounds.bottom.roundToInt(),
                            )
                        },
                )
            }
        }
    }
}

private fun pngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

/** 从 Compose 的 Context 往上找 Activity（PixelCopy 需要它的 window）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun reportFileName(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> "milenote-%04d-%02d-report".format(data.period.year, data.period.month)
    ReportKind.YEAR -> "milenote-%04d-report".format(data.period.year)
    ReportKind.ALL -> "milenote-all-report"
}

/** 分享图的竖版摘要卡（约 340×470dp，保证一屏内完整录制）。 */
@Composable
private fun ReportShareCard(data: ReportData, ledger: Ledger, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val profile = ledger.energyProfile
    val top = data.categories.take(2)
    val total = data.categories.sumOf { it.value }
    Column(
        modifier = modifier
            .width(330.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLow)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr("里程志", "MileNote"),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = reportPeriodTitle(data),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (data.inProgress) {
                tr("截至 %s", "Through %s", shortDate(data.range.to))
            } else {
                shortDate(data.range.from) + " – " + shortDate(data.range.to)
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = tr("支出合计", "Total spend"),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = "${Units.currencySymbol}${Format.money(data.total)}",
            style = MaterialTheme.typography.moneyLarge,
            color = scheme.onSurface,
        )
        Text(
            text = shareDeltaLine(data),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
        )
        Text(
            text = shareMetricLine(data),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
        )
        if (top.isNotEmpty()) {
            Text(
                text = tr("费用构成", "Breakdown"),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            top.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = DataLabels.categoryFor(ledger, item.name, profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = tr(
                            "%s · %s",
                            "%s · %s",
                            percentLabel(item.value, total),
                            "${Units.currencySymbol}${Format.money(item.value)}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }
        }
        data.fuel?.let { fuel ->
            val rate = fuel.rate ?: fuel.estimated
            if (rate != null) {
                Text(
                    text = tr(
                        "油耗 %s %s · 加油 %d 次",
                        "Fuel economy %s %s · %d fills",
                        Format.fmt(rate),
                        Units.consumption,
                        fuel.count,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
            }
        }
        data.electric?.let { electric ->
            val rate = electric.rate ?: electric.estimated
            if (rate != null) {
                Text(
                    text = tr(
                        "电耗 %s %s · 充电 %d 次",
                        "Energy use %s %s · %d charges",
                        Format.fmt(rate),
                        Units.energyConsumption,
                        electric.count,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tr(
                "里程志 · 数据仅存本机",
                "MileNote · data stays on this device",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** 纯文本版报告（分享到聊天 / 备忘录）。 */
internal fun buildReportText(data: ReportData, ledger: Ledger): String {
    val profile = ledger.energyProfile
    val sb = StringBuilder()
    sb.append(tr("里程志", "MileNote")).append(" · ").append(reportPeriodTitle(data)).append('\n')
    sb.append(tr("支出 %s", "Spending %s", moneyText(data.total)))
    shareDeltaLine(data).takeIf { it.isNotBlank() }?.let { sb.append("（").append(it).append("）") }
    sb.append('\n')
    sb.append(shareMetricLine(data)).append('\n')
    if (data.categories.isNotEmpty()) {
        sb.append('\n').append(tr("费用构成", "Breakdown")).append('\n')
        val total = data.categories.sumOf { it.value }
        data.categories.forEach { item ->
            sb.append(DataLabels.categoryFor(ledger, item.name, profile))
                .append(' ').append(moneyText(item.value))
                .append('（').append(percentLabel(item.value, total)).append('）').append('\n')
        }
    }
    val fuel = data.fuel
    val electric = data.electric
    if (fuel != null || electric != null) {
        sb.append('\n').append(tr("能源", "Energy")).append('\n')
        fuel?.let { line ->
            sb.append(energySummary(line, electric = false)).append('\n')
            val rate = line.rate ?: line.estimated
            if (rate != null) {
                sb.append(
                    tr(
                        "油耗 %s %s（%s%s）",
                        "Fuel economy %s %s (%s%s)",
                        Format.fmt(rate),
                        Units.consumption,
                        spanLabel(line.intervals, false),
                        energyDeltaSuffix(line, data),
                    ),
                ).append('\n')
            }
        }
        electric?.let { line ->
            sb.append(energySummary(line, electric = true)).append('\n')
            val rate = line.rate ?: line.estimated
            if (rate != null) {
                sb.append(
                    tr(
                        "电耗 %s %s（%s%s）",
                        "Energy use %s %s (%s%s)",
                        Format.fmt(rate),
                        Units.energyConsumption,
                        spanLabel(line.intervals, true),
                        energyDeltaSuffix(line, data),
                    ),
                ).append('\n')
            }
        }
    }
    return sb.toString().trimEnd()
}

// ---------------------------------------------------------------------------------------------
// 卡片与文本共用的文案片段
// ---------------------------------------------------------------------------------------------

private fun reportPeriodTitle(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> if (I18n.english) {
        "${monthName(data.period.month)} ${data.period.year}"
    } else {
        "${data.period.year} 年 ${data.period.month} 月"
    }
    ReportKind.YEAR -> if (I18n.english) "${data.period.year}" else "${data.period.year} 年"
    ReportKind.ALL -> {
        val first = data.firstDate?.let { com.milenote.android.domain.calc.Dates.parse(it) }
        if (first == null) {
            tr("全部记录", "All records")
        } else if (I18n.english) {
            "${monthName(first.monthValue)} ${first.year} – today"
        } else {
            "%d.%02d – 至今".format(first.year, first.monthValue)
        }
    }
}

private fun shareDeltaLine(data: ReportData): String {
    if (data.period.kind == ReportKind.ALL) {
        return tr(
            "月均 %s · 记录跨度 %s",
            "%s a month · %s of records",
            moneyText(data.monthlyAvg),
            spanMonthsText(data.spanMonths),
        )
    }
    val delta = data.totalDelta ?: return ""
    val label = when (data.period.kind) {
        ReportKind.MONTH -> if (data.inProgress) tr("较上月同期", "vs same span last month") else tr("较上月", "vs last month")
        ReportKind.YEAR -> if (data.inProgress) tr("较去年同期", "vs same span last year") else tr("较上年", "vs last year")
        ReportKind.ALL -> ""
    }
    if (delta.previous <= 0.0) {
        return tr("%s 无支出", "%s: no spending", label)
    }
    if (kotlin.math.abs(delta.diff) < 0.5) return tr("%s 基本持平", "%s about the same", label)
    val arrow = if (delta.diff > 0) "↑" else "↓"
    val percent = delta.percent?.let { " · ${oneDecimal(kotlin.math.abs(it))}%" } ?: ""
    return "$label $arrow ${moneyText(kotlin.math.abs(delta.diff))}$percent"
}

private fun shareMetricLine(data: ReportData): String {
    val parts = mutableListOf(
        tr("记录 %d 笔", "%d entries", data.recordCount),
        tr("里程 %s %s", "Distance %s %s", data.distance?.let { Format.fmt(it) } ?: "—", Units.distanceUnit),
    )
    data.perKm?.let { parts += tr("每公里 %s", "%s per km", moneyText(it)) }
    data.monthlyAvg?.let { parts += tr("月均 %s", "%s a month", moneyText(it)) }
    return parts.joinToString(" · ")
}

private fun energySummary(line: com.milenote.android.domain.calc.ReportEnergyLine, electric: Boolean): String {
    val parts = mutableListOf(
        if (electric) tr("充电 %d 次", "%d charges", line.count) else tr("加油 %d 次", "%d fills", line.count),
    )
    if (line.amount > 0) {
        parts += "${Format.fmt(line.amount)} ${if (electric) Units.energyUnit else Units.volumeUnit}"
    }
    line.unitPrice?.let {
        parts += "${Format.money(it)} ${if (electric) Units.pricePerEnergy else Units.pricePerVolume}"
    }
    return parts.joinToString(" · ")
}

private fun energyDeltaSuffix(
    line: com.milenote.android.domain.calc.ReportEnergyLine,
    data: ReportData,
): String {
    val prev = line.previous?.rate ?: return ""
    val current = line.rate ?: return ""
    val diff = current - prev
    if (kotlin.math.abs(diff) < 0.05) return tr("，基本持平", ", about the same")
    val arrow = if (diff > 0) "↑" else "↓"
    val label = when (data.period.kind) {
        ReportKind.MONTH -> if (data.inProgress) tr("较上月同期", "vs same span last month") else tr("较上月", "vs last month")
        ReportKind.YEAR -> if (data.inProgress) tr("较去年同期", "vs same span last year") else tr("较上年", "vs last year")
        ReportKind.ALL -> ""
    }
    return tr(
        "，%s %s %s",
        ", %s %s %s",
        label,
        arrow,
        Format.fmt(kotlin.math.abs(diff)),
    )
}

private fun spanLabel(intervals: Int, electric: Boolean): String = when {
    electric -> if (intervals == 1) tr("满电法 %d 段", "%d full-charge span", intervals) else tr("满电法 %d 段", "%d full-charge spans", intervals)
    else -> if (intervals == 1) tr("满箱法 %d 段", "%d full-tank span", intervals) else tr("满箱法 %d 段", "%d full-tank spans", intervals)
}

private fun spanMonthsText(months: Int?): String {
    if (months == null || months <= 0) return "—"
    val years = months / 12
    val rest = months % 12
    return when {
        years == 0 -> tr("%d 个月", "%d mo", rest)
        rest == 0 -> tr("%d 年", "%d y", years)
        else -> tr("%d 年 %d 个月", "%d y %d mo", years, rest)
    }
}

private fun moneyText(value: Double?): String =
    if (value == null) "—" else "${Units.currencySymbol}${Format.money(value)}"

private fun oneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return Format.fmt(rounded)
}

private fun percentLabel(value: Double, total: Double): String {
    if (total <= 0.0) return "—"
    return "${oneDecimal(value / total * 100.0)}%"
}

private fun monthName(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)

private fun shortDate(iso: String): String {
    val date = com.milenote.android.domain.calc.Dates.parse(iso) ?: return iso
    return if (I18n.english) {
        "${monthName(date.monthValue)} ${date.dayOfMonth}"
    } else {
        "${date.monthValue} 月 ${date.dayOfMonth} 日"
    }
}
