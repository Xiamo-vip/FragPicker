package com.fragpicker.android.feature.retry

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class PendingRetry(val key: String, val replace: Boolean)

/** One durable identity per backend/account/fragment. No user text, cloud IDs or tokens. */
class RetryRequestStore(private val preferences: SharedPreferences, baseUrl: String, userId: Long) {
    private val scope = "v1:${baseUrl.trimEnd('/')}:$userId:"
    companion object { private val lock = Any() }
    fun load(id: Long): PendingRetry? = synchronized(lock) {
        require(id > 0)
        preferences.getString(scope + id, null)?.let {
            val json = JSONObject(it)
            val key = json.getString("key"); require(UUID.fromString(key).toString() == key)
            PendingRetry(key, json.getBoolean("replace"))
        }
    }
    fun getOrCreate(id: Long, replace: Boolean): PendingRetry = synchronized(lock) {
        load(id) ?: PendingRetry(UUID.randomUUID().toString(), replace).also { save(id, it) }
    }
    fun save(id: Long, request: PendingRetry) = synchronized(lock) {
        require(id > 0 && UUID.fromString(request.key).toString() == request.key)
        if (!preferences.edit().putString(scope + id, JSONObject().put("key", request.key).put("replace", request.replace).toString()).commit())
            throw IOException("Retry request could not be saved")
    }
    fun clear(id: Long, key: String) = synchronized(lock) {
        if (load(id)?.key == key && !preferences.edit().remove(scope + id).commit()) throw IOException("Retry request could not be cleared")
    }
}
