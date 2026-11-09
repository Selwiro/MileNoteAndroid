package com.milenote.android.data.webdav

import com.milenote.android.domain.backup.BackupException
import com.milenote.android.domain.backup.LedgerCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app 层账本编解码口径单测（纯 JVM，不依赖 Android framework）。
 *
 * 加密算法真源在 domain [LedgerCrypto]（crypto-core 负责实现与互操作 fixture）；本测试只锁定
 * app 数据层依赖的契约：明文/信封识别、正确/错误密码，以及「同步比较的 hash 一律用明文」的
 * 口径（[plainLedgerHash]，[WebDavRepository.plainHash] 委托它）。
 *
 * 为避免每个用例都跑一次 600000 次 PBKDF2，信封在 companion 里惰性生成一次复用。
 */
class WebDavLedgerCodecTest {

    @Test
    fun `plain json is not treated as envelope`() {
        assertFalse(LedgerCrypto.isEncrypted(PLAIN))
    }

    @Test
    fun `non json and empty object are not treated as envelope`() {
        assertFalse(LedgerCrypto.isEncrypted("not json at all"))
        assertFalse(LedgerCrypto.isEncrypted("{}"))
        assertFalse(LedgerCrypto.isEncrypted("""{"format":"drive-ledger"}"""))
    }

    @Test
    fun `encrypted envelope is detected and carries expected format fields`() {
        assertTrue(LedgerCrypto.isEncrypted(ENVELOPE))
        assertTrue(ENVELOPE.contains("\"format\":\"drive-ledger-encrypted\""))
        assertTrue(ENVELOPE.contains("\"version\":1"))
        assertTrue(ENVELOPE.contains("\"algorithm\":\"AES-GCM-256\""))
        assertTrue(ENVELOPE.contains("\"kdf\":\"PBKDF2-SHA256\""))
        assertTrue(ENVELOPE.contains("\"iterations\":600000"))
    }

    @Test
    fun `correct password decrypts back to plain json`() {
        assertEquals(PLAIN, LedgerCrypto.decryptIfEncrypted(ENVELOPE, PASSWORD))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals(PLAIN, LedgerCrypto.decryptIfEncrypted(PLAIN, PASSWORD))
    }

    @Test
    fun `wrong password fails with decryption error`() {
        val error = assertThrows(BackupException::class.java) {
            LedgerCrypto.decryptIfEncrypted(ENVELOPE, "$PASSWORD-wrong")
        }
        assertTrue(
            "错误密码应给出解密失败提示，实际：${error.message}",
            error.message.orEmpty().contains("解密失败"),
        )
    }

    @Test
    fun `too short password is rejected before encryption`() {
        assertThrows(BackupException::class.java) { LedgerCrypto.encrypt(PLAIN, "1234567") }
    }

    @Test
    fun `envelope is randomized per encryption so ciphertext hash cannot be compared`() {
        val second = LedgerCrypto.encrypt(PLAIN, PASSWORD)
        assertNotEquals("随机 salt/iv 应让两次密文不同", ENVELOPE, second)
        assertEquals(PLAIN, LedgerCrypto.decryptIfEncrypted(second, PASSWORD))
        assertNotEquals(
            "密文 hash 每次不同，不能用于同步比较",
            Sha256.hex(ENVELOPE.toByteArray(Charsets.UTF_8)),
            Sha256.hex(second.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `plain hash uses plaintext regardless of encryption`() {
        val decrypted = LedgerCrypto.decryptIfEncrypted(ENVELOPE, PASSWORD)
        assertEquals(
            "加密文件与明文文件的内容 hash 必须一致（都用明文算）",
            plainLedgerHash(PLAIN),
            plainLedgerHash(decrypted),
        )
    }

    @Test
    fun `plain hash matches sha256 of utf8 plaintext and changes with content`() {
        assertEquals(Sha256.hex(PLAIN.toByteArray(Charsets.UTF_8)), plainLedgerHash(PLAIN))
        assertNotEquals(plainLedgerHash(PLAIN), plainLedgerHash("$PLAIN "))
    }

    private companion object {
        const val PASSWORD = "ledger-secret-123"

        /** 与 [com.milenote.android.domain.backup.BackupCodec.encode] 同形状的紧凑 JSON（含中文）。 */
        const val PLAIN = """{"version":2,"records":[{"id":"r1","amount":12.5,"note":"跨端"}]}"""

        val ENVELOPE: String by lazy { LedgerCrypto.encrypt(PLAIN, PASSWORD) }
    }
}
