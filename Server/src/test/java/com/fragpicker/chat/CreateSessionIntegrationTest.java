package com.fragpicker.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class CreateSessionIntegrationTest {
    private static final String SCHEMA = "fragpicker_session_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create chat session test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void createsDurableOwnedSessionWithDefaultTitleUtcTimeAndNoCloudConfiguration() {
        var owner = login(); var response = create(owner, UUID.randomUUID().toString(), Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var body = response.getBody(); long id = body.path("sessionId").asLong(); assertThat(id).isPositive(); assertThat(body.path("title").asText()).isEqualTo("新对话");
        assertThat(body.path("replayed").asBoolean()).isFalse(); assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/v1/chat/sessions/" + id);
        assertThat(Instant.parse(body.path("createdAt").asText())).isBetween(Instant.now().minusSeconds(30), Instant.now()); assertThat(body.path("updatedAt")).isEqualTo(body.path("createdAt"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM chat_sessions WHERE id = ?", Long.class, id)).isEqualTo(owner.id());
        assertThat(body.toString()).doesNotContain("userId", "idempotencyKey", "apiKey");
        jdbc.update("DELETE FROM users WHERE id = ?", owner.id()); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE id = ?", Integer.class, id)).isZero();
    }
    @Test void replaysCanonicalRequestAndRejectsDifferentTitleWithoutCreatingAnotherSession() {
        var owner = login(); String key = UUID.randomUUID().toString(); var first = create(owner, key.toUpperCase(Locale.ROOT), Map.of("title", "  数学学习  "));
        var repeated = create(owner, key, Map.of("title", "数学学习")); assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(repeated.getBody().path("replayed").asBoolean()).isTrue();
        for (String field : List.of("sessionId", "title", "createdAt", "updatedAt")) assertThat(repeated.getBody().path(field)).isEqualTo(first.getBody().path(field));
        var conflict = create(owner, key, Map.of("title", "做饭")); assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); assertThat(conflict.getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?", Integer.class, owner.id())).isEqualTo(1);
    }
    @Test void boundsUnicodeTitlesAndRejectsInvalidKeysWithoutSideEffects() {
        var owner = login(); assertThat(create(owner, UUID.randomUUID().toString(), Map.of("title", "😀".repeat(100))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (String title : List.of("😀".repeat(101), "标题\n控制", "\uD800", "a".repeat(201))) {
            var invalid = create(owner, UUID.randomUUID().toString(), Map.of("title", title)); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_CHAT_TITLE");
        }
        for (String key : List.of("", "not-uuid", "1-1-1-1-1")) {
            var invalid = create(owner, key, Map.of()); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        }
        assertThat(create(owner, null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?", Integer.class, owner.id())).isEqualTo(1);
    }
    @Test void requiresLoginAndSeparatesOwnersDespiteSameKeyAndInjectedUserId() {
        var owner = login(); var foreign = login(); String key = UUID.randomUUID().toString();
        assertThat(http.postForEntity("/api/v1/chat/sessions", Map.of(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var first = create(owner, key, Map.of("title", "本人对话")); var second = create(foreign, key, Map.of("title", "另一对话", "userId", owner.id()));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED); assertThat(second.getBody().path("sessionId")).isNotEqualTo(first.getBody().path("sessionId"));
        assertThat(jdbc.queryForObject("SELECT user_id FROM chat_sessions WHERE id = ?", Long.class, second.getBody().path("sessionId").asLong())).isEqualTo(foreign.id());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_sessions (user_id, idempotency_key, title, created_at, updated_at) VALUES (?, ?, '重复', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", owner.id(), key)).isInstanceOf(DataAccessException.class);
    }
    @Test void concurrentDuplicateHttpRequestsCreateExactlyOneDurableSession() throws Exception {
        var owner = login(); String key = UUID.randomUUID().toString(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return create(owner, key, Map.of("title", "并发对话")); });
            var second = executor.submit(() -> { start.await(); return create(owner, key, Map.of("title", "并发对话")); }); start.countDown();
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatusCode(), b.getStatusCode())).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
            assertThat(a.getBody().path("sessionId")).isEqualTo(b.getBody().path("sessionId"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?", Integer.class, owner.id())).isEqualTo(1);
        }
    }
    private ResponseEntity<JsonNode> create(Account owner, String key, Object input) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); if (key != null) headers.set("Idempotency-Key", key); return http.exchange("/api/v1/chat/sessions", HttpMethod.POST, new HttpEntity<>(input, headers), JsonNode.class); }
    private Account login() {
        var credentials = Map.of("username", "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText());
    }
    private record Account(long id, String token) { }
}
