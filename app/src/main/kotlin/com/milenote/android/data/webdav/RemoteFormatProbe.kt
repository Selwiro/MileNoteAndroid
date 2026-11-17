package com.milenote.android.data.webdav

import com.milenote.android.domain.backup.LedgerCrypto

/**
 * 从「远端文件开头若干个字节」判断它当前是明文备份还是加密信封。
 *
 * 1.0 试用反馈：「WebDAV 设置里开了加密，远端格式却显示未知」。原因是格式只能靠**读内容**判断，
 * 而「保存并测试」原来只发 HEAD（只有大小 / ETag），所以永远推不出来。现在会读开头 256 字节。
 *
 * @return true = 加密信封；false = 明文 JSON；null = 判断不了（非 JSON / 空文件 / 读失败）。
 */
internal fun remoteFormatFromPrefix(prefix: String?): Boolean? {
    val text = prefix
        ?.removePrefix("\uFEFF")
        ?.trimStart()
        ?: return null
    if (text.isEmpty()) return null
    // 信封的 format 字段写在最前面，前缀里能直接看到；不依赖 JSON 完整可解析。
    if (text.contains(LedgerCrypto.FORMAT)) return true
    // 其余以 `{` 开头的一律按明文备份处理（version:1/2 都是 JSON 对象）。
    return if (text.startsWith("{")) false else null
}
