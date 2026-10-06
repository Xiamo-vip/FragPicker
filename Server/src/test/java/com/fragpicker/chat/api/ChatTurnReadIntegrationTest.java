package com.fragpicker.chat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.chat.*;
import com.fragpicker.chat.persistence.*;
import com.fragpicker.chat.turn.ChatTurnResult;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.search.SearchResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatTurnReadIntegrationTest {
    private static final String SCHEMA = "fragpicker_turn_read_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http;
    @Autowired ChatSessionService sessions;
    @Autowired ChatTurnStore turns;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create chat read test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void readsOwnedPersistedAnswerAndSourceCardsWithoutInternalLeaseOrProviderData() {
        var owner = login(); long session = session(owner); var turn = turns.begin(owner.id(), session, UUID.randomUUID().toString(), "查导数").turn();
        var fragment = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); jdbc.update("UPDATE fragments SET status = 'READY' WHERE id = ?", fragment.fragmentId());
        var card = new SearchResponse.Hit(fragment.fragmentId(), "导数课程", "老师", LocalDate.of(2026,10,6), "导数描述变化率", List.of(), "/api/v1/fragments/" + fragment.fragmentId() + "/media?kind=VIDEO", null, .5, .5, false, new SearchResponse.Match(0, "TRANSCRIPT", 0, 0L, 2000L, "原文"));
        assertThat(turns.complete(turn, new ChatTurnResult("已找到 [资料" + fragment.fragmentId() + "]", List.of(card), false, 2, 1))).isTrue(); var response = get(owner, session, turn.id()); var body = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store"); assertThat(body.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(body.path("question").asText()).isEqualTo("查导数"); assertThat(body.path("answer").asText()).contains("已找到"); assertThat(body.path("cards")).hasSize(1); assertThat(body.path("cards").get(0).path("fragmentId").asLong()).isEqualTo(fragment.fragmentId());
        assertThat(body.path("cards").get(0).path("match").path("startMs").asLong()).isZero(); assertThat(Instant.parse(body.path("completedAt").asText())).isBetween(Instant.now().minusSeconds(30), Instant.now());
        assertThat(body.toString()).doesNotContain("leaseToken", "leaseExpiresAt", "authVersion", "userId", "idempotencyKey", "reasoning", "objectKey", "apiKey");
    }
    @Test void exposesRunningAndInterruptedStatesWithoutInventingAnAnswer() {
        var owner = login(); long session = session(owner); var turn = turns.begin(owner.id(), session, UUID.randomUUID().toString(), "待完成").turn();
        var running = get(owner, session, turn.id()).getBody(); assertThat(running.path("state").asText()).isEqualTo("RUNNING"); assertThat(running.path("answer").isNull()).isTrue(); assertThat(running.path("cards")).isEmpty();
        jdbc.update("UPDATE chat_turns SET created_at = UTC_TIMESTAMP(3) - INTERVAL 2 MINUTE, lease_expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE id = ?", turn.id());
        var failed = get(owner, session, turn.id()).getBody(); assertThat(failed.path("state").asText()).isEqualTo("FAILED"); assertThat(failed.path("errorCode").asText()).isEqualTo("CHAT_INTERRUPTED"); assertThat(failed.path("answer").isNull()).isTrue();
        assertThat(get(owner, session, turn.id()).getBody().path("completedAt")).isEqualTo(failed.path("completedAt")); assertThat(jdbc.queryForObject("SELECT state FROM chat_turns WHERE id = ?", String.class, turn.id())).isEqualTo("FAILED");
    }
    @Test void requiresLoginAndHidesForeignSessionsAndMessagesDespiteInjectedOwnerId() {
        var owner = login(); var foreign = login(); long session = session(owner), otherSession = session(foreign); var turn = turns.begin(owner.id(), session, UUID.randomUUID().toString(), "私人问题").turn();
        assertThat(http.getForEntity(path(session, turn.id()), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var hidden = get(foreign, session, turn.id()); assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(hidden.getBody().path("code")).isEqualTo(get(foreign, Long.MAX_VALUE, turn.id()).getBody().path("code"));
        assertThat(get(foreign, otherSession, turn.id()).getBody().path("code").asText()).isEqualTo("CHAT_TURN_NOT_FOUND");
        assertThat(http.exchange(path(session, turn.id()) + "?userId=" + owner.id(), HttpMethod.GET, new HttpEntity<>(headers(foreign)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner, session, turn.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void rejectsMalformedIdsAndReturnsUnauthorizedAfterRealLogout() {
        var owner = login(); long session = session(owner); var turn = turns.begin(owner.id(), session, UUID.randomUUID().toString(), "问题").turn();
        for (String id : List.of("0", "-1", "not-id", "99999999999999999999")) {
            var response = http.exchange("/api/v1/chat/sessions/" + session + "/messages/" + id, HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_CHAT_ID");
        }
        assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers(owner)), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(owner, session, turn.id()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    private long session(Account owner) { return sessions.create(owner.id(), UUID.randomUUID().toString(), new CreateSessionRequest(null)).sessionId(); }
    private String path(long session, long turn) { return "/api/v1/chat/sessions/" + session + "/messages/" + turn; }
    private ResponseEntity<JsonNode> get(Account owner, long session, long turn) { return http.exchange(path(session, turn), HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); }
    private HttpHeaders headers(Account owner) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private Account login() {
        var credentials = Map.of("username", "read_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText());
    }
    private record Account(long id, String token) { }
}
