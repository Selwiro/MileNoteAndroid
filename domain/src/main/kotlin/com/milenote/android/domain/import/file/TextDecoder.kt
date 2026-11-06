package com.milenote.android.domain.import.file

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CSV/TSV 文本解码。
 *
 * 优先按 BOM 判断 UTF-8 / UTF-16；没有 BOM 时先严格尝试 UTF-8，
 * 失败再回退 GB18030 / GBK（兼容国内财务软件导出的 GBK 文本）。
 */
internal object TextDecoder {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (isValidUtf8(bytes)) return String(bytes, Charsets.UTF_8)
        return String(bytes, fallbackCharset())
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it].toInt() and 0xFF == prefix[it] }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun fallbackCharset(): Charset =
        sequenceOf("GB18030", "GBK")
            .mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }
            .firstOrNull()
            ?: Charsets.ISO_8859_1
}
