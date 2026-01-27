package com.example.selectiveproxy

import android.content.Context
import android.os.Parcelable
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.parcelize.Parcelize
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Parcelize
data class SecureProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val encryptedPasswordBase64: String? = null,
    val type: ProxyType = ProxyType.HTTP,
    val blockAllDoh: Boolean = true
) : Parcelable {
    enum class ProxyType : Parcelable { HTTP, SOCKS5 }

    companion object {
        private const val TAG = "SecureProxyConfig"
        private const val KEY_ALIAS = "proxy_password_key"

        fun create(
            host: String,
            port: Int,
            username: String?,
            password: CharArray?,
            context: Context,
            blockAllDoh: Boolean = true
        ): SecureProxyConfig {
            val encryptedBase64 = password?.let { pass ->
                try {
                    ensureKeyExists()
                    val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val key = keystore.getKey(KEY_ALIAS, null) as? SecretKey ?: return@let null
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, key)
                    }
                    val iv = cipher.iv
                    val bytes = ByteArray(pass.size) { i -> pass[i].code.toByte() }
                    val encryptedBytes = cipher.doFinal(bytes)
                    java.util.Arrays.fill(bytes, 0.toByte())
                    val result = iv + encryptedBytes
                    Base64.encodeToString(result, Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.e(TAG, "Password encryption failed", e)
                    null
                }
            }
            return SecureProxyConfig(host, port, username, encryptedBase64, ProxyType.HTTP, blockAllDoh)
        }

        private fun ensureKeyExists() {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keystore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                val keySpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
                keyGenerator.init(keySpec)
                keyGenerator.generateKey()
            }
        }
    }

    private val encryptedPassword: ByteArray?
        get() = encryptedPasswordBase64?.let {
            Base64.decode(it, Base64.NO_WRAP)
        }

    fun getPasswordDecrypted(context: Context): CharArray? = encryptedPassword?.let { encrypted ->
        try {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keystore.getKey(KEY_ALIAS, null) as? SecretKey ?: return@let null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encrypted.copyOfRange(0, 12)
            val encryptedData = encrypted.copyOfRange(12, encrypted.size)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encryptedData)
            val chars = CharArray(decrypted.size)
            for (i in decrypted.indices) {
                chars[i] = decrypted[i].toChar()
            }
            java.util.Arrays.fill(decrypted, 0.toByte())
            chars
        } catch (e: Exception) {
            Log.e(TAG, "Password decryption failed", e)
            null
        }
    }
}
