package com.fragpicker.android.feature.clipboard

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class ClipboardPreview(val url: String, val title: String, val author: String, val host: String) {
    override fun toString() = "ClipboardPreview[REDACTED]"
}
data class PendingClipboardFeed(val key: String, val preview: ClipboardPreview) {
    override fun toString() = "PendingClipboardFeed[REDACTED]"
}

/** Only the URL the user has confirmed is durable; the original clipboard text is never stored. */
class ClipboardRequestStore(private val preferences: SharedPreferences, baseUrl: String, userId: Long) {
    private val scope = ClipboardLinks.digest("${baseUrl.trimEnd('/')}:$userId")
    fun seen(fingerprint: String) = preferences.getString("$scope:seen", null) == fingerprint
    fun markSeen(fingerprint: String) {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        if (!preferences.edit().putString("$scope:seen", fingerprint).commit()) throw IOException("Clipboard decision could not be saved")
    }
    fun load(): PendingClipboardFeed? = preferences.getString("$scope:pending", null)?.let { text ->
        require(text.length <= 8192)
        val json = JSONObject(text)
        val preview = ClipboardPreview(json.getString("url"), json.getString("title"), json.getString("author"), json.getString("host"))
        PendingClipboardFeed(json.getString("key"), preview).also(::validate)
    }
    fun save(request: PendingClipboardFeed) {
        validate(request)
        val existing = load()
        require(existing == null || existing == request)
        val value = JSONObject().put("key", request.key).put("url", request.preview.url)
            .put("title", request.preview.title).put("author", request.preview.author).put("host", request.preview.host)
        if (!preferences.edit().putString("$scope:pending", value.toString()).commit()) throw IOException("Clipboard request could not be saved")
    }
    fun clear(key: String) {
        if (load()?.key == key && !preferences.edit().remove("$scope:pending").commit()) throw IOException("Clipboard request could not be cleared")
    }
    private fun validate(request: PendingClipboardFeed) {
        require(UUID.fromString(request.key).toString() == request.key)
        require(ClipboardLinks.extract(request.preview.url) == request.preview.url)
        require(request.preview.title.length in 1..320 && request.preview.author.length <= 160)
        require(java.net.URI(request.preview.url).host == request.preview.host)
    }
}