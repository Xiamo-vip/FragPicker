package com.fragpicker.android

import android.content.ClipData
import android.os.PersistableBundle
import com.fragpicker.android.feature.clipboard.ClipboardLinks
import org.junit.Test
import org.junit.Assert.*

class ClipboardLinksTest {
    @Test fun extractsOnlyOneCanonicalLinkAndDropsShareCaption() {
        assertEquals("https://b23.tv/Clipboard123?x=1", ClipboardLinks.extract("私人备注 HTTPS://B23.TV:443/Clipboard123?x=1#section。"))
        assertNull(ClipboardLinks.extract("没有链接"))
        assertNull(ClipboardLinks.extract("https://b23.tv/one https://b23.tv/two"))
        assertNull(ClipboardLinks.extract("https://user:password@b23.tv/private"))
        assertNull(ClipboardLinks.extract("https://b23.tv:8080/private"))
        assertNull(ClipboardLinks.extract("a".repeat(4097)))
    }
    @Test fun skipsSensitiveOversizeMultipleAndContentUriClips() {
        val sensitive = ClipData.newPlainText("", "https://b23.tv/ClipboardSecret")
        sensitive.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        assertNull(ClipboardLinks.fromClip(sensitive))
        assertNull(ClipboardLinks.fromClip(ClipData.newPlainText("", "https://bank.example/reset?token=private")))
        assertNull(ClipboardLinks.fromClip(ClipData.newPlainText("", "a".repeat(4097))))
        val multiple = ClipData.newPlainText("", "https://b23.tv/one").apply { addItem(ClipData.Item("https://b23.tv/two")) }
        assertNull(ClipboardLinks.fromClip(multiple))
        val uri = ClipData("uri", arrayOf("text/uri-list"), ClipData.Item(android.net.Uri.parse("content://private.example/secret")))
        assertNull(ClipboardLinks.fromClip(uri))
    }
    @Test fun fingerprintDoesNotContainRawClipboard() {
        val candidate = ClipboardLinks.fromClip(ClipData.newPlainText("", "私人笔记 https://b23.tv/Clipboard123"))!!
        assertEquals("https://b23.tv/Clipboard123", candidate.url)
        assertEquals(64, candidate.fingerprint.length)
        assertFalse(candidate.toString().contains("Clipboard123"))
    }
}