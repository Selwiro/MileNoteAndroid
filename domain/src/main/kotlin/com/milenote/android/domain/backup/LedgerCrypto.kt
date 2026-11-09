package com.milenote.android.domain.backup

import com.milenote.android.domain.i18n.tr
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 远端账本信封加密（`drive-ledger-encrypted` v1），与 Web 版
 * `car-assistant/dist/webdav.mjs` 的 `encodeSync` / `decodeSync` 逐字节互通。
 *
 * 格式（compact JSON，UTF-8）：
 * `{"format":"drive-ledger-encrypted","version":1,"algorithm":"AES-GCM-256",`
 * `"kdf":"PBKDF2-SHA256","iterations":600000,"salt":"<base64>","iv":"<base64>","data":"<base64>"}`
 *
 * - KDF：PBKDF2-HMAC-SHA256 600000 次，密码按 UTF-8 字节参与，派生 256-bit key；
 * - 加密：AES/GCM/NoPadding，salt 16B / iv 12B 随机，tag 128 bit，无 AAD；
 * - Base64：标准字母表带 `=` padding（Java `Base64.getEncoder()` ≡ 浏览器 `btoa`）。
 *
 * 解密前的字段校验（format/version/algorithm/kdf/iterations/salt 16B/iv 12B）不符一律
 * 报「不支持此加密格式」；非信封文本原样返回，保证旧明文文件兼容。
 */
object LedgerCrypto {

    const val FORMAT = "drive-ledger-encrypted"
    const val VERSION = 1
    const val ALGORITHM = "AES-GCM-256"
    const val KDF = "PBKDF2-SHA256"
    const val ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val MIN_PASSWORD_LENGTH = 8

    /** AES-256 派生 key 长度；PBKDF2 输出长度与 Web `deriveKey(length:256)` 一致。 */
    private const val KEY_BYTES = 32

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val json = Json
    private val secureRandom = SecureRandom()

    /**
     * 顶层 `format == FORMAT` 即视为加密信封（即使版本等字段不支持也返回 true）。
     * 不是 JSON 对象或解析失败返回 false。
     */
    fun isEncrypted(text: String): Boolean {
        val root = parseObjectOrNull(text) ?: return false
        return stringField(root["format"]) == FORMAT
    }

    /** 加密为信封 JSON；密码短于 [MIN_PASSWORD_LENGTH] 抛 [BackupException]。 */
    fun encrypt(plainJson: String, password: String): String {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw BackupException(
                tr("加密密码至少需要 8 个字符", "The encryption password must be at least 8 characters"),
            )
        }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val key = pbkdf2(password.toByteArray(Charsets.UTF_8), salt)
        val encrypted = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            // 本机 JCE 异常（AES-GCM 不可用等）：按解密同类错误上报，避免泄露实现细节。
            throw decryptionFailed(e)
        }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("algorithm", ALGORITHM)
                put("kdf", KDF)
                put("iterations", ITERATIONS)
                put("salt", base64(salt))
                put("iv", base64(iv))
                put("data", base64(encrypted))
            },
        )
    }

    /**
     * 信封则解密返回明文 JSON；非信封文本原样返回。
     *
     * 字段校验失败 → [BackupException]「不支持此加密格式」；
     * 密码错误 / 密文损坏 → [BackupException]「解密失败：密码不正确或文件已损坏」。
     */
    fun decryptIfEncrypted(text: String, password: String): String {
        val root = parseObjectOrNull(text) ?: return text
        if (stringField(root["format"]) != FORMAT) return text

        validateField(root)
        val salt = decodeSaltOrIv(root["salt"]) ?: throw unsupportedFormat()
        val iv = decodeSaltOrIv(root["iv"]) ?: throw unsupportedFormat()
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES) throw unsupportedFormat()

        val dataText = stringField(root["data"]) ?: throw decryptionFailed()
        val ciphertext = try {
            Base64.getDecoder().decode(dataText)
        } catch (e: IllegalArgumentException) {
            throw decryptionFailed(e)
        }
        // 空密码不会来自正常 UI 流程（由 app 层先要求输入）；直接按解密失败处理，
        // 省掉一次 600k 次派生。
        if (password.isEmpty()) throw decryptionFailed()

        val key = pbkdf2(password.toByteArray(Charsets.UTF_8), salt)
        val plain = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw decryptionFailed(e)
        }
        return String(plain, Charsets.UTF_8)
    }

    /** 校验除 salt/iv 外的格式字段（与 Web `decodeSync` 的严格相等一致）。 */
    private fun validateField(root: JsonObject) {
        if (strictNumber(root["version"]) != VERSION.toDouble()) throw unsupportedFormat()
        if (stringField(root["algorithm"]) != ALGORITHM) throw unsupportedFormat()
        if (stringField(root["kdf"]) != KDF) throw unsupportedFormat()
        if (strictNumber(root["iterations"]) != ITERATIONS.toDouble()) throw unsupportedFormat()
    }

    /**
     * PBKDF2-HMAC-SHA256（RFC 8018）标准循环。
     *
     * 不用 `SecretKeyFactory("PBKDF2WithHmacSHA256")`：其 char→byte 转换依赖 JCE 实现
     * （Conscrypt / OpenJDK 对非 ASCII 口令可能不一致），而这里必须与 Web 的
     * 「密码 UTF-8 字节」逐字节一致。`Mac` 在 `doFinal` 后自动复位，可复用同一实例。
     */
    private fun pbkdf2(password: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))
        val hashLength = mac.macLength
        val blockCount = (KEY_BYTES + hashLength - 1) / hashLength
        val output = ByteArray(blockCount * hashLength)
        val blockIndex = ByteArray(4)
        for (block in 1..blockCount) {
            blockIndex[0] = (block ushr 24).toByte()
            blockIndex[1] = (block ushr 16).toByte()
            blockIndex[2] = (block ushr 8).toByte()
            blockIndex[3] = block.toByte()
            mac.update(salt)
            mac.update(blockIndex)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (round in 1 until ITERATIONS) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, output, (block - 1) * hashLength, hashLength)
        }
        return output.copyOf(KEY_BYTES)
    }

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    /** 严格字符串字段：JSON 字符串才算，数字/布尔/null 一律视为缺失。 */
    private fun stringField(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    /** 严格数字字段（与 JS `===` 对数字字面量的语义一致；字符串 "1" 不算 1）。 */
    private fun strictNumber(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()
    }

    private fun decodeSaltOrIv(element: JsonElement?): ByteArray? {
        val text = stringField(element) ?: return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unsupportedFormat(): BackupException =
        BackupException(tr("不支持此加密格式", "Unsupported encryption format"))

    private fun decryptionFailed(cause: Throwable? = null): BackupException =
        BackupException(tr("解密失败：密码不正确或文件已损坏", "Decryption failed: wrong password or corrupted file"), cause)
}
