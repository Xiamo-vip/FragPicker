package com.fragpicker.android.feature.deletion

import android.content.SharedPreferences
import java.io.IOException

/** Only the acknowledged user intent is persisted, scoped to backend/account/content. */
class DeletionRequestStore(private val preferences: SharedPreferences, baseUrl: String, userId: Long) {
    private val scope = "v1:${baseUrl.trimEnd('/')}:$userId:"
    companion object { private val lock = Any() }
    fun pending(id: Long): Boolean = synchronized(lock) { require(id > 0); preferences.getBoolean(scope + id, false) }
    fun save(id: Long) = synchronized(lock) {
        require(id > 0)
        if (!preferences.edit().putBoolean(scope + id, true).commit()) throw IOException("Deletion intent could not be saved")
    }
    fun clear(id: Long) = synchronized(lock) {
        require(id > 0)
        if (!preferences.edit().remove(scope + id).commit()) throw IOException("Deletion intent could not be cleared")
    }
}
