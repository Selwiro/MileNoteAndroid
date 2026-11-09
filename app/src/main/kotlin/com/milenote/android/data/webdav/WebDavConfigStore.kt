package com.milenote.android.data.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.milenote.android.domain.backup.LedgerCrypto
import com.milenote.android.i18n.tr
import com.milenote.android.platform.webdav.WebDavException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.webDavConfigDataStore by preferencesDataStore(name = "milenote-webdav")

/**
 * WebDAV 配置持久化。
 *
 * 存储策略：
 * - 普通字段（地址 / 用户名 / 是否允许 HTTP）存 DataStore Preferences（文件 `milenote-webdav`）；
 * - **两个密码都不存明文**：用 Android Keystore 的 AES-256-GCM 密钥加密后，只存「密文 Base64 + 随机 IV Base64」；
 *   WebDAV 应用密码与账本加密密码**各用一个独立 Keystore 别名**（[PasswordCipher.WEBDAV_ALIAS] /
 *   [PasswordCipher.LEDGER_ALIAS]），避免两者混用；密钥不出 Keystore。
 *
 * 对外只暴露 [config]（含 `hasPassword` / `hasEncryptionPassword`，不含密码），明文密码只能通过
 * `internal` 的 [readPassword] / [readEncryptionPassword] 读取，且仅供同模块的 Repository 使用。
 */
class WebDavConfigStore(private val context: Context) {

    private val passwordCipher = PasswordCipher(PasswordCipher.WEBDAV_ALIAS)
    private val encryptionCipher = PasswordCipher(PasswordCipher.LEDGER_ALIAS)

    private object Keys {
        val fileUrl = stringPreferencesKey("file_url")
        val username = stringPreferencesKey("username")
        val allowHttp = booleanPreferencesKey("allow_http")
        val passwordCipher = stringPreferencesKey("password_cipher")
        val passwordIv = stringPreferencesKey("password_iv")
        val encryptionPasswordCipher = stringPreferencesKey("encryption_password_cipher")
        val encryptionPasswordIv = stringPreferencesKey("encryption_password_iv")
    }

    /** 当前配置；未配置（无地址）时为 null。**不含密码明文**。 */
    val config: Flow<WebDavConfigState?> = context.webDavConfigDataStore.data.map { prefs ->
        val fileUrl = prefs[Keys.fileUrl].orEmpty()
        if (fileUrl.isBlank()) {
            null
        } else {
            WebDavConfigState(
                fileUrl = fileUrl,
                username = prefs[Keys.username].orEmpty(),
                allowHttp = prefs[Keys.allowHttp] ?: false,
                hasPassword = !prefs[Keys.passwordCipher].isNullOrBlank(),
                hasEncryptionPassword = !prefs[Keys.encryptionPasswordCipher].isNullOrBlank(),
            )
        }
    }

    /**
     * 保存配置。
     *
     * @param password 密码明文；`null` 或空白表示**保留已保存的密码**（编辑时不必重新输入）。
     *   首次配置必须提供密码，否则抛「请输入应用密码」。
     * @param encryptionPassword 账本加密密码明文；`null` 或空白表示**保留已保存的加密密码**。
     * @param enableEncryption 是否开启加密上传。`false` 时清除已保存的加密密码密文（远端已有文件不动）。
     * @throws WebDavException 开启加密但没有可用的加密密码 / 新密码短于 [LedgerCrypto.MIN_PASSWORD_LENGTH]。
     */
    suspend fun save(
        fileUrl: String,
        username: String,
        password: String?,
        allowHttp: Boolean,
        encryptionPassword: String?,
        enableEncryption: Boolean,
    ) {
        val existing = context.webDavConfigDataStore.data.first()
        val hasExistingPassword = !existing[Keys.passwordCipher].isNullOrBlank()
        if (password.isNullOrBlank() && !hasExistingPassword) {
            throw WebDavException(tr("请输入应用密码", "Enter the app password"))
        }
        val encrypted: Pair<String, String>? = if (!password.isNullOrBlank()) {
            passwordCipher.encrypt(password)
        } else {
            null
        }
        val hasExistingEncryption = !existing[Keys.encryptionPasswordCipher].isNullOrBlank()
        val newEncryption: Pair<String, String>? = when {
            !enableEncryption -> null
            !encryptionPassword.isNullOrBlank() -> {
                if (encryptionPassword.length < LedgerCrypto.MIN_PASSWORD_LENGTH) {
                    throw WebDavException(
                        tr("加密密码至少需要 8 个字符", "The encryption password must be at least 8 characters"),
                    )
                }
                encryptionCipher.encrypt(encryptionPassword)
            }
            hasExistingEncryption -> null // 留空 = 沿用已保存的加密密码
            else -> throw WebDavException(tr("请设置加密密码", "Set an encryption password"))
        }
        context.webDavConfigDataStore.edit { prefs ->
            prefs[Keys.fileUrl] = fileUrl
            prefs[Keys.username] = username
            prefs[Keys.allowHttp] = allowHttp
            if (encrypted != null) {
                prefs[Keys.passwordCipher] = encrypted.first
                prefs[Keys.passwordIv] = encrypted.second
            }
            when {
                !enableEncryption -> {
                    prefs.remove(Keys.encryptionPasswordCipher)
                    prefs.remove(Keys.encryptionPasswordIv)
                }
                newEncryption != null -> {
                    prefs[Keys.encryptionPasswordCipher] = newEncryption.first
                    prefs[Keys.encryptionPasswordIv] = newEncryption.second
                }
                // 否则保留已保存的加密密码密文。
            }
        }
    }

    /**
     * 读取解密后的密码（仅供 Repository 使用，不对 UI 暴露）。
     *
     * @return 密码明文；未保存或解密失败时 null。
     */
    internal suspend fun readPassword(): String? {
        val prefs = context.webDavConfigDataStore.data.first()
        val cipherText = prefs[Keys.passwordCipher] ?: return null
        val iv = prefs[Keys.passwordIv] ?: return null
        return passwordCipher.decrypt(cipherText, iv)
    }

    /**
     * 读取解密后的账本加密密码（仅供 Repository 使用）。
     *
     * @return 加密密码明文；未保存或解密失败时 null。
     */
    internal suspend fun readEncryptionPassword(): String? {
        val prefs = context.webDavConfigDataStore.data.first()
        val cipherText = prefs[Keys.encryptionPasswordCipher] ?: return null
        val iv = prefs[Keys.encryptionPasswordIv] ?: return null
        return encryptionCipher.decrypt(cipherText, iv)
    }

    /**
     * 保存/更新账本加密密码（= 开启加密上传）；长度按 [LedgerCrypto.MIN_PASSWORD_LENGTH] 校验。
     *
     * @throws WebDavException 密码过短（中文提示）。
     */
    internal suspend fun saveEncryptionPassword(password: String) {
        if (password.length < LedgerCrypto.MIN_PASSWORD_LENGTH) {
            throw WebDavException(
                tr("加密密码至少需要 8 个字符", "The encryption password must be at least 8 characters"),
            )
        }
        val encrypted = encryptionCipher.encrypt(password)
        context.webDavConfigDataStore.edit { prefs ->
            prefs[Keys.encryptionPasswordCipher] = encrypted.first
            prefs[Keys.encryptionPasswordIv] = encrypted.second
        }
    }

    /** 是否已保存密码。 */
    suspend fun hasPassword(): Boolean {
        val prefs = context.webDavConfigDataStore.data.first()
        return !prefs[Keys.passwordCipher].isNullOrBlank()
    }

    /** 清除全部配置（含 WebDAV 应用密码与账本加密密码密文）。 */
    suspend fun clear() {
        context.webDavConfigDataStore.edit { prefs -> prefs.clear() }
    }
}

/**
 * Android Keystore AES-256-GCM 密码加解密（每个用途一个独立密钥别名）。
 *
 * 每次加密使用随机 IV（由 Cipher 生成并随密文一起保存），不使用固定 IV；
 * 解密失败（如密钥被系统失效、密文损坏）返回 null，由上层提示重新输入密码。
 */
internal class PasswordCipher(private val alias: String) {

    companion object {
        /** WebDAV 应用密码的 Keystore 密钥别名（保持历史值，老配置可继续解密）。 */
        const val WEBDAV_ALIAS = "milenote-webdav-password"

        /** 账本加密密码的独立 Keystore 密钥别名（与 WebDAV 应用密码分开）。 */
        const val LEDGER_ALIAS = "milenote-webdav-ledger-password"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** @return (密文 Base64, 随机 IV Base64) */
    fun encrypt(plainText: String): Pair<String, String> {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(encrypted) to
                Base64.getEncoder().encodeToString(cipher.iv)
        } catch (t: Throwable) {
            throw WebDavException(
                tr(
                    "密码加密失败，请重试（设备安全模块可能暂时不可用）",
                    "Password encryption failed — try again (the device security module may be temporarily unavailable)",
                ),
                t,
            )
        }
    }

    /** 解密失败返回 null（不抛异常，由上层提示重新输入密码）。 */
    fun decrypt(cipherTextBase64: String, ivBase64: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.getDecoder().decode(ivBase64)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64)), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }
}
