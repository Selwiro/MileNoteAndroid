package com.milenote.android.domain.backup

import com.milenote.android.domain.i18n.tr
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `drive-ledger-encrypted` v1 信封加解密测试（对应 spec §6）。
 *
 * 性能约束：PBKDF2 600000 次每次约 0.3–1 s，本类把派生次数控制在 **7 次**
 * （共享一份 Android 信封、第二次随机值验证、Web fixture 解密），字段校验类用例
 * 必须在派生之前失败，因此不增加耗时。
 */
class LedgerCryptoTest {

    companion object {
        /** 与 `/tmp/milenote-webdav-fixture.mjs` 生成 fixture 时使用的 CJK 口令一致。 */
        const val PASSWORD = "里程志-交叉验证-123"
        const val WRONG_PASSWORD = "wrong-password-123"

        private const val UNSUPPORTED_ZH = "不支持此加密格式"
        private const val UNSUPPORTED_EN = "Unsupported encryption format"
        private const val DECRYPT_FAILED_ZH = "解密失败：密码不正确或文件已损坏"
        private const val DECRYPT_FAILED_EN = "Decryption failed: wrong password or corrupted file"
        private const val PASSWORD_SHORT_ZH = "加密密码至少需要 8 个字符"
        private const val PASSWORD_SHORT_EN = "The encryption password must be at least 8 characters"

        /** Web 端 `encodeSync(garage, 'plain')` 的真实产出（密码 fixture 的明文侧）。 */
        private val webPlain: String by lazy { resource("/webdav/web-plain.json") }

        /** Web 端 `encodeSync(garage, 'encrypted', 里程志-交叉验证-123)` 的真实产出。 */
        private val webEnvelope: String by lazy { resource("/webdav/web-encrypted-cjk-password.json") }

        /** Android 端生成的信封：明文用 `web-plain.json`，便于反向交给 Web `decodeSync` 验证。 */
        val androidEnvelope: String by lazy { LedgerCrypto.encrypt(webPlain, PASSWORD) }

        private fun resource(path: String): String =
            LedgerCryptoTest::class.java.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("缺少测试资源：$path")
    }

    private fun assertEqualsMessage(zh: String, en: String, actual: Throwable?) {
        assertEquals(tr(zh, en), actual?.message)
    }

    // ---------------------------------------------------------------- 往返 / 错误密码 / 篡改

    @Test
    fun `correct password round trips to the original plain json`() {
        assertEquals(webPlain, LedgerCrypto.decryptIfEncrypted(androidEnvelope, PASSWORD))
    }

    @Test
    fun `wrong password fails with documented message`() {
        val error = assertThrows(BackupException::class.java) {
            LedgerCrypto.decryptIfEncrypted(androidEnvelope, WRONG_PASSWORD)
        }
        assertEqualsMessage(DECRYPT_FAILED_ZH, DECRYPT_FAILED_EN, error)
    }

    @Test
    fun `tampered ciphertext fails with documented message`() {
        val data = parse(androidEnvelope).getValue("data").jsonPrimitive.content
        val bytes = Base64.getDecoder().decode(data)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = androidEnvelope.replace(data, Base64.getEncoder().encodeToString(bytes))
        assertNotEquals(androidEnvelope, tampered)

        val error = assertThrows(BackupException::class.java) {
            LedgerCrypto.decryptIfEncrypted(tampered, PASSWORD)
        }
        assertEqualsMessage(DECRYPT_FAILED_ZH, DECRYPT_FAILED_EN, error)
    }

    // ---------------------------------------------------------------- 随机 salt / iv

    @Test
    fun `salt and iv are random per encryption and both envelopes decrypt`() {
        val second = LedgerCrypto.encrypt(webPlain, PASSWORD)
        val first = parse(androidEnvelope)
        val other = parse(second)
        assertNotEquals(first.getValue("salt").jsonPrimitive.content, other.getValue("salt").jsonPrimitive.content)
        assertNotEquals(first.getValue("iv").jsonPrimitive.content, other.getValue("iv").jsonPrimitive.content)
        assertNotEquals(first.getValue("data").jsonPrimitive.content, other.getValue("data").jsonPrimitive.content)
        assertEquals(webPlain, LedgerCrypto.decryptIfEncrypted(second, PASSWORD))
    }

    // ---------------------------------------------------------------- 信封字段

    @Test
    fun `envelope fields match the web drive-ledger-encrypted v1 format`() {
        val envelope = parse(androidEnvelope)
        assertEquals(LedgerCrypto.FORMAT, envelope.getValue("format").jsonPrimitive.contentOrNull)
        assertEquals(LedgerCrypto.VERSION, envelope.getValue("version").jsonPrimitive.int)
        assertEquals(LedgerCrypto.ALGORITHM, envelope.getValue("algorithm").jsonPrimitive.contentOrNull)
        assertEquals(LedgerCrypto.KDF, envelope.getValue("kdf").jsonPrimitive.contentOrNull)
        assertEquals(LedgerCrypto.ITERATIONS, envelope.getValue("iterations").jsonPrimitive.int)
        assertEquals(16, Base64.getDecoder().decode(envelope.getValue("salt").jsonPrimitive.content).size)
        assertEquals(12, Base64.getDecoder().decode(envelope.getValue("iv").jsonPrimitive.content).size)
        // GCM tag 128 bit = 16 字节附在密文尾部。
        assertTrue(Base64.getDecoder().decode(envelope.getValue("data").jsonPrimitive.content).size > 16)
        // 与 Web `JSON.stringify` 一致：compact 单行，不带换行/缩进。
        assertFalse(androidEnvelope.contains('\n'))
        assertTrue(
            androidEnvelope.startsWith(
                """{"format":"drive-ledger-encrypted","version":1,"algorithm":"AES-GCM-256","kdf":"PBKDF2-SHA256","iterations":600000,"salt":"""",
            ),
        )
        // 标准 base64 带 padding（16B/12B/密文长度下必然出现 `=`）。
        assertTrue(envelope.getValue("data").jsonPrimitive.content.endsWith("=") ||
            envelope.getValue("salt").jsonPrimitive.content.endsWith("="))
    }

    // ---------------------------------------------------------------- Web → Android fixture

    @Test
    fun `decrypts web generated envelope with cjk password`() {
        val decrypted = LedgerCrypto.decryptIfEncrypted(webEnvelope, PASSWORD)
        assertEquals(webPlain, decrypted)

        val root = Json.parseToJsonElement(decrypted).jsonObject
        assertEquals(2, root.getValue("version").jsonPrimitive.int)
        assertNotNull(root["cars"])
    }

    @Test
    fun `web generated envelope is recognized as encrypted`() {
        assertTrue(LedgerCrypto.isEncrypted(webEnvelope))
        assertTrue(LedgerCrypto.isEncrypted(androidEnvelope))
    }

    // ---------------------------------------------------------------- 明文原样返回 / isEncrypted

    @Test
    fun `plain text is returned unchanged`() {
        assertEquals(webPlain, LedgerCrypto.decryptIfEncrypted(webPlain, PASSWORD))
        assertEquals("{\"version\":2,\"cars\":[]}", LedgerCrypto.decryptIfEncrypted("{\"version\":2,\"cars\":[]}", PASSWORD))
        assertEquals("hello 里程志", LedgerCrypto.decryptIfEncrypted("hello 里程志", PASSWORD))
        assertEquals("[1,2,3]", LedgerCrypto.decryptIfEncrypted("[1,2,3]", PASSWORD))
        assertEquals("{\"format\":\"drive-ledger-connection\",\"version\":1}", LedgerCrypto.decryptIfEncrypted("{\"format\":\"drive-ledger-connection\",\"version\":1}", PASSWORD))
        // 空密码也不应影响明文直通（不触发解密）。
        assertEquals(webPlain, LedgerCrypto.decryptIfEncrypted(webPlain, ""))
    }

    @Test
    fun `isEncrypted detects envelopes only`() {
        assertTrue(LedgerCrypto.isEncrypted(androidEnvelope))
        // 即使 version 等字段不受支持，format 命中即视为信封。
        assertTrue(LedgerCrypto.isEncrypted(mutate { it["version"] = JsonPrimitive(9) }))

        assertFalse(LedgerCrypto.isEncrypted(webPlain))
        assertFalse(LedgerCrypto.isEncrypted("{}"))
        assertFalse(LedgerCrypto.isEncrypted("[]"))
        assertFalse(LedgerCrypto.isEncrypted("not json"))
        assertFalse(LedgerCrypto.isEncrypted(""))
        assertFalse(LedgerCrypto.isEncrypted("42"))
        assertFalse(LedgerCrypto.isEncrypted("{\"format\":1}"))
        assertFalse(LedgerCrypto.isEncrypted("{\"format\":\"drive-ledger-connection\"}"))
    }

    // ---------------------------------------------------------------- 字段校验失败

    @Test
    fun `unsupported version algorithm kdf or iterations is rejected before decryption`() {
        val cases = listOf<Pair<String, (java.util.LinkedHashMap<String, JsonElement>) -> Unit>>(
            "version 2" to { map -> map["version"] = JsonPrimitive(2) },
            "version as string" to { map -> map["version"] = JsonPrimitive("1") },
            "algorithm AES-CBC" to { map -> map["algorithm"] = JsonPrimitive("AES-CBC-256") },
            "algorithm missing" to { map -> map.remove("algorithm") },
            "kdf PBKDF2-SHA512" to { map -> map["kdf"] = JsonPrimitive("PBKDF2-SHA512") },
            "iterations 599999" to { map -> map["iterations"] = JsonPrimitive(599_999) },
            "iterations as string" to { map -> map["iterations"] = JsonPrimitive("600000") },
            "salt 15 bytes" to { map -> map["salt"] = JsonPrimitive(b64(ByteArray(15))) },
            "salt 17 bytes" to { map -> map["salt"] = JsonPrimitive(b64(ByteArray(17))) },
            "salt not base64" to { map -> map["salt"] = JsonPrimitive("not-base64!!") },
            "salt missing" to { map -> map.remove("salt") },
            "iv 13 bytes" to { map -> map["iv"] = JsonPrimitive(b64(ByteArray(13))) },
            "iv 11 bytes" to { map -> map["iv"] = JsonPrimitive(b64(ByteArray(11))) },
            "iv missing" to { map -> map.remove("iv") },
        )
        for ((name, change) in cases) {
            val broken = mutate(change = change)
            val error = assertThrows("用例：$name", BackupException::class.java) {
                LedgerCrypto.decryptIfEncrypted(broken, PASSWORD)
            }
            assertEquals("用例：$name", tr(UNSUPPORTED_ZH, UNSUPPORTED_EN), error.message)
        }
    }

    @Test
    fun `missing or invalid data reports decryption failure`() {
        val missing = mutate { it.remove("data") }
        assertEqualsMessage(
            DECRYPT_FAILED_ZH,
            DECRYPT_FAILED_EN,
            assertThrows(BackupException::class.java) { LedgerCrypto.decryptIfEncrypted(missing, PASSWORD) },
        )
        val invalid = mutate { it["data"] = JsonPrimitive("***") }
        assertEqualsMessage(
            DECRYPT_FAILED_ZH,
            DECRYPT_FAILED_EN,
            assertThrows(BackupException::class.java) { LedgerCrypto.decryptIfEncrypted(invalid, PASSWORD) },
        )
    }

    // ---------------------------------------------------------------- 密码长度 / 反向互操作落盘

    @Test
    fun `password shorter than 8 characters is rejected`() {
        assertEquals(8, LedgerCrypto.MIN_PASSWORD_LENGTH)
        val error = assertThrows(BackupException::class.java) {
            LedgerCrypto.encrypt(webPlain, "1234567")
        }
        assertEqualsMessage(PASSWORD_SHORT_ZH, PASSWORD_SHORT_EN, error)
    }

    @Test
    fun `writes android envelope for reverse web interop`() {
        val tmp = domainBuildTmpDir()
        val envelopeFile = File(tmp, "webdav-android-envelope.json")
        val plainFile = File(tmp, "webdav-android-plain.json")
        envelopeFile.writeText(androidEnvelope, Charsets.UTF_8)
        plainFile.writeText(webPlain, Charsets.UTF_8)
        File(tmp, "webdav-android-interop.txt").writeText(
            buildString {
                appendLine("# crypto-core 反向互操作产物（domain/build/tmp，不提交）")
                appendLine("password=$PASSWORD")
                appendLine(
                    "verify: node /tmp/milenote-webdav-verify.mjs " +
                        "${envelopeFile.absolutePath} $PASSWORD ${plainFile.absolutePath}",
                )
            },
            Charsets.UTF_8,
        )
        println("reverse-interop envelope: ${envelopeFile.absolutePath}")

        assertTrue(envelopeFile.isFile)
        assertEquals(androidEnvelope, envelopeFile.readText(Charsets.UTF_8))
        assertEquals(webPlain, plainFile.readText(Charsets.UTF_8))
        assertTrue(LedgerCrypto.isEncrypted(envelopeFile.readText(Charsets.UTF_8)))
    }

    // ---------------------------------------------------------------- helpers

    private fun domainBuildTmpDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val domainDir = if (cwd.name == "domain") cwd else File(cwd, "domain").takeIf { it.isDirectory } ?: cwd
        return File(domainDir, "build/tmp").also { it.mkdirs() }
    }

    private fun parse(envelope: String): JsonObject = Json.parseToJsonElement(envelope).jsonObject

    /** 复制信封并改字段，保持字段顺序；默认作用于 Android 生成的共享信封。 */
    private fun mutate(
        base: String = androidEnvelope,
        change: (java.util.LinkedHashMap<String, JsonElement>) -> Unit,
    ): String {
        val map = LinkedHashMap(parse(base))
        change(map)
        return Json.encodeToString(JsonObject.serializer(), JsonObject(map))
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
