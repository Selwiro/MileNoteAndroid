package com.milenote.android.domain.backup

/**
 * 账本备份的体积上限（1.0 审查 #12）。
 *
 * 以前四处的上限各不相同：导出没有上限、本地还原 20 MiB、WebDAV 下载 32 MiB、上传没有限制，
 * 于是 App 能生成并上传自己读不回来的备份。现在所有路径都以这里为准。
 */
object LedgerLimits {

    /** 明文 JSON 上限：含多张车辆图片的账本也远低于它，同时挡住误放的大文件。 */
    const val MAX_PLAIN_BYTES: Long = 32L * 1024 * 1024

    /** 加密信封的额外体积（base64 膨胀 + 头部）。 */
    const val ENVELOPE_SLACK_BYTES: Long = 4L * 1024 * 1024

    /** 允许传输的最大字节数（加密信封口径）。 */
    const val MAX_ENVELOPE_BYTES: Long = MAX_PLAIN_BYTES + ENVELOPE_SLACK_BYTES

    /** 按 MB 展示上限（提示文案用，向下取整）。 */
    fun maxPlainMb(): Int = (MAX_PLAIN_BYTES / (1024 * 1024)).toInt()
}
