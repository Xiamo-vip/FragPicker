package com.fragpicker.android.feature.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.fragpicker.android.core.network.*
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

data class PendingMessage(val question: String, val key: String, val createKey: String,
    val sessionId: Long? = null, val turnId: Long? = null) {
    override fun toString() = "PendingMessage[content=REDACTED]"
}

/** Call on IO. AAD and preference namespace bind requests to backend + account. */
class PendingMessageVault(context: Context, baseUrl: String, userId: Long) {
    private val preferences = context.applicationContext.getSharedPreferences("chat_pending", Context.MODE_PRIVATE)
    private val scope = "v1:${baseUrl.trimEnd('/')}:$userId"
    fun save(value: PendingMessage) {
        validate(value)
        val body = JSONObject().put("question", value.question).put("key", value.key).put("createKey", value.createKey)
            .put("sessionId", value.sessionId ?: JSONObject.NULL).put("turnId", value.turnId ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(scope.toByteArray(Charsets.UTF_8)) }
            val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(body), Base64.NO_WRAP)
            if (!preferences.edit().putString(scope, sealed).commit()) throw IOException("Pending request could not be saved")
        } finally { body.fill(0) }
    }
    fun load(): PendingMessage? {
        val sealed = preferences.getString(scope, null) ?: return null
        require(sealed.length <= 24_000)
        val parts = sealed.split(':', limit = 2); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            updateAAD(scope.toByteArray(Charsets.UTF_8))
        }
        val bytes = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            PendingMessage(json.getString("question"), json.getString("key"), json.getString("createKey"),
                json.optionalLong("sessionId"), json.optionalLong("turnId")).also(::validate)
        } finally { bytes.fill(0) }
    }
    fun clear() { if (!preferences.edit().remove(scope).commit()) throw IOException("Pending request could not be cleared") }
    private fun validate(value: PendingMessage) {
        require(value.question.isNotBlank() && value.question.length <= 4000 && value.question.codePointCount(0, value.question.length) <= 2000)
        UUID.fromString(value.key); UUID.fromString(value.createKey)
        require(value.sessionId == null || value.sessionId > 0); require(value.turnId == null || value.turnId > 0 && value.sessionId != null)
    }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build()); generateKey()
        }
    }
    companion object { private const val KEY_ALIAS = "fragpicker-pending-chat-v1"; private val keyLock = Any() }
}
