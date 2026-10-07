package com.fragpicker.android

import androidx.test.platform.app.InstrumentationRegistry
import com.fragpicker.android.core.auth.*
import com.fragpicker.android.core.network.JsonApi
import com.fragpicker.android.feature.chat.ChatStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChatTransportIntegrationTest {
    @org.junit.After fun cleanup() = cleanupRealSession()
    @Test fun unknownPostResultReconnectsWithSameKeyToStoredFailureWithoutAnotherTurn() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBackend") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FragPickerApplication
        val username = "transport_" + UUID.randomUUID().toString().take(8)
        val password = "Integration_" + UUID.randomUUID().toString().take(12)
        app.authRepository.register(username, password)
        val owner = app.authRepository.login(username, password)
        val api = JsonApi(app.authRepository, owner.id)
        val session = api.request("POST", "/api/v1/chat/sessions", JSONObject().put("title", "连接恢复测试"), UUID.randomUUID().toString()).getLong("sessionId")
        val stream = ChatStream(api); val key = UUID.randomUUID().toString()
        try { withTimeout(20_000) { stream.send(session, key, "寻找我保存的导数资料").toList() }; fail("Disabled provider accepted") }
        catch (failure: AuthApiFailure) { assertEquals(503, failure.status) }
        val restored = withTimeout(20_000) { stream.send(session, key, "寻找我保存的导数资料").toList() }
        assertEquals(listOf("accepted", "failed"), restored.map { it.name })
        assertTrue(restored.first().data.getBoolean("replayed"))
        assertEquals("FAILED", restored.last().data.getString("state"))
        assertEquals("CHAT_DISABLED", restored.last().data.getString("errorCode"))
        val history = api.request("GET", "/api/v1/chat/sessions/$session/messages?limit=10")
        assertEquals(1, history.getJSONArray("items").length())
        assertEquals(history.getJSONArray("items").getJSONObject(0).getLong("turnId"), restored.last().data.getLong("turnId"))
    }
}
