package com.fragpicker.chat.api;
import com.fasterxml.jackson.databind.*;
import com.fragpicker.chat.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatMessageSseIntegrationTest {
    private static final String SCHEMA = "fragpicker_sse_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired ChatSessionService sessions; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @LocalServerPort int port;
    @MockitoBean StreamingChatModel model;
    private final List<Long> owned = new ArrayList<>(); private FakeHandle handle;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create SSE test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @BeforeEach void fixture() { handle = new FakeHandle(); }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void streamsJsonFramesThenCommitsAndReplaysWithoutCallingModelAgain() throws Exception {
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); callback.onPartialThinking(new PartialThinking("private-thinking"), new PartialThinkingContext(handle)); emit(callback, "第一行\n", "第二行"); callback.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("第一行\n第二行")).build()); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var owner = login(); long session = session(owner); String key = UUID.randomUUID().toString();
        try (var client = client()) {
            var response = client.send(request(owner, session, key, "你好"), HttpResponse.BodyHandlers.ofInputStream()); assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store"); assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            var events = read(response.body()); assertThat(events).extracting(Event::name).containsExactly("accepted", "round_start", "delta", "delta", "round_end", "done");
            var done = events.getLast().data(); long turn = done.path("turnId").asLong(); assertThat(done.path("answer").asText()).isEqualTo("第一行\n第二行"); assertThat(done.path("state").asText()).isEqualTo("COMPLETED"); assertThat(done.toString()).doesNotContain("private-thinking", "leaseToken");
            assertThat(jdbc.queryForObject("SELECT answer FROM chat_turns WHERE id = ?", String.class, turn)).isEqualTo(done.path("answer").asText());
            var replay = client.send(request(owner, session, key, "你好"), HttpResponse.BodyHandlers.ofInputStream()); var repeated = read(replay.body()); assertThat(repeated).extracting(Event::name).containsExactly("accepted", "done"); assertThat(repeated.getFirst().data().path("replayed").asBoolean()).isTrue(); assertThat(repeated.getLast().data()).isEqualTo(done);
            verify(model, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }
    @Test void supplierFailureIsPersistedAndReplayedWithoutRawBodyOrRetries() throws Exception {
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); callback.onError(new IllegalStateException("supplier-secret-body")); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var owner = login(); long session = session(owner); String key = UUID.randomUUID().toString();
        try (var client = client()) { var events = read(client.send(request(owner, session, key, "问题"), HttpResponse.BodyHandlers.ofInputStream()).body()); assertThat(events.getLast().name()).isEqualTo("error"); assertThat(events.getLast().data().path("code").asText()).isEqualTo("CHAT_PROVIDER_UNAVAILABLE");
            long turn = events.getFirst().data().path("turnId").asLong(); assertThat(jdbc.queryForObject("SELECT state FROM chat_turns WHERE id = ?", String.class, turn)).isEqualTo("FAILED");
            var repeated = read(client.send(request(owner, session, key, "问题"), HttpResponse.BodyHandlers.ofInputStream()).body()); assertThat(repeated.getLast().name()).isEqualTo("failed"); assertThat(repeated.toString()).doesNotContain("supplier-secret-body"); verify(model, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class)); }
    }
    @Test void invalidOrForeignRequestsStayJsonAndDoNotCallModel() throws Exception {
        var owner = login(); var foreign = login(); long session = session(owner);
        try (var client = client()) {
            for (var request : List.of(request(owner, session, null, "问题"), request(owner, session, UUID.randomUUID().toString(), " "), request(foreign, session, UUID.randomUUID().toString(), "问题"))) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertThat(response.statusCode()).isIn(400,404); assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json"); assertThat(response.body()).doesNotContain("lease", "私人"); }
            var unauthorized = HttpRequest.newBuilder(URI.create(address(session))).POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"问题\"}")).header("Content-Type", "application/json").build(); assertThat(client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        verifyNoInteractions(model); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE user_id = ?", Integer.class, owner.id())).isZero();
    }
    @Test void disconnectCancelsGenerationAndPendingReplayDoesNotStartAnotherModelCall() throws Exception {
        doAnswer(invocation -> { StreamingChatResponseHandler callback = invocation.getArgument(1); emit(callback, "草稿"); return null; }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var owner = login(); long session = session(owner); String key = UUID.randomUUID().toString();
        try (var client = client()) {
            var original = client.send(request(owner, session, key, "问题"), HttpResponse.BodyHandlers.ofInputStream()); var reader = new BufferedReader(new InputStreamReader(original.body(), StandardCharsets.UTF_8)); var first = next(reader); long turn = first.data().path("turnId").asLong();
            var replay = read(client.send(request(owner, session, key, "问题"), HttpResponse.BodyHandlers.ofInputStream()).body()); assertThat(replay.getLast().name()).isEqualTo("pending");
            var busy = client.send(request(owner, session, UUID.randomUUID().toString(), "另一个问题"), HttpResponse.BodyHandlers.ofString()); assertThat(busy.statusCode()).isEqualTo(409); assertThat(json.readTree(busy.body()).path("code").asText()).isEqualTo("CHAT_SESSION_BUSY");
            original.body().close(); long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos(); String state;
            do { state = jdbc.queryForObject("SELECT state FROM chat_turns WHERE id = ?", String.class, turn); if (!state.equals("RUNNING")) break; Thread.sleep(100); } while (System.nanoTime() < deadline);
            assertThat(state).isEqualTo("FAILED"); assertThat(handle.cancelled.get()).isTrue(); assertThat(jdbc.queryForObject("SELECT answer FROM chat_turns WHERE id = ?", String.class, turn)).isNull(); verify(model, times(1)).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        }
    }
    private void emit(StreamingChatResponseHandler callback, String... texts) { for (String text : texts) callback.onPartialResponse(new PartialResponse(text), new PartialResponseContext(handle)); }
    private HttpClient client() { return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build(); }
    private HttpRequest request(Account owner, long session, String key, String question) throws Exception { var request = HttpRequest.newBuilder(URI.create(address(session))).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + owner.token()).header("Content-Type", "application/json").header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message", question)))); if (key != null) request.header("Idempotency-Key", key); return request.build(); }
    private String address(long session) { return "http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + session + "/messages"; }
    private List<Event> read(InputStream body) throws Exception { try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) { var events = new ArrayList<Event>(); Event event; while ((event = next(reader)) != null) events.add(event); return events; } }
    private Event next(BufferedReader reader) throws Exception { String name = null; var data = new StringBuilder(); String line; while ((line = reader.readLine()) != null) { if (line.isEmpty() && name != null) return new Event(name, json.readTree(data.toString())); if (line.startsWith("event:")) name = line.substring(6).strip(); if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()); } return null; }
    private long session(Account owner) { return sessions.create(owner.id(), UUID.randomUUID().toString(), new CreateSessionRequest(null)).sessionId(); }
    private Account login() { var credentials = Map.of("username", "sse_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id,login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { }
    private record Event(String name, JsonNode data) { }
    private static class FakeHandle implements StreamingHandle { final AtomicBoolean cancelled = new AtomicBoolean(); @Override public void cancel() { cancelled.set(true); } @Override public boolean isCancelled() { return cancelled.get(); } }
}
