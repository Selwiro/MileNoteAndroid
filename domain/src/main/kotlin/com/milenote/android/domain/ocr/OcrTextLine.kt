package com.milenote.android.domain.ocr

/** OCR 识别出的一行文字及其在图片中的像素坐标（左上原点）。 */
data class OcrTextLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** OCR 全部结果：纯文本用于展示/编辑，带坐标的行用于结构化解析。 */
data class OcrText(
    val text: String,
    val lines: List<OcrTextLine> = emptyList(),
)
