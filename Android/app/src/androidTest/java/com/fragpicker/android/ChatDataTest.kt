package com.fragpicker.android

import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.feature.chat.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.util.UUID

class ChatDataTest {
    @Test fun decoderHandlesCommentsCrLfMultilineUnicodeAndRejectsIncompleteEvents() = runBlocking {
        val events = mutableListOf<ChatEvent>()
        ChatEventDecoder.read(StringReader(": heartbeat\r\nevent: delta\r\ndata: {\"round\":1,\r\ndata: \"text\":\"导数🙂\"}\r\n\r\nevent: done\ndata: {}\n\n")) { events.add(it) }
        assertEquals(listOf("delta", "done"), events.map { it.name }); assertEquals("导数🙂", events.first().data.getString("text"))
        try { ChatEventDecoder.read(StringReader("event: delta\ndata: {}\n")) {}; fail("Truncated event accepted") } catch (_: IOException) { }
        try { ChatEventDecoder.read(StringReader("data: " + "x".repeat(1_048_577))) {}; fail("Oversized line accepted") } catch (_: IOException) { }
    }
    @Test fun pendingRequestsAreEncryptedAccountBoundAndKeepExactKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = "https://fixture-${UUID.randomUUID()}.invalid"
        val vault = PendingMessageVault(context, base, 1)
        val message = PendingMessage("帮我查找导数学习资料🙂", UUID.randomUUID().toString(), UUID.randomUUID().toString(), 123, 456)
        try {
            vault.save(message); assertEquals(message, vault.load())
            val sealed = context.getSharedPreferences("chat_pending", android.content.Context.MODE_PRIVATE).getString("v1:$base:1", null)!!
            assertFalse(sealed.contains(message.question)); assertFalse(sealed.contains(message.key))
            assertNull(PendingMessageVault(context, base, 2).load())
            assertNull(PendingMessageVault(context, "$base/other", 1).load())
            vault.save(message.copy(turnId = 789)); assertEquals(789L, vault.load()!!.turnId)
        } finally { vault.clear() }
        assertNull(vault.load())
    }
}
