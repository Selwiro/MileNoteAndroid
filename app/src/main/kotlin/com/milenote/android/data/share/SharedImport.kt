package com.milenote.android.data.share

/**
 * 从其他 App 分享进来的导入内容（钱迹「分享到其他 App」、文件管理器分享、文本分享等）。
 *
 * @property bytes 文件字节或文本 UTF-8 字节
 * @property fileName 展示用文件名（没有则为「分享导入」）
 * @property mimeType 分享方声明的 MIME（可能为空）
 */
class SharedImport(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String? = null,
)
