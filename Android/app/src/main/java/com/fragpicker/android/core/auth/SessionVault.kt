package com.fragpicker.android.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fragpicker.android.BuildConfig
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Call on IO dispatcher. Stores only encrypted refresh credentials; no password or access token. */
class SessionVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("account_session", Context.MODE_PRIVATE)
    private val aad = ("fragpicker-session-v1:" + BuildConfig.API_BASE_URL.trimEnd('/')).toByteArray(Charsets.UTF_8)

    fun save(refreshToken: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val bytes = refreshToken.toByteArray(Charsets.UTF_8)
        try {
            val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP)
            if (!preferences.edit().putString("sealed_refresh", sealed).commit()) throw IOException("Session could not be saved")
        } finally { bytes.fill(0) }
    }

    fun consume(): String? {
        val sealed = preferences.getString("sealed_refresh", null) ?: return null
        // Commit before sending a one-use token: an interrupted refresh must never replay it.
        clear()
        val parts = sealed.split(':', limit = 2)
        require(parts.size == 2) { "Invalid local session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(aad)
        val bytes = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    fun clear() {
        if (!preferences.edit().clear().commit()) throw IOException("Session could not be cleared")
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "fragpicker-session-v1"
        private val keyLock = Any()
    }
}
