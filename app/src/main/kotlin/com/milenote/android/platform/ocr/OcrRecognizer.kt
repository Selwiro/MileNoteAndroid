package com.milenote.android.platform.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.milenote.android.domain.ocr.OcrText
import com.milenote.android.domain.ocr.OcrTextLine
import com.milenote.android.i18n.tr
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * OCR 识别失败：`message` 为可直接展示给用户的中文提示。
 *
 * 常见原因：图片过于模糊/倾斜、图片非加油小票、ML Kit 模型初始化失败。
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
 * ML Kit 中文文字识别（bundled 模型，离线可用，不依赖 Google Play 服务下载）。
 *
 * 每次调用创建并关闭一个 [com.google.mlkit.vision.text.TextRecognizer]：
 * ML Kit 的 bundled 识别器持有 native 资源，用完必须 `close()`，否则会泄漏。
 */
object MlKitOcrRecognizer : OcrRecognizer {

    private const val TAG = "MlKitOcrRecognizer"

    override suspend fun recognize(bitmap: Bitmap): OcrText {
        if (bitmap.isRecycled) throw OcrException(tr("图片已失效，请重新拍摄", "Image is no longer available — take a new photo"))
        return suspendCancellableCoroutine { cont ->
            val closed = AtomicBoolean(false)
            val recognizer = try {
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resumeWithException(OcrException(tr("文字识别组件初始化失败，请稍后重试", "Text recognition failed to initialize — try again later"), e))
                }
                return@suspendCancellableCoroutine
            }
            val closeOnce = {
                if (closed.compareAndSet(false, true)) {
                    runCatching { recognizer.close() }
                }
            }
            cont.invokeOnCancellation {
                Log.d(TAG, "识别已取消")
                closeOnce()
            }
            val image = try {
                InputImage.fromBitmap(bitmap, 0)
            } catch (e: Exception) {
                closeOnce()
                if (cont.isActive) cont.resumeWithException(OcrException(tr("图片格式不受支持，请重新拍摄", "Unsupported image format — take a new photo"), e))
                return@suspendCancellableCoroutine
            }
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    closeOnce()
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrTextLine(
                                text = line.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                            )
                        }
                    }
                    if (cont.isActive) cont.resume(OcrText(result.text.orEmpty(), lines))
                }
                .addOnFailureListener { e ->
                    closeOnce()
                    Log.w(TAG, "文字识别失败", e)
                    if (cont.isActive) {
                        cont.resumeWithException(
                            OcrException(
                                tr(
                                    "小票识别失败：%s",
                                    "Receipt scan failed: %s",
                                    e.message ?: tr("请检查光线和清晰度", "check lighting and focus"),
                                ),
                                e,
                            ),
                        )
                    }
                }
        }
    }
}


/**
 * ML Kit 拉丁文字识别：中文模型对七段数码管/纯数字屏幕照片识别较差，
 * 加油机照片在中文模型失败时用拉丁模型再试一次。
 */
object MlKitLatinOcrRecognizer : OcrRecognizer {
    override suspend fun recognize(bitmap: Bitmap): OcrText {
        if (bitmap.isRecycled) throw OcrException(tr("图片已失效，请重新拍摄", "Image is no longer available — take a new photo"))
        return suspendCancellableCoroutine { cont ->
            val recognizer: TextRecognizer = try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resumeWithException(OcrException(tr("数字识别组件初始化失败，请稍后重试", "Number recognition failed to initialize — try again later"), e))
                }
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { recognizer.close() } }
            val image = try {
                InputImage.fromBitmap(bitmap, 0)
            } catch (e: Exception) {
                runCatching { recognizer.close() }
                if (cont.isActive) cont.resumeWithException(OcrException(tr("图片格式不受支持，请重新拍摄", "Unsupported image format — take a new photo"), e))
                return@suspendCancellableCoroutine
            }
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    runCatching { recognizer.close() }
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrTextLine(line.text, box.left, box.top, box.right, box.bottom)
                        }
                    }
                    if (cont.isActive) cont.resume(OcrText(result.text.orEmpty(), lines))
                }
                .addOnFailureListener { e ->
                    runCatching { recognizer.close() }
                    if (cont.isActive) {
                        cont.resumeWithException(
                            OcrException(
                                tr(
                                    "数字识别失败：%s",
                                    "Number scan failed: %s",
                                    e.message ?: tr("请重试", "try again"),
                                ),
                                e,
                            ),
                        )
                    }
                }
        }
    }
}
