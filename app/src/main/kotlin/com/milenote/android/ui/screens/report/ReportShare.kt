package com.milenote.android.ui.screens.report

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milenote.android.R
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.calc.ReportData
import com.milenote.android.domain.calc.ReportEnergyLine
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
import com.milenote.android.ui.icons.MileNoteIcons
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** 分享图固定画布：360×480dp @3× = 1080×1440px。 */
private val ShareWidth = 360.dp
private val ShareHeight = 480.dp

private const val ShareDensity = 3f

/** 分享图固定配色（墨蓝），不跟随深浅色主题。 */
private val ShareBgTop = Color(0xFF0B1620)
private val ShareBgMid = Color(0xFF122C36)
private val ShareBgEnd = Color(0xFF1A414D)
private val ShareInk = Color(0xFFF4F8FA)
private val ShareAccent = Color(0xFF9CC7E4)

/**
 * 分享报告的页内浮层。
 *
 * 分享卡按 **360×480dp @ 3×** 离屏渲染成 1080×1440 的位图：
 * 卡片组合在 density=3 的隐藏层里，用 `GraphicsLayer` 录制后 `toImageBitmap()`，
 * 因此导出尺寸与设备屏幕无关。预览直接显示这张位图，所见即所得。
 *
 * PNG 写到 `cacheDir/exports/`，`file_paths.xml` 已配该目录，FileProvider 直接分享 / SAF 保存。
 * 录制失败时提示并退回文本分享。
 */
@Composable
internal fun ReportShareOverlay(
    data: ReportData,
    ledger: Ledger,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 待保存的图片先落成临时文件：SAF 是另一个 Activity，期间旋转 / 进程重建会丢组合内状态。
    val pendingFile = remember {
        java.io.File(context.cacheDir, "exports/pending-report.png")
    }
    val exportLayer = rememberGraphicsLayer()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val fileName = reportFileName(data)

    fun shareText() {
        val bytes = buildReportText(data, ledger).toByteArray(Charsets.UTF_8)
        FileExporter.shareText(context, "$fileName.txt", "text/plain", bytes)
    }

    fun pngBytes(image: ImageBitmap): ByteArray {
        val out = ByteArrayOutputStream()
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun imageOrFallback(): ImageBitmap? {
        val image = preview
        if (image == null) {
            error = tr("图片生成失败，已改为分享文本", "Image could not be generated; sharing text instead")
            shareText()
        }
        return image
    }

    // 组合完成后等两帧（布局 + 绘制），确保离屏卡已经 record 一次，再抓图。
    LaunchedEffect(data) {
        preview = null
        withFrameNanos { }
        withFrameNanos { }
        preview = runCatching { exportLayer.toImageBitmap() }.getOrNull()
        preview?.let { android.util.Log.d("ReportShare", "captured ${it.width}x${it.height}") }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = pendingFile.readBytes()
                FileExporter.writeTo(context, uri, bytes)
            }.onFailure {
                error = tr("写入图片失败，请换一个位置重试", "Could not save the image; try another location")
            }
        }
        pendingFile.delete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onDismiss),
    ) {
        // 离屏导出卡：alpha 0.01 不可见，但仍参与绘制，GraphicsLayer 才能录到内容。
        CompositionLocalProvider(LocalDensity provides Density(ShareDensity, 1f)) {
            Box(
                modifier = Modifier
                    .size(ShareWidth, ShareHeight)
                    .alpha(0.01f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            exportLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
                            drawLayer(exportLayer)
                        },
                ) {
                    ReportShareCard(data = data, ledger = ledger, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    // 吞掉卡片区域的点击，避免点在卡上把遮罩关掉。
                    .clickable(onClick = {}),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tr("分享报告", "Share report"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MileNoteIcons.Close,
                            contentDescription = tr("关闭分享", "Close share"),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val image = imageOrFallback() ?: return@TextButton
                            busy = true
                            scope.launch {
                                runCatching {
                                    pendingFile.parentFile?.mkdirs()
                                    pendingFile.writeBytes(pngBytes(image))
                                }.onFailure {
                                    busy = false
                                    error = tr(
                                        "图片生成失败，已改为分享文本",
                                        "Image could not be generated; sharing text instead",
                                    )
                                    shareText()
                                    return@launch
                                }
                                busy = false
                                saveLauncher.launch("$fileName.png")
                            }
                        },
                        enabled = !busy,
                    ) { Text(tr("保存图片", "Save image")) }
                    TextButton(
                        onClick = {
                            val image = imageOrFallback() ?: return@TextButton
                            busy = true
                            scope.launch {
                                runCatching {
                                    FileExporter.shareText(
                                        context,
                                        "$fileName.png",
                                        "image/png",
                                        pngBytes(image),
                                    )
                                }.onFailure {
                                    error = tr(
                                        "图片生成失败，已改为分享文本",
                                        "Image could not be generated; sharing text instead",
                                    )
                                    shareText()
                                }
                                busy = false
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
                Text(
                    text = tr(
                        "1080 × 1440 · PNG · 固定 3:4",
                        "1080 × 1440 · PNG · fixed 3:4",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val image = preview
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = tr("分享图预览", "Share image preview"),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = 300.dp)
                            .fillMaxWidth(0.86f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(18.dp),
                            )
                            .padding(bottom = 10.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = 300.dp)
                            .fillMaxWidth(0.86f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr("正在生成…", "Rendering…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun reportFileName(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> "milenote-%04d-%02d-report".format(data.period.year, data.period.month)
    ReportKind.YEAR -> "milenote-%04d-report".format(data.period.year)
    ReportKind.ALL -> "milenote-all-report"
}

// ---------------------------------------------------------------------------------------------
// 分享卡（360×480dp，固定墨蓝）
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReportShareCard(data: ReportData, ledger: Ledger, modifier: Modifier = Modifier) {
    val profile = ledger.energyProfile
    val vehicle = DataLabels.vehicleName(ledger.vehicle.name)
    val bars = shareCompareBars(data)
    val stats = shareStats(data)
    val categories = data.categories
    val shown = categories.take(4)
    val total = categories.sumOf { it.value }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(ShareBgTop, ShareBgMid, ShareBgEnd),
                ),
            ),
    ) {
        // 右上角柔光，给纯色底一点层次（不画水印/公路，缩略图会变脏）。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(220.dp)
                .height(220.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(ShareAccent.copy(alpha = 0.20f), Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp),
        ) {
            // 品牌行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ShareInk),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tr("里程志", "MileNote"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ShareInk,
                    letterSpacing = 0.3.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = sharePeriodLabel(data),
                    fontSize = 9.sp,
                    color = Color(0xFFDCEBF3),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ShareInk.copy(alpha = 0.10f))
                        .border(1.dp, ShareInk.copy(alpha = 0.08f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MileNoteIcons.Car,
                    contentDescription = null,
                    tint = ShareInk.copy(alpha = 0.50f),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = vehicle,
                    fontSize = 9.sp,
                    color = ShareInk.copy(alpha = 0.50f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = shareSpendLabel(data),
                fontSize = 9.sp,
                color = ShareInk.copy(alpha = 0.50f),
                letterSpacing = 1.sp,
            )
            val money = Format.money(data.total)
            val dot = money.indexOf('.')
            val intPart = if (dot >= 0) money.substring(0, dot) else money
            val decPart = if (dot >= 0) money.substring(dot) else ""
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = Units.currencySymbol,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = ShareInk.copy(alpha = 0.86f),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = intPart,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ShareInk,
                    letterSpacing = (-1.3).sp,
                    maxLines = 1,
                    softWrap = false,
                )
                if (decPart.isNotEmpty()) {
                    Text(
                        text = decPart,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = ShareInk.copy(alpha = 0.72f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            ShareCompareChart(bars = bars)

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                stats.forEachIndexed { index, stat ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .height(34.dp)
                                .width(1.dp)
                                .background(ShareInk.copy(alpha = 0.10f)),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = stat.value,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ShareInk,
                                maxLines = 1,
                                softWrap = false,
                            )
                            if (stat.unit != null) {
                                Text(
                                    text = " ${stat.unit}",
                                    fontSize = 9.sp,
                                    color = ShareInk.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                imageVector = stat.icon,
                                contentDescription = null,
                                tint = ShareAccent.copy(alpha = 0.75f),
                                modifier = Modifier.size(9.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stat.label,
                                fontSize = 9.sp,
                                color = ShareInk.copy(alpha = 0.50f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr("钱花在哪", "Where it went"),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ShareInk,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = tr(
                        "共 %s · %d 个分类",
                        "%s · %d categories",
                        "${Units.currencySymbol}${Format.money(total)}",
                        categories.size,
                    ),
                    fontSize = 8.sp,
                    color = ShareInk.copy(alpha = 0.42f),
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            shown.forEach { item ->
                ShareCategoryRow(
                    name = DataLabels.categoryFor(ledger, item.name, profile),
                    value = item.value,
                    fraction = shareFraction(item.value, shown.maxOfOrNull { it.value } ?: 0.0),
                    percent = sharePercent(item.value, total),
                    color = shareCategoryColor(item.name),
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            shareEnergyLine(data)?.let { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ShareInk.copy(alpha = 0.05f))
                        .border(1.dp, ShareInk.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = line.icon,
                        contentDescription = null,
                        tint = ShareAccent.copy(alpha = 0.85f),
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = line.left,
                        fontSize = 10.sp,
                        color = ShareInk,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = line.right,
                        fontSize = 8.sp,
                        color = ShareInk.copy(alpha = 0.50f),
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ShareInk.copy(alpha = 0.08f)),
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = shareRangeLabel(data),
                    fontSize = 8.sp,
                    color = ShareInk.copy(alpha = 0.36f),
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "MileNote",
                    fontSize = 8.sp,
                    color = ShareInk.copy(alpha = 0.36f),
                    maxLines = 1,
                )
            }
        }
    }
}

private data class ShareBar(val label: String, val value: Double, val valueText: String, val current: Boolean)

@Composable
private fun ShareCompareChart(bars: List<ShareBar>) {
    val max = bars.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEach { bar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bar.label,
                    fontSize = 9.sp,
                    fontWeight = if (bar.current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (bar.current) ShareInk else ShareInk.copy(alpha = 0.50f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.width(30.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(ShareInk.copy(alpha = 0.07f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(shareFraction(bar.value, max))
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(
                                if (bar.current) ShareAccent else ShareAccent.copy(alpha = 0.34f),
                            ),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = bar.valueText,
                    fontSize = if (bar.current) 10.sp else 9.sp,
                    fontWeight = if (bar.current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (bar.current) ShareInk else ShareInk.copy(alpha = 0.58f),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

@Composable
private fun ShareCategoryRow(
    name: String,
    value: Double,
    fraction: Float,
    percent: String,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color.copy(alpha = 0.17f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = name,
            fontSize = 10.sp,
            color = ShareInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(62.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ShareInk.copy(alpha = 0.09f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${Units.currencySymbol}${Format.money(value)}",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = ShareInk,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = percent,
            fontSize = 8.sp,
            color = ShareInk.copy(alpha = 0.48f),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

private data class ShareStat(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val value: String,
    val unit: String?,
    val label: String,
)

private fun shareStats(data: ReportData): List<ShareStat> {
    val third = if (data.period.kind == ReportKind.ALL) {
        ShareStat(
            icon = MileNoteIcons.Calendar,
            value = data.monthlyAvg?.let { Format.fmt(kotlin.math.round(it)) } ?: "—",
            unit = if (data.monthlyAvg != null) Units.money else null,
            label = tr("月均", "Monthly"),
        )
    } else {
        ShareStat(
            icon = MileNoteIcons.Calendar,
            value = Format.fmt(data.daily),
            unit = Units.money,
            label = tr("日均", "Daily avg"),
        )
    }
    return listOf(
        ShareStat(
            icon = MileNoteIcons.Road,
            value = data.distance?.let { Format.fmt(it) } ?: "—",
            unit = if (data.distance != null) Units.distanceUnit else null,
            label = tr("里程", "Distance"),
        ),
        ShareStat(
            icon = MileNoteIcons.Wallet,
            value = data.perKm?.let { Format.money(it) } ?: "—",
            unit = if (data.perKm != null) Units.money else null,
            label = tr("每公里", "Per km"),
        ),
        third,
    )
}

/**
 * 分享图的对比柱：本期 + 上一期（+ 去年同月 / 最近几年）。
 *
 * - 月报：本期 / 上月 / 去年（有数才画）；
 * - 年报：本期 / 上年；
 * - 累计：最近最多 5 年。
 */
private fun shareCompareBars(data: ReportData): List<ShareBar> {
    val money: (Double) -> String = { "${Units.currencySymbol}${Format.money(it)}" }
    return when (data.period.kind) {
        ReportKind.MONTH -> buildList {
            add(ShareBar(tr("本期", "Now"), data.total, money(data.total), current = true))
            data.totalDelta?.previous?.takeIf { it > 0.0 }?.let {
                add(ShareBar(tr("上月", "Prev"), it, money(it), current = false))
            }
            data.yearAgoDelta?.previous?.takeIf { it > 0.0 }?.let {
                add(ShareBar(tr("去年", "Yr-1"), it, money(it), current = false))
            }
        }
        ReportKind.YEAR -> buildList {
            add(ShareBar(tr("本期", "Now"), data.total, money(data.total), current = true))
            data.totalDelta?.previous?.takeIf { it > 0.0 }?.let {
                add(ShareBar(tr("上年", "Prev"), it, money(it), current = false))
            }
        }
        ReportKind.ALL -> data.yearlyTrend.takeLast(5).mapIndexed { index, item ->
            ShareBar(
                label = item.year.toString(),
                value = item.total,
                valueText = money(item.total),
                current = index == data.yearlyTrend.takeLast(5).lastIndex,
            )
        }
    }
}

private data class ShareEnergy(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val left: String,
    val right: String,
)

private fun shareEnergyLine(data: ReportData): ShareEnergy? {
    data.fuel?.let { fuel ->
        val rate = fuel.rate ?: fuel.estimated ?: return@let
        return ShareEnergy(
            icon = MileNoteIcons.Fuel,
            left = tr("油耗 %s %s", "Fuel %s %s", Format.fmt(rate), Units.consumption),
            right = if (fuel.count == 1) {
                tr(
                    "加油 1 次 · 均价 %s %s",
                    "1 fill · %s %s avg",
                    Format.money(fuel.unitPrice ?: 0.0),
                    Units.pricePerVolume,
                )
            } else {
                tr(
                    "加油 %d 次 · 均价 %s %s",
                    "%d fills · %s %s avg",
                    fuel.count,
                    Format.money(fuel.unitPrice ?: 0.0),
                    Units.pricePerVolume,
                )
            },
        )
    }
    data.electric?.let { electric ->
        val rate = electric.rate ?: electric.estimated ?: return@let
        return ShareEnergy(
            icon = MileNoteIcons.VehicleElectric,
            left = tr("电耗 %s %s", "Energy %s %s", Format.fmt(rate), Units.energyConsumption),
            right = if (electric.count == 1) {
                tr(
                    "充电 1 次 · 均价 %s %s",
                    "1 charge · %s %s avg",
                    Format.money(electric.unitPrice ?: 0.0),
                    Units.pricePerEnergy,
                )
            } else {
                tr(
                    "充电 %d 次 · 均价 %s %s",
                    "%d charges · %s %s avg",
                    electric.count,
                    Format.money(electric.unitPrice ?: 0.0),
                    Units.pricePerEnergy,
                )
            },
        )
    }
    return null
}

private fun sharePeriodLabel(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> if (I18n.english) {
        "${monthName(data.period.month)} ${data.period.year} · Month"
    } else {
        "${data.period.year} 年 ${data.period.month} 月 · 月报"
    }
    ReportKind.YEAR -> if (I18n.english) "${data.period.year} · Year" else "${data.period.year} 年 · 年报"
    ReportKind.ALL -> tr("全部记录", "All records")
}

private fun shareSpendLabel(data: ReportData): String = when (data.period.kind) {
    ReportKind.MONTH -> tr("本月支出", "This month")
    ReportKind.YEAR -> tr("本年支出", "This year")
    ReportKind.ALL -> tr("累计支出", "All time")
}

private fun shareRangeLabel(data: ReportData): String {
    val fromIso = if (data.period.kind == ReportKind.ALL) {
        data.firstDate ?: data.range.to
    } else {
        data.range.from
    }
    val start = Dates.parse(fromIso) ?: return ""
    val to = Dates.parse(data.range.to) ?: return ""
    return "%d.%02d.%02d – %d.%02d.%02d · %s".format(
        start.year,
        start.monthValue,
        start.dayOfMonth,
        to.year,
        to.monthValue,
        to.dayOfMonth,
        tr("%d 笔记录", "%d entries", data.recordCount),
    )
}

private fun shareCategoryColor(name: String): Color {
    val index = com.milenote.android.ui.theme.MileNoteCategoryNames.indexOf(
        if (name == "维修保养") "保养" else name,
    )
    return if (index >= 0) {
        com.milenote.android.ui.theme.DarkCategoryColors[index]
    } else {
        Color(0xFFA9B6C9)
    }
}

private fun shareFraction(value: Double, max: Double): Float {
    if (!value.isFinite() || !max.isFinite() || max <= 0.0 || value <= 0.0) return 0f
    return (value / max).toFloat().coerceIn(0f, 1f)
}

private fun sharePercent(value: Double, total: Double): String {
    if (total <= 0.0 || !value.isFinite()) return "—"
    val percent = kotlin.math.round(value / total * 1000.0) / 10.0
    return if (percent == kotlin.math.floor(percent)) "${percent.toInt()}%" else "$percent%"
}

// ---------------------------------------------------------------------------------------------
// 纯文本版报告（分享到聊天 / 备忘录）
// ---------------------------------------------------------------------------------------------

/** 括号按语言：中文全角、英文半角并留一个空格。 */
private fun parenthesize(text: String): String =
    if (I18n.english) " (" + text + ")" else "\uFF08" + text + "\uFF09"

/** 纯文本版报告（分享到聊天 / 备忘录）。 */
internal fun buildReportText(data: ReportData, ledger: Ledger): String {
    val profile = ledger.energyProfile
    val sb = StringBuilder()
    sb.append(tr("里程志", "MileNote")).append(" · ").append(reportPeriodTitle(data))
    if (data.inProgress) {
        sb.append(parenthesize(tr("截至 %s", "through %s", shortDate(data.range.to))))
    }
    sb.append('\n')
    sb.append(tr("支出 %s", "Spending %s", moneyText(data.total)))
    shareDeltaLine(data).takeIf { it.isNotBlank() }?.let { sb.append(parenthesize(it)) }
    sb.append('\n')
    sb.append(shareMetricLine(data)).append('\n')
    if (data.categories.isNotEmpty()) {
        sb.append('\n').append(tr("费用构成", "Breakdown")).append('\n')
        val total = data.categories.sumOf { it.value }
        data.categories.forEach { item ->
            sb.append(DataLabels.categoryFor(ledger, item.name, profile))
                .append(' ').append(moneyText(item.value))
                .append(parenthesize(percentLabel(item.value, total))).append('\n')
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
                val detail = listOf(spanLabel(line.intervals, false), energyDelta(line, data))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                sb.append(
                    tr(
                        "油耗 %s %s%s",
                        "Fuel economy %s %s%s",
                        Format.fmt(rate),
                        Units.consumption,
                        parenthesize(detail),
                    ),
                ).append('\n')
            }
        }
        electric?.let { line ->
            sb.append(energySummary(line, electric = true)).append('\n')
            val rate = line.rate ?: line.estimated
            if (rate != null) {
                val detail = listOf(spanLabel(line.intervals, true), energyDelta(line, data))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                sb.append(
                    tr(
                        "电耗 %s %s%s",
                        "Energy use %s %s%s",
                        Format.fmt(rate),
                        Units.energyConsumption,
                        parenthesize(detail),
                    ),
                ).append('\n')
            }
        }
    }
    futureRecordNote(data)?.let { sb.append('\n').append(it).append('\n') }
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
        return if (!data.hasEarlierRecords) {
            tr("记录从本期开始", "Data starts with this period")
        } else {
            tr("%s 无支出", "%s: no spending", label)
        }
    }
    if (abs(delta.diff) < 0.5) return tr("%s 基本持平", "%s about the same", label)
    val arrow = if (delta.diff > 0) "↑" else "↓"
    val percent = delta.percent?.let { " · ${oneDecimal(abs(it))}%" } ?: ""
    return "$label $arrow ${moneyText(abs(delta.diff))}$percent"
}

private fun shareMetricLine(data: ReportData): String {
    val parts = mutableListOf(
        tr("记录 %d 笔", "%d entries", data.recordCount),
        tr("里程 %s %s", "Distance %s %s", data.distance?.let { Format.fmt(it) } ?: "—", Units.distanceUnit),
    )
    data.perKm?.let { parts += tr("每公里 %s", "%s per km", moneyText(it)) }
    if (data.period.kind != ReportKind.ALL) {
        data.monthlyAvg?.let { parts += tr("月均 %s", "%s a month", moneyText(it)) }
    }
    return parts.joinToString(" · ")
}

private fun energySummary(line: ReportEnergyLine, electric: Boolean): String {
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

private fun energyDelta(line: ReportEnergyLine, data: ReportData): String {
    val prev = line.previous?.rate ?: return ""
    val current = line.rate ?: return ""
    val diff = current - prev
    if (abs(diff) < 0.05) return tr("基本持平", "about the same")
    val arrow = if (diff > 0) "↑" else "↓"
    val label = when (data.period.kind) {
        ReportKind.MONTH -> if (data.inProgress) tr("较上月同期", "vs same span last month") else tr("较上月", "vs last month")
        ReportKind.YEAR -> if (data.inProgress) tr("较去年同期", "vs same span last year") else tr("较上年", "vs last year")
        ReportKind.ALL -> ""
    }
    return tr("%s %s %s", "%s %s %s", label, arrow, Format.fmt(abs(diff)))
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

/** `2026 年 11 月 13 日` / `Nov 13, 2026`：分享图独立可读，必须带年份。 */
private fun longDate(iso: String): String {
    val date = com.milenote.android.domain.calc.Dates.parse(iso) ?: return iso
    return if (I18n.english) {
        "${monthName(date.monthValue)} ${date.dayOfMonth}, ${date.year}"
    } else {
        "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
    }
}

private fun shortDate(iso: String): String {
    val date = com.milenote.android.domain.calc.Dates.parse(iso) ?: return iso
    return if (I18n.english) {
        "${monthName(date.monthValue)} ${date.dayOfMonth}"
    } else {
        "${date.monthValue} 月 ${date.dayOfMonth} 日"
    }
}
