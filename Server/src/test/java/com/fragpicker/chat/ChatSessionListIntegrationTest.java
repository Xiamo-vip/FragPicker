package com.fragpicker.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatSessionListIntegrationTest {
    private static final String SCHEMA = "fragpicker_session_list_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create session list test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }

    @Test void emptyAndBoundedPagesPreserveUtcTitlesAndTieOrderWithoutPrivateColumns() {
        var owner = login(); var empty = get(owner, ""); assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(empty.getBody().path("items")).isEmpty(); assertThat(empty.getBody().path("nextCursor").isNull()).isTrue();
        seed(owner, 63, "😀".repeat(100)); var expected = jdbc.queryForList("SELECT id FROM chat_sessions WHERE user_id = ? ORDER BY updated_at DESC, id DESC", Long.class, owner.id());
        var initial = get(owner, ""); assertThat(initial.getBody().path("items").size()).isEqualTo(20);
        var first = get(owner, "?limit=50"); assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store"); assertThat(first.getBody().path("items").size()).isEqualTo(50);
        var seen = ids(first.getBody()); var last = get(owner, "?limit=50&cursor=" + first.getBody().path("nextCursor").asText()); seen.addAll(ids(last.getBody()));
        assertThat(seen).containsExactlyElementsOf(expected); assertThat(last.getBody().path("items").size()).isEqualTo(13); assertThat(last.getBody().path("nextCursor").isNull()).isTrue();
        var item = first.getBody().path("items").get(0); assertThat(item.path("title").asText()).isEqualTo("😀".repeat(100)); assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(Instant.parse(item.path("updatedAt").asText())).isEqualTo(Instant.parse("2026-01-03T00:00:00.123Z")); assertThat(first.getBody().toString()).doesNotContain("userId", "idempotencyKey", "lease", "answer", "apiKey");
    }
    @Test void cursorSurvivesBoundaryDeletionAndRecentActivityChangesFirstPage() {
        var owner = login(); seed(owner, 3, "活动"); var expected = jdbc.queryForList("SELECT id FROM chat_sessions WHERE user_id = ? ORDER BY updated_at DESC, id DESC", Long.class, owner.id());
        var first = get(owner, "?limit=1"); String cursor = first.getBody().path("nextCursor").asText(); jdbc.update("DELETE FROM chat_sessions WHERE id = ?", expected.getFirst());
        var next = get(owner, "?limit=1&cursor=" + cursor); assertThat(ids(next.getBody())).containsExactly(expected.get(1));
        jdbc.update("UPDATE chat_sessions SET updated_at = '2026-01-04 00:00:00.000' WHERE id = ?", expected.getLast());
        assertThat(ids(get(owner, "?limit=1").getBody())).containsExactly(expected.getLast());
        // Across pages activity can move a record above the cursor; refreshing from page one shows it.
        assertThat(ids(get(owner, "?limit=50&cursor=" + cursor).getBody())).containsExactly(expected.get(1));
    }
    @Test void rejectsMalformedAndNonCanonicalCursorsAndLimitsWithoutDatabaseWrites() {
        var owner = login(); seed(owner, 1, "边界");
        for (String limit : List.of("0", "51", "100", "-1", "x", "1.5")) assertInvalid(get(owner, "?limit=" + limit));
        for (String cursor : List.of("", "x", "!!!!", "a".repeat(97), encoded("2026-01-01T00:00:00.123456Z|1"), encoded("2026-01-01T00:00:00Z|0"), encoded("2026-01-01T00:00:00Z|9223372036854775808"), encoded("2026-01-01T00:00:00Z|01"), encoded("0999-01-01T00:00:00Z|1"), encoded("2026-01-01T01:00:00+01:00|1"))) assertInvalid(get(owner, "?cursor=" + cursor));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?", Integer.class, owner.id())).isEqualTo(1);
    }
    @Test void filtersOwnerEvenWithForeignCursorOrInjectedUserIdAndRejectsRevokedLogin() {
        var owner = login(); var foreign = login(); seed(owner, 3, "本人"); seed(foreign, 3, "秘密");
        assertThat(http.getForEntity("/api/v1/chat/sessions", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String cursor = get(foreign, "?limit=1").getBody().path("nextCursor").asText();
        var result = get(owner, "?cursor=" + cursor + "&userId=" + foreign.id()); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(result.getBody().path("items")).isNotEmpty();
        for (var item : result.getBody().path("items")) { assertThat(item.path("title").asText()).isEqualTo("本人"); assertThat(jdbc.queryForObject("SELECT user_id FROM chat_sessions WHERE id = ?", Long.class, item.path("sessionId").asLong())).isEqualTo(owner.id()); }
        var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); assertThat(get(owner, "").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    private void assertInvalid(ResponseEntity<JsonNode> response) { assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_CHAT_PAGE"); }
    private String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private List<Long> ids(JsonNode page) { var ids = new ArrayList<Long>(); page.path("items").forEach(item -> ids.add(item.path("sessionId").asLong())); return ids; }
    private void seed(Account owner, int count, String title) {
        for (int i = 0; i < count; i++) jdbc.update("INSERT INTO chat_sessions (user_id, idempotency_key, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)", owner.id(), UUID.randomUUID().toString(), title, LocalDateTime.parse("2026-01-01T00:00:00"), LocalDateTime.parse(i % 2 == 0 ? "2026-01-03T00:00:00.123" : "2026-01-02T00:00:00"));
    }
    private ResponseEntity<JsonNode> get(Account owner, String query) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return http.exchange("/api/v1/chat/sessions" + query, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class); }
    private Account login() { var credentials = Map.of("username", "list_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
