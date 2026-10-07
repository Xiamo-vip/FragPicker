package com.fragpicker.android.feature.history

import android.content.SharedPreferences
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** Only date + random request ID; no text, tokens or media. Private app data, backups disabled. */
class DigestRequestStore(private val preferences: SharedPreferences, baseUrl: String, userId: Long) {
    private val scope = "v1:${baseUrl.trimEnd('/')}:$userId:"
    fun load(date: String): String? {
        LocalDate.parse(date)
        return preferences.getString(scope + date, null)?.also { require(UUID.fromString(it).toString() == it) }
    }
    fun save(date: String, key: String) {
        LocalDate.parse(date); require(UUID.fromString(key).toString() == key)
        if (!preferences.edit().putString(scope + date, key).commit()) throw IOException("Digest request could not be saved")
    }
    fun clear(date: String) {
        if (!preferences.edit().remove(scope + date).commit()) throw IOException("Digest request could not be cleared")
    }
}
