package com.milenote.android.platform.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.milenote.android.domain.ocr.OcrText
import com.milenote.android.domain.ocr.OcrTextLine
import com.milenote.android.i18n.tr
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * OCR 识别失败：`message` 为可直接展示给用户的中文提示。
 *
 * 常见原因：图片过于模糊/倾斜、图片非加油小票、Tesseract 模型初始化失败。
 */
class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 图片文字识别（OCR）抽象。
 *
 * 抽象成接口是为了：
 * - 页面侧可注入假实现做 Preview / 测试；
 * - 后续若替换识别引擎（PaddleOCR / 云端），页面不用改。
 *
 * 约定：实现**不得**把图片写入磁盘或上传网络；调用方负责在识别结束后回收 Bitmap。
 */
interface OcrRecognizer {
    /**
     * 识别 [bitmap] 中的全部文字。
     *
     * @return 识别文本（纯文本 + 带像素坐标的行，供结构化解析）。
     * @throws OcrException 识别失败时抛出，消息为中文。
     */
    suspend fun recognize(bitmap: Bitmap): OcrText
}

/**
 * Tesseract 中文文字识别（内置 `tessdata_fast` 模型：`chi_sim+eng`，离线可用）。
 *
 * Tesseract 初始化慢（首次约 1–2 秒，要加载语言模型）、但实例可复用，
 * 因此本实现**进程内保留单例**，不每次调用重建；`TessBaseAPI` 非线程安全，用 [Mutex] 串行化。
 *
 * 模型文件在 APK 的 `assets/tessdata/`，首次识别时复制到 `filesDir/tesseract/tessdata/`
 * （Tesseract 只接受真实文件路径，targetSdk ≥ 29 时不能用外部可读目录）。
 *
 * 注意：`chi_sim+eng` 同时加载中英模型，数字/小数点在中文小票里由 eng 模型兜底，
 * 比纯 `chi_sim` 更稳；代价是比单语言稍慢。
 */
object TesseractOcrRecognizer : OcrRecognizer {

    /** 识别引擎需要 Application Context 找 assets 与可写目录，在 [MileNoteApplication] 里注入一次。 */
    fun install(context: Context) {
        TesseractEnvironment.install(context)
    }

    private val engine = TessEngine(
        language = "chi_sim+eng",
        // 小票是「标签列 + 数值列」的两栏排版：PSM_AUTO(3) 会把两栏拆成两个文本块，
        // 标签与数值分离（实测金额/实付金额整行丢失，解析不到实付金额）；
        // PSM_SINGLE_BLOCK(6) 按整块文本逐行识别，标签和数值留在同一行，保留小数点。
        pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        tag = "TesseractOcr",
    )

    override suspend fun recognize(bitmap: Bitmap): OcrText = engine.recognize(bitmap)
}

/**
 * Tesseract 拉丁文字识别：中文模型对七段数码管/纯数字屏幕照片识别较差，
 * 加油机照片在中文模型失败时用英文模型再试一次（语言数据与中文引擎共享，不额外占 APK）。
 *
 * 用 [TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT]：加油机屏幕文字是零散分布的，
 * 稀疏模式能找出不连续的文本块，比默认的版面分析更适合这种照片。
 */
object TesseractLatinOcrRecognizer : OcrRecognizer {

    private val engine = TessEngine(
        language = "eng",
        pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
        tag = "TesseractLatinOcr",
    )

    override suspend fun recognize(bitmap: Bitmap): OcrText = engine.recognize(bitmap)
}

/** 仅调试用：数字白名单引擎（OcrDebugActivity 调用，正式链路不引用）。 */
internal object DebugDigitOcrRecognizer {
    private val dense = TessEngine(
        language = "eng",
        pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        tag = "DebugDigitDense",
        whitelist = "0123456789.",
    )
    private val sparse = TessEngine(
        language = "eng",
        pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
        tag = "DebugDigitSparse",
        whitelist = "0123456789.",
    )

    suspend fun recognize(bitmap: Bitmap, sparseText: Boolean = false): OcrText =
        if (sparseText) sparse.recognize(bitmap) else dense.recognize(bitmap)
}

/**
 * 全局 Application Context 持有者：OCR 接口不带 Context，用一次性注入避免把平台细节
 * 泄漏到页面层（页面只依赖 [OcrRecognizer]）。
 */
private object TesseractEnvironment {
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun requireContext(): Context = appContext ?: throw OcrException(
        tr("文字识别组件未初始化，请重启应用", "Text recognition is not ready — restart the app"),
    )
}

/**
 * `assets/tessdata/` → `filesDir/tesseract/tessdata/` 的安装器。
 *
 * 只在首次使用或内置模型版本变化时复制，用版本标记文件避免每次启动都做 IO。
 */
private object TesseractDataStore {
    private const val ASSET_DIR = "tessdata"

    /** 内置模型版本；替换 `assets/tessdata/` 下的 traineddata 时同步 +1/改日期，触发重新复制。 */
    private const val VERSION = "tessdata_fast-2026-11-09"

    private val LANGUAGES = listOf("chi_sim", "eng")

    @Synchronized
    fun ensure(context: Context): File {
        val root = File(context.filesDir, "tesseract")
        val dataDir = File(root, ASSET_DIR)
        val marker = File(root, ".traineddata-version")

        if (
            marker.isFile &&
            marker.readText().trim() == VERSION &&
            LANGUAGES.all { File(dataDir, "$it.traineddata").length() > 0L }
        ) {
            return root
        }

        if (!dataDir.isDirectory && !dataDir.mkdirs()) {
            throw OcrException(
                tr("文字识别数据目录创建失败，请检查存储空间", "Could not create the OCR data directory — check storage"),
            )
        }
        for (language in LANGUAGES) {
            copyAsset(context, "$ASSET_DIR/$language.traineddata", File(dataDir, "$language.traineddata"))
        }
        runCatching { marker.writeText(VERSION) }
        return root
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            context.assets.open(asset).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            if (!tmp.renameTo(target)) {
                // 个别文件系统不支持覆盖式 rename：退化成复制。
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.d("TesseractDataStore", "已安装 $asset（${target.length()} 字节）")
        } catch (e: Exception) {
            tmp.delete()
            throw OcrException(
                tr("文字识别数据安装失败，请检查存储空间后重试", "Could not install OCR data — check storage, then retry"),
                e,
            )
        }
    }
}

/**
 * 单个 Tesseract 引擎封装：懒初始化 + 复用 + 线程串行。
 *
 * `TessBaseAPI` 持有 native 资源，识别失败后可能处于不可用状态，这里会丢弃实例重建；
 * 正常路径下实例在整个进程生命周期内复用（不 `recycle()`），避免每次识别都付初始化开销。
 */
private class TessEngine(
    private val language: String,
    private val pageSegMode: Int,
    private val tag: String,
    private val whitelist: String? = null,
) {
    private val mutex = Mutex()

    @Volatile
    private var api: TessBaseAPI? = null

    suspend fun recognize(bitmap: Bitmap): OcrText = mutex.withLock {
        withContext(Dispatchers.IO) { recognizeBlocking(bitmap) }
    }

    private fun recognizeBlocking(bitmap: Bitmap): OcrText {
        if (bitmap.isRecycled) {
            throw OcrException(tr("图片已失效，请重新拍摄", "Image is no longer available — take a new photo"))
        }
        val engine = obtainEngine()
        // Tesseract 只接受 ARGB_8888；相册/相机路径通常已是该格式，这里只做兜底。
        val normalized = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            null
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw OcrException(tr("图片格式不受支持，请重新拍摄", "Unsupported image format — take a new photo"))
        }
        return try {
            engine.setImage(normalized ?: bitmap)
            val text = engine.getUTF8Text().orEmpty().trim()
            OcrText(text, readLines(engine))
        } catch (e: Exception) {
            discardEngine()
            Log.w(tag, "文字识别失败", e)
            throw OcrException(
                tr(
                    "小票识别失败：%s",
                    "Receipt scan failed: %s",
                    e.message ?: tr("请检查光线和清晰度", "check lighting and focus"),
                ),
                e,
            )
        } finally {
            normalized?.recycle()
        }
    }

    private fun obtainEngine(): TessBaseAPI {
        api?.let { return it }

        val context = TesseractEnvironment.requireContext()
        val dataPath = TesseractDataStore.ensure(context).absolutePath
        // TessBaseAPI 构造/静态块里会 System.loadLibrary，ABI 不匹配时抛 UnsatisfiedLinkError，
        // 必须包在 try 里才能给用户可读提示而不是直接崩。
        val engine = try {
            TessBaseAPI()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(tag, "Tesseract native 库缺失", e)
            throw OcrException(
                tr("当前设备不支持文字识别，请改用从相册选择或手动输入", "Text recognition is not supported on this device — enter values manually"),
                e,
            )
        }
        try {
            // init 传 data 目录的父路径，Tesseract 自己拼 `<path>/tessdata`。
            if (!engine.init(dataPath, language)) {
                engine.recycle()
                throw OcrException(
                    tr("文字识别模型加载失败，请重新安装应用", "Could not load the OCR model — reinstall the app"),
                )
            }
            engine.setPageSegMode(pageSegMode)
            if (whitelist != null) {
                runCatching { engine.setVariable("tessedit_char_whitelist", whitelist) }
            }
            // 手机照片没有可靠的 DPI 元数据，交给 Tesseract 自行估计常把行高判错；
            // 小票/屏幕照片按 300dpi 处理的经验值最稳。
            runCatching { engine.setVariable("user_defined_dpi", "300") }
        } catch (e: OcrException) {
            throw e
        } catch (e: Exception) {
            runCatching { engine.recycle() }
            Log.w(tag, "Tesseract 初始化失败", e)
            throw OcrException(
                tr("文字识别组件初始化失败，请稍后重试", "Text recognition failed to initialize — try again later"),
                e,
            )
        } catch (e: UnsatisfiedLinkError) {
            // 设备 ABI 没有对应的 native 库（例如被裁掉了 abiFilters）。
            runCatching { engine.recycle() }
            Log.w(tag, "Tesseract native 库缺失", e)
            throw OcrException(
                tr("当前设备不支持文字识别，请改用从相册选择或手动输入", "Text recognition is not supported on this device — enter values manually"),
                e,
            )
        }
        api = engine
        return engine
    }

    /** 当前实例可能已损坏：丢掉，下次识别重建。 */
    private fun discardEngine() {
        api?.let { runCatching { it.recycle() } }
        api = null
    }

    /**
     * 把 Tesseract 的文本行 iterator 转成带像素坐标的 [OcrTextLine]。
     *
     * 必须在 `getUTF8Text()` 之后调用（识别结果已就绪）；坐标原点与传入 Bitmap 相同（左上）。
     */
    private fun readLines(engine: TessBaseAPI): List<OcrTextLine> {
        val iterator = engine.getResultIterator() ?: return emptyList()
        return try {
            val lines = ArrayList<OcrTextLine>()
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    .orEmpty()
                    .replace('\n', ' ')
                    .trim()
                if (text.isNotEmpty()) {
                    val box = runCatching {
                        iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    }.getOrNull()
                    lines += if (box != null && box.width() > 0 && box.height() > 0) {
                        OcrTextLine(text, box.left, box.top, box.right, box.bottom)
                    } else {
                        OcrTextLine(text)
                    }
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            lines
        } finally {
            runCatching { iterator.delete() }
        }
    }
}
