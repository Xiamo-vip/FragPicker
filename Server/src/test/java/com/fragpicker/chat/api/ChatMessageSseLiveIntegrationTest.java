package com.fragpicker.chat.api;
import com.fasterxml.jackson.databind.*;
import com.fragpicker.chat.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.knowledge.index.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CHAT_API_TEST_ENABLED", matches = "true")
class ChatMessageSseLiveIntegrationTest {
    private static final String SCHEMA = "fragpicker_sse_live_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired ChatSessionService sessions;
    @Autowired SubmissionService submissions; @Autowired KnowledgeResultMapper knowledge; @Autowired IndexStore indexes; @Autowired @Lazy LocalEmbeddingService embeddings; @LocalServerPort int port;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create live SSE test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.chat.enabled", () -> true); registry.add("fragpicker.integrations.chat.timeout", () -> "90s");
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void actualHttpLoginDeepSeekToolsMySqlVectorsStreamingAndTwoTurnPersistenceWorkTogether() throws Exception {
        var owner = login(); var foreign = login(); var math = pending(owner, "导数与瞬时变化率课程"); pending(foreign, "其他人的秘密数学课程"); var worker = new IndexWorker(indexes, embeddings); assertThat(worker.runOnce()).isTrue(); assertThat(worker.runOnce()).isTrue();
        long session = sessions.create(owner.id(), UUID.randomUUID().toString(), new CreateSessionRequest("数学学习")).sessionId(); String key = UUID.randomUUID().toString();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            var first = send(client, owner, session, key, "请帮我找一下我之前想要学习的数学资源有关导数的。"); var result = first.getLast().data(); verify(result, owner, math.fragmentId()); assertThat(result.path("answer").asText()).contains("导数").doesNotContain("其他人的秘密数学课程");
            assertThat(first.stream().filter(event -> event.name().equals("delta")).count()).isPositive(); assertThat(first.stream().filter(event -> event.name().equals("done")).count()).isEqualTo(1);
            long turn = result.path("turnId").asLong(); assertThat(jdbc.queryForObject("SELECT answer FROM chat_turns WHERE id = ?", String.class, turn)).isEqualTo(result.path("answer").asText());
            var replay = send(client, owner, session, key, "请帮我找一下我之前想要学习的数学资源有关导数的。"); assertThat(replay).extracting(Event::name).containsExactly("accepted", "done"); assertThat(replay.getLast().data()).isEqualTo(result);
            var second = send(client, owner, session, UUID.randomUUID().toString(), "请再次检索并用一句话概括刚才那份导数资料，保留资料编号。"); verify(second.getLast().data(), owner, math.fragmentId());
            assertThat(second.getLast().data().path("answer").asText()).contains("导数", Long.toString(math.fragmentId())); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE session_id = ? AND state = 'COMPLETED'", Integer.class, session)).isEqualTo(2);
        }
        System.out.println("Real chat SSE API: HTTP login, two DeepSeek tool turns, owned MySQL/ONNX, streamed deltas, committed source cards and replay succeeded.");
    }
    private void verify(JsonNode result, Account owner, long fragment) { assertThat(result.path("state").asText()).isEqualTo("COMPLETED"); assertThat(result.path("toolCalls").asInt()).isBetween(1,3); assertThat(result.path("cards").findValues("fragmentId")).extracting(JsonNode::asLong).contains(fragment); for (var card : result.path("cards")) { long id = card.path("fragmentId").asLong(); assertThat(jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, id)).isEqualTo(owner.id()); assertThat(card.path("videoMediaPath").asText()).isEqualTo("/api/v1/fragments/" + id + "/media?kind=VIDEO"); } }
    private List<Event> send(HttpClient client, Account owner, long session, String key, String text) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/sessions/" + session + "/messages")).timeout(Duration.ofMinutes(4)).header("Authorization", "Bearer " + owner.token()).header("Content-Type", "application/json").header("Accept", "text/event-stream").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("message", text)))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream()); assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        var events = new ArrayList<Event>(); try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) { String name = null; var data = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) { if (line.startsWith("event:")) name = line.substring(6).strip(); else if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()); else if (line.isEmpty() && name != null) { events.add(new Event(name, json.readTree(data.toString()))); name = null; data.setLength(0); } }
        }
        assertThat(events.getLast().name()).isEqualTo("done"); return events;
    }
    private SubmissionResponse pending(Account owner, String title) {
        String summary = "数学学习：导数是函数瞬时变化率，用极限和切线斜率理解导数，应用求导公式判断函数单调性。";
        var item = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); knowledge.insert(item.fragmentId(), owner.id(), 2000, summary, "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY(?), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'test-model', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, summary, item.fragmentId()); jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id, user_id, title, video_url, parsed_at) VALUES (?, ?, ?, 'https://example.com/test-only.mp4', UTC_TIMESTAMP(3))", item.fragmentId(), owner.id(), title);
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at) VALUES (?, ?, ?, 'test-private', ?, 10, REPEAT('a',64), ?, UTC_TIMESTAMP(3))", item.fragmentId(), owner.id(), kind, "test-fixture/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg"); return item;
    }
    private Account login() { var credentials = Map.of("username", "sse_live_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { }
    private record Event(String name, JsonNode data) { }
}
