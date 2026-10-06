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
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatTurnListIntegrationTest {
    private static final String SCHEMA = "fragpicker_turn_list_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired ChatSessionService sessions; @Autowired ChatTurnStore turns; @Autowired SubmissionService submissions; @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create chat page test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }

    @Test void loadsAllHistoryBeyondModelContextWithSourcesAndIntactLargeUnicodeAnswer() {
        var owner = login(); long session = session(owner); var empty = get(owner, session, ""); assertThat(empty.getBody().path("items")).isEmpty(); assertThat(empty.getBody().path("nextBefore").isNull()).isTrue();
        var ids = new ArrayList<Long>(); var card = card(owner);
        for (int i = 0; i < 23; i++) { var turn = begin(owner, session, "问题" + i); ids.add(turn.id()); assertThat(turns.complete(turn, new ChatTurnResult(i == 22 ? "😀".repeat(12000) : "回答" + i, List.of(card), false, 1, 1))).isTrue(); }
        assertThat(get(owner, session, "").getBody().path("items").size()).isEqualTo(10);
        var first = get(owner, session, "?limit=20"); assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store"); assertThat(first.getBody().path("items").size()).isEqualTo(20);
        var seen = ids(first.getBody()); var next = get(owner, session, "?limit=20&before=" + first.getBody().path("nextBefore").asLong()); seen.addAll(ids(next.getBody())); assertThat(seen).containsExactlyElementsOf(ids.reversed()); assertThat(next.getBody().path("items").size()).isEqualTo(3); assertThat(next.getBody().path("nextBefore").isNull()).isTrue();
        var item = first.getBody().path("items").get(0); assertThat(item.path("answer").asText()).isEqualTo("😀".repeat(12000)); assertThat(item.path("cards").get(0).path("match").path("endMs").asLong()).isEqualTo(2000); assertThat(item.path("cards").get(0).path("fragmentId").asLong()).isEqualTo(card.fragmentId());
        assertThat(first.getBody().toString()).doesNotContain("leaseToken", "authVersion", "userId", "idempotencyKey", "reasoning", "objectKey");
    }
    @Test void persistsExpiredStateAndPreservesRunningAndFailedWithoutDraftAnswers() {
        var owner = login(); long session = session(owner); var failed = begin(owner, session, "失败"); assertThat(turns.fail(failed, "CHAT_PROVIDER_UNAVAILABLE")).isTrue(); var expired = begin(owner, session, "中断");
        jdbc.update("UPDATE chat_turns SET created_at = UTC_TIMESTAMP(3) - INTERVAL 2 MINUTE, lease_expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE id = ?", expired.id());
        var page = get(owner, session, "").getBody(); assertThat(page.path("items").get(0).path("errorCode").asText()).isEqualTo("CHAT_INTERRUPTED"); assertThat(jdbc.queryForObject("SELECT state FROM chat_turns WHERE id = ?", String.class, expired.id())).isEqualTo("FAILED");
        var running = begin(owner, session, "运行"); var latest = get(owner, session, "").getBody(); assertThat(ids(latest)).containsExactly(running.id(), expired.id(), failed.id()); assertThat(latest.path("items").get(0).path("state").asText()).isEqualTo("RUNNING");
        for (var item : latest.path("items")) { assertThat(item.path("answer").isNull()).isTrue(); assertThat(item.path("cards")).isEmpty(); }
    }
    @Test void stableIdCursorSurvivesDeletionAndDoesNotIncludeNewerTurnsDuringPaging() {
        var owner = login(); long session = session(owner); var oldest = begin(owner, session, "最早"); turns.fail(oldest, "CHAT_CANCELLED"); var middle = begin(owner, session, "中间"); turns.fail(middle, "CHAT_CANCELLED"); var newest = begin(owner, session, "最近"); turns.fail(newest, "CHAT_CANCELLED");
        var first = get(owner, session, "?limit=1").getBody(); assertThat(ids(first)).containsExactly(newest.id()); long before = first.path("nextBefore").asLong(); jdbc.update("DELETE FROM chat_turns WHERE id = ?", newest.id()); var arrived = begin(owner, session, "后来");
        assertThat(ids(get(owner, session, "?before=" + before).getBody())).containsExactly(middle.id(), oldest.id()); assertThat(ids(get(owner, session, "?limit=1").getBody())).containsExactly(arrived.id()); assertThat(get(owner, session, "?before=1").getBody().path("items")).isEmpty();
    }
    @Test void rejectsBadPagesAndForeignParentsEvenWithInjectedOwnerOrForeignMessageCursor() {
        var owner = login(); var foreign = login(); long session = session(owner), other = session(foreign); begin(owner, session, "私人");
        assertThat(http.getForEntity(path(session), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var hidden = get(foreign, session, "?userId=" + owner.id()); assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(hidden.getBody().path("code")).isEqualTo(get(foreign, Long.MAX_VALUE, "").getBody().path("code"));
        assertThat(get(foreign, other, "?before=9223372036854775807").getBody().path("items")).isEmpty();
        for (String query : List.of("?limit=0", "?limit=21", "?limit=x", "?limit=-1", "?before=0", "?before=-1", "?before=x", "?before=9223372036854775808", "?before=")) { var invalid = get(owner, session, query); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_CHAT_PAGE"); }
        assertThat(http.exchange("/api/v1/chat/sessions/bad/messages", HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class).getBody().path("code").asText()).isEqualTo("INVALID_CHAT_ID");
        assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers(owner)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); assertThat(get(owner, session, "").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void corruptSourceFailsThePageInsteadOfReturningUnverifiedCard() {
        var owner = login(); long session = session(owner); var turn = begin(owner, session, "来源"); var card = card(owner); turns.complete(turn, new ChatTurnResult("已找到", List.of(card), false, 1, 1)); jdbc.update("UPDATE chat_turn_sources SET snapshot = JSON_SET(snapshot, '$.fragmentId', 999999) WHERE turn_id = ?", turn.id());
        var response = get(owner, session, ""); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(response.getBody().path("code").asText()).isEqualTo("CHAT_STORAGE_INVALID"); assertThat(response.getBody().toString()).doesNotContain("999999", "来源");
    }
    private SearchResponse.Hit card(Account owner) { var fragment = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); jdbc.update("UPDATE fragments SET status = 'READY' WHERE id = ?", fragment.fragmentId()); return new SearchResponse.Hit(fragment.fragmentId(), "导数课程", "老师", LocalDate.of(2026,10,6), "变化率", List.of(), "/api/v1/fragments/" + fragment.fragmentId() + "/media?kind=VIDEO", null, .5, .5, false, new SearchResponse.Match(0, "TRANSCRIPT", 0, 0L, 2000L, "原文")); }
    private List<Long> ids(JsonNode page) { var ids = new ArrayList<Long>(); page.path("items").forEach(item -> ids.add(item.path("turnId").asLong())); return ids; }
    private StoredChatTurn begin(Account owner, long session, String question) { return turns.begin(owner.id(), session, UUID.randomUUID().toString(), question).turn(); }
    private long session(Account owner) { return sessions.create(owner.id(), UUID.randomUUID().toString(), new CreateSessionRequest(null)).sessionId(); }
    private String path(long session) { return "/api/v1/chat/sessions/" + session + "/messages"; }
    private ResponseEntity<JsonNode> get(Account owner, long session, String query) { return http.exchange(path(session) + query, HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); }
    private HttpHeaders headers(Account owner) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private Account login() { var credentials = Map.of("username", "page_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
