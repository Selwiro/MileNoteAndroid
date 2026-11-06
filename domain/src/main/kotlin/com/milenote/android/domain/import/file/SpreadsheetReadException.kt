package com.milenote.android.domain.import.file

/**
 * 表格文件读取失败。
 *
 * [message] 是可直接展示给用户的中文提示（不含技术堆栈），
 * 界面层可以直接 `e.message` 展示；排查问题时再看 [cause]。
 */
class SpreadsheetReadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
