package com.fragpicker.android

import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.feature.clipboard.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class ClipboardRequestStoreTest {
    @Test fun retainsConfirmedRequestAndDecisionAcrossRestartsWithoutCrossingAccountOrBackend() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("clipboard_store_test", 0)
        prefs.edit().clear().commit()
        try {
            val original = ClipboardRequestStore(prefs, "https://example.com", 1)
            val request = PendingClipboardFeed(UUID.randomUUID().toString(), ClipboardPreview("https://b23.tv/Clipboard123", "视频", "作者", "b23.tv"))
            original.save(request); original.markSeen("a".repeat(64))
            val restored = ClipboardRequestStore(prefs, "https://example.com/", 1)
            assertEquals(request, restored.load()); assertTrue(restored.seen("a".repeat(64)))
            assertNull(ClipboardRequestStore(prefs, "https://example.com", 2).load())
            assertNull(ClipboardRequestStore(prefs, "https://other.example.com", 1).load())
            assertFalse(ClipboardRequestStore(prefs, "https://example.com", 2).seen("a".repeat(64)))
            assertThrows(Exception::class.java) { restored.save(request.copy(key = UUID.randomUUID().toString())) }
            restored.clear(UUID.randomUUID().toString()); assertEquals(request, restored.load())
            restored.clear(request.key); assertNull(restored.load())
            assertThrows(Exception::class.java) { original.save(request.copy(key = "invalid")) }
            assertThrows(Exception::class.java) { original.save(request.copy(preview = request.preview.copy(url = "https://user:password@b23.tv/secret"))) }
            assertFalse(prefs.all.values.toString().contains("私人笔记"))
        } finally { prefs.edit().clear().commit() }
    }
}