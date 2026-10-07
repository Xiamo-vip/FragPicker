package com.fragpicker.android.feature.feed

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.util.UUID

class IncomingShareViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val pending = saved.getStateFlow<Bundle?>("incomingShare", null)
    fun accept(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()?.take(4096)?.trim()
        if (text.isNullOrBlank()) return
        saved["incomingShare"] = Bundle().apply { putString("id", UUID.randomUUID().toString()); putString("text", text) }
    }
    fun consume(id: String) { if (pending.value?.getString("id") == id) saved["incomingShare"] = null }
    fun clear() { saved["incomingShare"] = null }
}
