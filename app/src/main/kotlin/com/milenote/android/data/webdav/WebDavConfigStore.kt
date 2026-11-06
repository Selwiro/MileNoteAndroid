package com.milenote.android.data.webdav

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * - **密码不存明文**：用 Android Keystore 的 AES-256-GCM 密钥加密后，只存「密文 Base64 + 随机 IV Base64」；
 *   密钥别名固定 [PasswordCipher.ALIAS]，不出 Keystore。
 *
 * 对外只暴露 [config]（含 `hasPassword`，不含密码），明文密码只能通过 `internal` 的 [readPassword] 读取，
 * 且仅供同模块的 Repository 组装请求使用。
 */
class WebDavConfigStore(private val context: Context) {

    private object Keys {
        val fileUrl = stringPreferencesKey("file_url")
        val username = stringPreferencesKey("username")
        val allowHttp = booleanPreferencesKey("allow_http")
        val passwordCipher = stringPreferencesKey("password_cipher")
        val passwordIv = stringPreferencesKey("password_iv")
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
            )
        }
    }

    /**
     * 保存配置。
     *
     * @param password 密码明文；`null` 或空白表示**保留已保存的密码**（编辑时不必重新输入）。
     *   首次配置必须提供密码，否则抛「请输入应用密码」。
     */
    suspend fun save(
        fileUrl: String,
        username: String,
        password: String?,
        allowHttp: Boolean,
    ) {
        val existing = context.webDavConfigDataStore.data.first()
        val hasExistingPassword = !existing[Keys.passwordCipher].isNullOrBlank()
        if (password.isNullOrBlank() && !hasExistingPassword) {
            throw WebDavException("请输入应用密码")
        }
        val encrypted: Pair<String, String>? = if (!password.isNullOrBlank()) {
            PasswordCipher.encrypt(password)
        } else {
            null
        }
        context.webDavConfigDataStore.edit { prefs ->
            prefs[Keys.fileUrl] = fileUrl
            prefs[Keys.username] = username
            prefs[Keys.allowHttp] = allowHttp
            if (encrypted != null) {
                prefs[Keys.passwordCipher] = encrypted.first
                prefs[Keys.passwordIv] = encrypted.second
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
        return PasswordCipher.decrypt(cipherText, iv)
    }

    /** 是否已保存密码。 */
    suspend fun hasPassword(): Boolean {
        val prefs = context.webDavConfigDataStore.data.first()
        return !prefs[Keys.passwordCipher].isNullOrBlank()
    }

    /** 清除全部配置（含密码密文）。 */
    suspend fun clear() {
        context.webDavConfigDataStore.edit { prefs -> prefs.clear() }
    }
}

/**
 * Android Keystore AES-256-GCM 密码加解密。
 *
 * 每次加密使用随机 IV（由 Cipher 生成并随密文一起保存），不使用固定 IV；
 * 解密失败（如密钥被系统失效、密文损坏）返回 null，由上层提示重新输入密码。
 */
internal object PasswordCipher {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "milenote-webdav-password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val KEY_SIZE_BITS = 256

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
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
            throw WebDavException("密码加密失败，请重试（设备安全模块可能暂时不可用）", t)
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
