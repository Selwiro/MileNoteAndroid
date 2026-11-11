package com.milenote.android.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import com.milenote.android.domain.ocr.FuelReceiptParser
import com.milenote.android.platform.ocr.PumpDisplayReader
import com.milenote.android.platform.ocr.DebugDigitOcrRecognizer
import com.milenote.android.platform.ocr.TesseractLatinOcrRecognizer
import com.milenote.android.platform.ocr.TesseractOcrRecognizer
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仅 debug 构建的 OCR 调试入口：`adb am start` 传图片路径，跑完整识别链路并把中间结果写 logcat。
 *
 * ```bash
 * adb shell run-as com.milenote.android cp /data/local/tmp/ocr3.jpg files/ocr3.jpg
 * adb shell am start -n com.milenote.android/com.milenote.android.debug.OcrDebugActivity \
 *     --es image files/ocr3.jpg
 * adb logcat -s OCRDBG
 * ```
 */
class OcrDebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("image") ?: run {
            Log.w(TAG, "missing --es image")
            finish()
            return
        }
        val file = File(name).let { if (it.isAbsolute) it else File(filesDir, name) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                run(file)
            } catch (t: Throwable) {
                Log.e(TAG, "failed: $t", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private suspend fun run(file: File) {
        val start = System.currentTimeMillis()
        val raw = BitmapFactory.decodeFile(file.absolutePath)
        if (raw == null) {
            Log.e(TAG, "decode failed: ${file.absolutePath} exists=${file.exists()}")
            return
        }
        val bitmap = prepare(raw)
        Log.i(
            TAG,
            "== ${file.name} raw=${raw.width}x${raw.height} ocr=${bitmap.width}x${bitmap.height}",
        )
        val chinese = TesseractOcrRecognizer.recognize(bitmap)
        Log.i(TAG, "---- chi_sim text (${System.currentTimeMillis() - start} ms) ----\n${chinese.text}")
        for (line in chinese.lines) {
            Log.i(TAG, "line [${line.left},${line.top},${line.right},${line.bottom}] '${line.text}'")
        }
        val parsed = FuelReceiptParser.parse(chinese.text, chinese.lines)
        Log.i(TAG, "parsed amount=${parsed.amount} liters=${parsed.liters} price=${parsed.unitPrice} date='${parsed.date}' odo=${parsed.odometer} warnings=${parsed.warnings}")
        val latin = TesseractLatinOcrRecognizer.recognize(bitmap)
        Log.i(TAG, "---- eng text ----\n${latin.text}")
        val pump = PumpDisplayReader.read(bitmap)
        Log.i(TAG, "pump readings=$pump (${System.currentTimeMillis() - start} ms total)")
        Log.i(TAG, PumpDisplayReader.debugTrace(bitmap))
        val cropSpecs = intent.getStringExtra("crops")
        if (!cropSpecs.isNullOrBlank()) {
            for (crop in cropSpecs.split(";")) {
                val parts = crop.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size != 4) continue
                val (cx, cy, cw, chh) = parts
                if (cx < 0 || cy < 0 || cw <= 1 || chh <= 1) continue
                val right = (cx + cw).coerceAtMost(bitmap.width)
                val bottom = (cy + chh).coerceAtMost(bitmap.height)
                if (right - cx < 2 || bottom - cy < 2) continue
                val cropBmp = Bitmap.createBitmap(bitmap, cx, cy, right - cx, bottom - cy)
                Log.i(TAG, "---- crop x=$cx y=$cy w=${right - cx} h=${bottom - cy} ----")
                for ((label, variant) in variants(cropBmp)) {
                    val dense = DebugDigitOcrRecognizer.recognize(variant)
                    val spar = DebugDigitOcrRecognizer.recognize(variant, sparseText = true)
                    Log.i(TAG, "crop[$label] dense='${dense.text.replace("\n", "/")}' sparse='${spar.text.replace("\n", "/")}'")
                    if (variant !== cropBmp) variant.recycle()
                }
                cropBmp.recycle()
            }
        }
        bitmap.recycle()
        raw.recycle()
        Log.i(TAG, "== done")
    }

    /** 原图 + 灰度二值化几个阈值，给数字白名单引擎尝试。 */
    private fun variants(source: Bitmap): List<Pair<String, Bitmap>> {
        val result = ArrayList<Pair<String, Bitmap>>()
        result.add("raw" to source)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (threshold in intArrayOf(100, 130, 160)) {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val out = IntArray(pixels.size)
            for (index in pixels.indices) {
                val color = pixels[index]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                out[index] = if (lum <= threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            output.setPixels(out, 0, source.width, 0, 0, source.width, source.height)
            result.add("th$threshold" to output)
        }
        return result
    }

    /** 与 OcrCaptureScreen.prepareForOcr 相同的尺寸策略（长边 1400–2000，小图放大 ≤5×）。 */
    private fun prepare(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = when {
            longest > 2000 -> 2000f / longest
            longest < 1400 -> (1400f / longest).coerceAtMost(5f)
            else -> 1f
        }
        if (scale == 1f) return source
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "OCRDBG"
    }
}
