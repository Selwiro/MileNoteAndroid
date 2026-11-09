package com.milenote.android.ui.screens.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.milenote.android.domain.ocr.FuelReceipt
import com.milenote.android.domain.ocr.FuelReceiptParser
import com.milenote.android.i18n.Units
import com.milenote.android.i18n.tr
import com.milenote.android.platform.ocr.OcrException
import com.milenote.android.platform.ocr.OcrRecognizer
import com.milenote.android.platform.ocr.TesseractLatinOcrRecognizer
import com.milenote.android.platform.ocr.TesseractOcrRecognizer
import com.milenote.android.ui.components.LoadingState
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OCR 页面的四个阶段。 */
private enum class OcrStage { Idle, Recognizing, Result, Error }

/** OCR 输入图最长边上限（像素）：既保证小票文字可辨，又控制内存与耗时。 */
private const val MAX_OCR_SIDE = 2000

/** 小图（如加油机屏幕照片）放大到的最长边；Tesseract 对 300px 级图片几乎无法识别。 */
private const val MIN_OCR_SIDE = 1400

/** 小图放大倍数上限，避免过度插值。 */
private const val MAX_OCR_UPSCALE = 5f

/** 相册图片解码时的最长边上限（像素）。 */
private const val MAX_DECODE_SIDE = 2400

/**
 * 单次识别的时间预算：低清/七段屏照片会触发「多增强变体 × 中英双引擎」兜底，
 * 最坏情况十几轮整图 OCR。超过预算就停止重试，保留当前得分最高的结果。
 */
private const val OCR_TIME_BUDGET_MS = 20_000L

/**
 * 加油小票拍照识别页。
 *
 * 交互：CameraX 预览 → 拍照（或相册选图）→ Tesseract 中文 OCR → [FuelReceiptParser] 解析字段
 * → 结果确认面板 → 通过 [onResult] 把 [FuelReceipt] 回填给编辑器。
 *
 * 隐私：图片只在内存中处理（识别完立即 `recycle()`），**不落盘、不上传**；
 * 相册图片也只读取一次，不复制到应用目录。
 *
 * ```kotlin
 * OcrCaptureScreen(
 *     onResult = { receipt ->
 *         editor.prefill(receipt)   // 用 receipt.amount / liters / date / odometer
 *         showOcrScreen = false
 *     },
 *     onClose = { showOcrScreen = false },
 * )
 * ```
 *
 * @param onResult 用户点「使用结果」后回调解析出的字段（可能为空字段，调用方应允许手动修改）。
 * @param onClose 关闭页面（左上角 X / 返回）。
 * @param modifier 外部布局修饰符。
 * @param recognizer 识别实现，默认 Tesseract 中文（可注入假实现做测试）。
 */
@Composable
fun OcrCaptureScreen(
    onResult: (FuelReceipt) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recognizer: OcrRecognizer = TesseractOcrRecognizer,
    latinRecognizer: OcrRecognizer = TesseractLatinOcrRecognizer,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var stage by remember { mutableStateOf(OcrStage.Idle) }
    var receipt by remember { mutableStateOf<FuelReceipt?>(null) }
    var recognizedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // 离开页面时立刻释放相机，避免占用导致其他应用/下次进入黑屏。
    DisposableEffect(Unit) {
        onDispose {
            cameraProvider.value?.let { provider -> runCatching { provider.unbindAll() } }
        }
    }

    fun fail(message: String) {
        errorMessage = message
        stage = OcrStage.Error
    }

    fun resetToIdle() {
        stage = OcrStage.Idle
        receipt = null
        recognizedText = ""
        errorMessage = null
    }

    /** 线程切换 + 识别 + 解析的统一入口；[load] 在 IO 线程执行并返回（位图, 需要旋转的角度）。 */
    fun launchRecognition(load: suspend () -> Pair<Bitmap?, Int>) {
        if (stage == OcrStage.Recognizing) return
        stage = OcrStage.Recognizing
        errorMessage = null
        scope.launch {
            var prepared: Bitmap? = null
            try {
                val (bitmap, rotation) = withContext(Dispatchers.IO) { load() }
                if (bitmap == null) {
                    fail(tr("无法读取图片，请换一张或重新拍摄", "Could not read the image — try another photo or retake"))
                    return@launch
                }
                prepared = withContext(Dispatchers.Default) { prepareForOcr(bitmap, rotation) }
                if (prepared !== bitmap) {
                    withContext(Dispatchers.Default) { if (!bitmap.isRecycled) bitmap.recycle() }
                }
                var bestText = ""
                var bestParsed = FuelReceiptParser.parse("")
                var bestScore = -1
                val allTexts = mutableListOf<String>()
                val recognitionStartedAt = SystemClock.elapsedRealtime()
                suspend fun tryWith(engine: OcrRecognizer, image: Bitmap) {
                    // 预算用尽就不再尝试新变体/引擎；已跑过的结果仍然有效。
                    if (SystemClock.elapsedRealtime() - recognitionStartedAt > OCR_TIME_BUDGET_MS) return
                    val ocr = engine.recognize(image)
                    if (ocr.text.isNotBlank()) allTexts.add(ocr.text)
                    val parsed = FuelReceiptParser.parse(ocr.text, ocr.lines)
                    val score = parseScore(parsed)
                    if (score > bestScore || (score == bestScore && ocr.text.length > bestText.length)) {
                        bestScore = score
                        bestText = ocr.text
                        bestParsed = parsed
                    }
                }
                tryWith(recognizer, prepared)
                // 普通小票一次就够；加油机屏幕等低清/七段数码管照片再尝试增强图 + 拉丁数字模型。
                if (bestScore < 7) {
                    val enhanced = withContext(Dispatchers.Default) { buildEnhancedVariants(prepared) }
                    try {
                        for (image in enhanced) {
                            tryWith(recognizer, image)
                            if (bestScore >= 7) break
                        }
                        if (bestScore < 7) {
                            tryWith(latinRecognizer, prepared)
                            for (image in enhanced) {
                                if (bestScore >= 7) break
                                tryWith(latinRecognizer, image)
                            }
                        }
                    } finally {
                        withContext(Dispatchers.Default) { enhanced.forEach { if (!it.isRecycled) it.recycle() } }
                    }
                }
                // 加油机七段数码管：Tesseract 同样会丢小数点/把 1 读成 L，用多轮 OCR 的数字行兜底还原。
                if (bestParsed.amount == null || bestParsed.liters == null) {
                    FuelReceiptParser.parsePumpDisplay(allTexts)?.let { pump ->
                        bestParsed = bestParsed.copy(
                            amount = pump.amount ?: bestParsed.amount,
                            liters = pump.liters ?: bestParsed.liters,
                            unitPrice = pump.unitPrice ?: bestParsed.unitPrice,
                            warnings = (
                                bestParsed.warnings.filterNot { warning ->
                                    // domain 解析器的警告文案随语言切换（中文「实付金额/加油量」/
                                    // 英文 "Paid amount/Fuel amount"），两种都匹配以保持过滤有效。
                                    (pump.amount != null &&
                                        (warning.contains("实付金额") || warning.contains("Paid amount"))) ||
                                        (pump.liters != null &&
                                            (warning.contains("加油量") || warning.contains("Fuel amount")))
                                } + pump.warnings
                            ).distinct(),
                        )
                        bestText = allTexts.filter { it.isNotBlank() }.distinct().joinToString("\n")
                    }
                }
                val pumpDetected = allTexts.any {
                    it.contains("提枪加油") || (it.contains("金额") && it.contains("单价"))
                }
                bestParsed = FuelReceiptParser.reconcile(bestParsed, pumpDetected)
                recognizedText = bestText
                receipt = bestParsed
                stage = OcrStage.Result
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                fail(e.message ?: tr("小票识别失败，请重拍", "Receipt recognition failed — retake the photo"))
            } catch (e: Exception) {
                fail(
                    tr(
                        "小票识别失败：%s",
                        "Receipt recognition failed: %s",
                        e.message ?: tr("请检查光线和清晰度后重试", "check lighting and focus, then retry"),
                    ),
                )
            } finally {
                withContext(Dispatchers.Default) {
                    prepared?.takeIf { !it.isRecycled }?.recycle()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            errorMessage = tr(
                "未授予相机权限，可以改用「从相册选择」",
                "Camera permission not granted — you can pick from the gallery instead",
            )
            stage = OcrStage.Error
        } else {
            errorMessage = null
            if (stage == OcrStage.Error) stage = OcrStage.Idle
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchRecognition {
            val bitmap = decodeImage(context, uri)
            bitmap to 0
        }
    }

    /** 调起系统相册（Photo Picker，API 26 起自动回退到文档选择器）。 */
    fun pickFromGallery() {
        runCatching {
            pickImageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure {
            fail(
                tr(
                    "无法打开相册：%s",
                    "Could not open the gallery: %s",
                    it.message ?: tr("请重试", "try again"),
                ),
            )
        }
    }

    /** 请求相机权限（已拒绝过会走系统二次确认；被永久拒绝时提示改用相册）。 */
    fun requestCameraPermission() {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /** 拍照：成功后把 ImageProxy 转 Bitmap 并识别，失败给中文提示。 */
    fun capturePhoto() {
        if (stage == OcrStage.Recognizing) return
        if (!hasCameraPermission) {
            requestCameraPermission()
            return
        }
        stage = OcrStage.Recognizing
        errorMessage = null
        val executor = ContextCompat.getMainExecutor(context)
        try {
            imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = runCatching { image.toBitmap() }.getOrNull()
                    runCatching { image.close() }
                    if (bitmap == null) {
                        fail(tr("拍照结果无法解析，请重试", "Could not process the photo — try again"))
                    } else {
                        launchRecognition { bitmap to rotation }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    fail(tr("拍照失败：%s", "Photo capture failed: %s", exception.message ?: tr("请重试", "try again")))
                }
            })
        } catch (e: Exception) {
            fail(tr("拍照失败：%s", "Photo capture failed: %s", e.message ?: tr("相机未就绪，请重试", "camera not ready — try again")))
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OcrHeader(onClose = onClose)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = MileNoteDimens.PageHorizontal),
            ) {
                // 预览 / 占位
                if (hasCameraPermission && cameraError == null) {
                    CameraPreview(
                        imageCapture = imageCapture,
                        cameraProvider = cameraProvider,
                        lifecycleOwner = lifecycleOwner,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MileNoteShapes.large),
                        onError = { message -> cameraError = message },
                    )
                } else {
                    CameraPlaceholder(
                        hasPermission = hasCameraPermission,
                        cameraError = cameraError,
                        onRequestPermission = { requestCameraPermission() },
                        onPickGallery = { pickFromGallery() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 阶段覆盖层
                when (stage) {
                    OcrStage.Recognizing -> RecognizingOverlay()
                    OcrStage.Result -> ResultOverlay(
                        receipt = receipt,
                        recognizedText = recognizedText,
                        onUse = { parsed -> parsed?.let(onResult) },
                        onRetake = { resetToIdle() },
                    )
                    OcrStage.Error -> ErrorOverlay(
                        message = errorMessage ?: tr("识别失败，请重试", "Recognition failed — try again"),
                        onRetake = { resetToIdle() },
                        onPickGallery = { pickFromGallery() },
                    )
                    OcrStage.Idle -> Unit
                }
            }

            OcrBottomActions(
                enabled = stage == OcrStage.Idle && hasCameraPermission && cameraError == null,
                onCapture = { capturePhoto() },
                onPickGallery = { pickFromGallery() },
                onRequestPermission = { requestCameraPermission() },
            )
        }
    }
}

@Composable
private fun OcrHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = MileNoteDimens.PageHorizontal, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(MileNoteDimens.TouchTarget)) {
            Icon(
                imageVector = MileNoteIcons.Close,
                contentDescription = tr("关闭", "Close"),
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(tr("拍照识别小票", "Scan receipt"), style = MaterialTheme.typography.titleLarge)
            Text(
                tr(
                    "自动识别金额、油量、支付日期与总里程",
                    "Detects amount, fuel, payment date, and odometer automatically",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** CameraX 预览（[PreviewView]）。 */
@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    cameraProvider: MutableState<ProcessCameraProvider?>,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                bindCamera(ctx, this, lifecycleOwner, imageCapture, cameraProvider, onError)
            }
        },
    )
}

/** 绑定 CameraX 预览 + 拍照用例；失败回调中文提示（相机被占用 / 无相机）。 */
private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    imageCapture: ImageCapture,
    cameraProvider: MutableState<ProcessCameraProvider?>,
    onError: (String) -> Unit,
) {
    try {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider.value = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (t: Throwable) {
                onError(
                    tr(
                        "相机启动失败：%s",
                        "Camera failed to start: %s",
                        t.message ?: tr("可能被其他应用占用", "it may be in use by another app"),
                    ),
                )
            }
        }, ContextCompat.getMainExecutor(context))
    } catch (t: Throwable) {
        onError(
            tr(
                "相机初始化失败：%s",
                "Camera initialization failed: %s",
                t.message ?: tr("未知错误", "unknown error"),
            ),
        )
    }
}

@Composable
private fun CameraPlaceholder(
    hasPermission: Boolean,
    cameraError: String?,
    onRequestPermission: () -> Unit,
    onPickGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MileNoteShapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MileNoteIcons.Camera,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    cameraError != null -> tr("相机不可用", "Camera unavailable")
                    !hasPermission -> tr("需要相机权限才能拍照识别", "Camera permission is needed to scan")
                    else -> tr("正在准备相机…", "Preparing camera…")
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = cameraError
                    ?: if (!hasPermission) {
                        tr(
                            "我们只在你拍照时使用相机，图片不会上传或保存。",
                            "The camera is only used while you take a photo; images are never " +
                                "uploaded or saved.",
                        )
                    } else {
                        tr(
                            "若长时间无画面，可直接从相册选择小票。",
                            "If the preview stays blank, pick a receipt from the gallery instead.",
                        )
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (!hasPermission) {
                Button(onClick = onRequestPermission) { Text(tr("允许使用相机", "Allow camera access")) }
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = onPickGallery) { Text(tr("从相册选择", "Choose from gallery")) }
        }
    }
}

/** 识别中遮罩：M3 Expressive LoadingIndicator。 */
@Composable
private fun RecognizingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MileNoteShapes.large)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MileNoteShapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            LoadingState(label = tr("正在识别小票…", "Reading receipt…"))
        }
    }
}

/** 识别结果确认面板：字段回显 + 校验提示 + 使用/重拍。 */
@Composable
private fun ResultOverlay(
    receipt: FuelReceipt?,
    recognizedText: String,
    onUse: (FuelReceipt?) -> Unit,
    onRetake: () -> Unit,
) {
    var parsed by remember(recognizedText) { mutableStateOf(receipt) }
    var rawText by remember(recognizedText) { mutableStateOf(recognizedText) }
    val hasUsefulField = parsed != null && (
        parsed?.amount != null || parsed?.liters != null ||
            parsed?.date?.isNotBlank() == true || parsed?.odometer != null
        )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("识别结果", "Scan result"), style = MaterialTheme.typography.titleLarge)
            Text(
                if (hasUsefulField) {
                    tr("请核对以下字段，确认后填入记账表单", "Check the fields below before filling the entry form")
                } else {
                    tr("没有识别到有效字段，可重拍或改用手动填写", "No valid fields detected — retake the photo or fill in manually")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MileNoteShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ReceiptField(
                        tr("实付金额", "Amount paid"),
                        parsed?.amount?.let { money(it) } ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("加油量", "Fuel amount"),
                        parsed?.liters?.let { tr("%s %s", "%s %s", number(it), Units.volumeUnit) }
                            ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("油价", "Unit price"),
                        parsed?.unitPrice?.let { tr("%s %s", "%s %s", money(it), Units.pricePerVolume) }
                            ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("应付金额", "Amount due"),
                        parsed?.payable?.let { money(it) } ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("优惠金额", "Discount"),
                        parsed?.discount?.let { money(it) } ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("支付日期", "Payment date"),
                        parsed?.date?.ifBlank { tr("未识别", "Not detected") }
                            ?: tr("未识别", "Not detected"),
                    )
                    HorizontalDivider()
                    ReceiptField(
                        tr("总里程", "Odometer"),
                        parsed?.odometer?.let { tr("%s %s", "%s %s", number(it), Units.distanceUnit) }
                            ?: tr("未识别", "Not detected"),
                    )
                }
            }

            parsed?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                MessageBar(
                    message = warnings.joinToString(tr("；", "; ")),
                    kind = MessageKind.WARNING,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onUse(parsed) },
                    modifier = Modifier
                        .weight(1f)
                        .height(MileNoteDimens.ButtonMinHeight),
                ) {
                    Text(if (hasUsefulField) tr("使用结果", "Use result") else tr("手动填写", "Fill in manually"))
                }
                OutlinedButton(
                    onClick = onRetake,
                    modifier = Modifier
                        .weight(1f)
                        .height(MileNoteDimens.ButtonMinHeight),
                ) {
                    Text(tr("重拍 / 换一张", "Retake / change photo"))
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MileNoteShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr("识别原文（可修改后重新解析）", "Raw text (edit and re-parse)"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = {
                            Text(tr("未识别到文字，可手动粘贴/输入小票文字", "No text detected — paste or type the receipt text"))
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { parsed = FuelReceiptParser.parse(rawText) },
                        ) {
                            Text(tr("重新解析", "Re-parse"))
                        }
                    }
                }
            }

        }
    }
}

/** 识别失败面板。 */
@Composable
private fun ErrorOverlay(
    message: String,
    onRetake: () -> Unit,
    onPickGallery: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            MessageBar(message = message, kind = MessageKind.ERROR)
            Text(
                tr(
                    "拍摄时请让整张小票进入画面、保持光线充足且文字清晰。",
                    "Keep the whole receipt in frame with good lighting and sharp text.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetake,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MileNoteDimens.ButtonMinHeight),
            ) {
                Text(tr("重试", "Retry"))
            }
            OutlinedButton(
                onClick = onPickGallery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MileNoteDimens.ButtonMinHeight),
            ) {
                Text(tr("从相册选择", "Choose from gallery"))
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 底部操作区：相册兜底 + 拍照主按钮。 */
@Composable
private fun OcrBottomActions(
    enabled: Boolean,
    onCapture: () -> Unit,
    onPickGallery: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = MileNoteDimens.PageHorizontal, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPickGallery,
            modifier = Modifier.height(MileNoteDimens.ButtonMinHeight),
        ) {
            Icon(
                imageVector = MileNoteIcons.Import,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(tr("相册", "Gallery"), modifier = Modifier.padding(start = 6.dp))
        }
        Button(
            onClick = if (enabled) onCapture else onRequestPermission,
            modifier = Modifier
                .weight(1f)
                .height(MileNoteDimens.ButtonMinHeight),
        ) {
            Icon(
                imageVector = MileNoteIcons.Camera,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (enabled) tr("拍照识别", "Scan") else tr("启用相机", "Enable camera"),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 结果字段行：左标签右值。 */
@Composable
private fun ReceiptField(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
        )
    }
}

/** 把相机/相册位图转成 OCR 友好的方向与尺寸（按需回收原图）。 */
private fun prepareForOcr(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    if (bitmap.isRecycled) return bitmap
    val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
    val scale = when {
        longest > MAX_OCR_SIDE -> MAX_OCR_SIDE.toFloat() / longest
        // 手机相册里的加油机照片常只有几百像素，先放大再识别。
        longest < MIN_OCR_SIDE -> (MIN_OCR_SIDE.toFloat() / longest).coerceAtMost(MAX_OCR_UPSCALE)
        else -> 1f
    }
    if (rotationDegrees == 0 && scale == 1f) return bitmap
    val matrix = Matrix().apply {
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        if (scale != 1f) postScale(scale, scale)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** 从相册 URI 解码位图（API 28+ 用 ImageDecoder 自动应用 EXIF 方向，且强制软件 ARGB_8888 位图供 OCR 使用）。 */
private fun decodeImage(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_DECODE_SIDE) {
                val sample = ceil(longest.toDouble() / MAX_DECODE_SIDE).toInt().coerceAtLeast(1)
                decoder.setTargetSampleSize(sample)
            }
        }
    } else {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }
}.getOrNull()

private fun parseScore(receipt: FuelReceipt): Int =
    (if (receipt.amount != null) 4 else 0) +
        (if (receipt.liters != null) 3 else 0) +
        (if (receipt.unitPrice != null) 2 else 0) +
        (if (receipt.date.isNotBlank()) 1 else 0) +
        (if (receipt.odometer != null) 1 else 0)

/** 灰度+对比度、二值化两个增强版本，专治低清/七段数码管照片。 */
private fun buildEnhancedVariants(base: Bitmap): List<Bitmap> {
    if (base.isRecycled) return emptyList()
    val grayscale = runCatching {
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(
            floatArrayOf(
                1.8f, 0f, 0f, 0f, -90f,
                0f, 1.8f, 0f, 0f, -90f,
                0f, 0f, 1.8f, 0f, -90f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        matrix.postConcat(contrast)
        Canvas(output).drawBitmap(
            base,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
        )
        output
    }.getOrNull()
    val threshold = runCatching {
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(base.width * base.height)
        base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val luminance = 0.299f * android.graphics.Color.red(color) +
                0.587f * android.graphics.Color.green(color) +
                0.114f * android.graphics.Color.blue(color)
            pixels[index] = if (luminance > 118f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        output.setPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        output
    }.getOrNull()
    // 负片：黑底浅字（七段数码管、深色 LCD）灰度后仍偏暗，反相后变成白底黑字，
    // Tesseract 的版面分析才找得到行。
    val negative = runCatching {
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        Canvas(output).drawBitmap(
            base,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(invert) },
        )
        output
    }.getOrNull()
    val crops = runCatching {
        listOf(
            // 加油机 LCD 主体：金额 / 油量两行。
            CropSpec(0.18f, 0.22f, 0.82f, 0.68f),
            // 连带下方单价行。
            CropSpec(0.12f, 0.20f, 0.90f, 0.92f),
        ).mapNotNull { spec ->
            val left = (base.width * spec.left).toInt().coerceIn(0, base.width - 2)
            val top = (base.height * spec.top).toInt().coerceIn(0, base.height - 2)
            val right = (base.width * spec.right).toInt().coerceIn(left + 1, base.width)
            val bottom = (base.height * spec.bottom).toInt().coerceIn(top + 1, base.height)
            Bitmap.createBitmap(base, left, top, right - left, bottom - top)
        }.map { crop -> prepareForOcr(crop, 0) }
    }.getOrElse { emptyList() }
    return listOfNotNull(grayscale, threshold, negative) + crops
}

private data class CropSpec(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun money(value: Double): String =
    String.format(Locale.CHINA, "%s%.2f", Units.currencySymbol, value)

private fun number(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.CHINA, "%.0f", value)
    } else {
        String.format(Locale.CHINA, "%.2f", value)
    }
