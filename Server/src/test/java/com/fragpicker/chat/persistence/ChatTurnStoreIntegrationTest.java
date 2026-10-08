package com.fragpicker.chat.persistence;

import com.fragpicker.chat.*;
import com.fragpicker.chat.turn.*;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.search.SearchResponse;
import com.fragpicker.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatTurnStoreIntegrationTest {
    private static final String SCHEMA = "fragpicker_turn_store_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired ChatTurnStore store;
    @Autowired ChatSessionService sessions;
    @Autowired UserAccountMapper users;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create chat persistence test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void persistsCanonicalQuestionReplaysWithoutReclaimingAndSerializesActiveTurns() {
        long owner = owner(), session = session(owner); String key = UUID.randomUUID().toString(); var started = store.begin(owner, session, key.toUpperCase(Locale.ROOT), "  导数资料  "); var ticket = started.turn();
        assertThat(started.fresh()).isTrue(); assertThat(ticket.question()).isEqualTo("导数资料"); assertThat(ticket.state()).isEqualTo("RUNNING"); assertThat(ticket.leaseExpiresAt()).isAfter(ticket.createdAt());
        var replay = store.begin(owner, session, key, "导数资料"); assertThat(replay.fresh()).isFalse(); assertThat(replay.turn().id()).isEqualTo(ticket.id()); assertThat(replay.turn().leaseToken()).isEqualTo(ticket.leaseToken());
        code(() -> store.begin(owner, session, key, "其他问题"), "IDEMPOTENCY_CONFLICT"); code(() -> store.begin(owner, session, UUID.randomUUID().toString(), "下一轮"), "CHAT_SESSION_BUSY");
        assertThat(store.fail(ticket, "CHAT_CANCELLED")).isTrue(); assertThat(store.fail(ticket, "CHAT_CANCELLED")).isFalse();
        assertThat(store.begin(owner, session, key, "导数资料").turn().state()).isEqualTo("FAILED"); assertThat(store.begin(owner, session, UUID.randomUUID().toString(), "下一轮").fresh()).isTrue();
    }
    @Test void completesAnswerAndOwnedCardAtomicallyAndRoundTripsWithoutLeaseSecrets() {
        long owner = owner(), session = session(owner); var legacy = card(owner);
        var card = new SearchResponse.Hit(legacy.fragmentId(), legacy.title(), legacy.author(), legacy.businessDate(), legacy.summary(), legacy.categories(),
                legacy.videoMediaPath(), legacy.coverMediaPath(), legacy.score(), legacy.semanticScore(), legacy.literalMatch(), legacy.match(), "从切线理解变化率。");
        var ticket = store.begin(owner, session, UUID.randomUUID().toString(), "查找导数").turn();
        assertThat(store.complete(ticket, result("已找到 [资料" + card.fragmentId() + "]", List.of(card)))).isTrue();
        assertThat(store.complete(ticket, result("晚到重复答案", List.of()))).isFalse(); var saved = store.get(owner, session, ticket.id()); assertThat(saved.state()).isEqualTo("COMPLETED");
        assertThat(saved.cards()).containsExactly(card); assertThat(saved.completedAt()).isBetween(Instant.now().minusSeconds(30), Instant.now()); assertThat(saved.modelRounds()).isEqualTo(2); assertThat(saved.toString()).doesNotContain("导数", ticket.leaseToken());
        long foreign = owner(); code(() -> store.get(foreign, session, ticket.id()), "CHAT_SESSION_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_turn_sources WHERE turn_id = ?", Integer.class, ticket.id())).isEqualTo(1);
        jdbc.update("UPDATE chat_turn_sources SET snapshot = JSON_REMOVE(snapshot, '$.introduction') WHERE turn_id = ?", ticket.id());
        assertThat(store.get(owner, session, ticket.id()).cards()).containsExactly(legacy);
    }
    @Test void rollsBackEarlierSourceWritesWhenLaterSourceIsForeignAndRejectsForgedMediaPaths() {
        long owner = owner(), foreign = owner(), session = session(owner); var own = card(owner); var secret = card(foreign); var ticket = store.begin(owner, session, UUID.randomUUID().toString(), "问题").turn();
        code(() -> store.complete(ticket, result("答案", List.of(own, secret))), "CHAT_SOURCE_UNAVAILABLE"); assertThat(store.get(owner, session, ticket.id()).state()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_turn_sources WHERE turn_id = ?", Integer.class, ticket.id())).isZero();
        var forged = new SearchResponse.Hit(own.fragmentId(), own.title(), own.author(), own.businessDate(), own.summary(), own.categories(), "https://foreign.example/private", null, .5, .5, false, own.match());
        code(() -> store.complete(ticket, result("答案", List.of(forged))), "CHAT_OUTPUT_INVALID");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chat_turn_sources (turn_id, user_id, fragment_id, ordinal, snapshot) VALUES (?, ?, ?, 0, JSON_OBJECT())", ticket.id(), owner, secret.fragmentId())).isInstanceOf(DataAccessException.class);
    }
    @Test void expiresInterruptedTurnsOnReadAndNewRequestAndFencesLateResults() {
        long owner = owner(), session = session(owner); String key = UUID.randomUUID().toString(); var old = store.begin(owner, session, key, "旧问题").turn(); expire(old);
        var interrupted = store.get(owner, session, old.id()); assertThat(interrupted.state()).isEqualTo("FAILED"); assertThat(interrupted.errorCode()).isEqualTo("CHAT_INTERRUPTED"); assertThat(interrupted.answer()).isNull();
        assertThat(store.begin(owner, session, key, "旧问题").fresh()).isFalse(); var next = store.begin(owner, session, UUID.randomUUID().toString(), "新问题").turn();
        assertThat(store.complete(old, result("旧答案", List.of()))).isFalse(); assertThat(store.fail(old, "CHAT_CANCELLED")).isFalse(); assertThat(store.complete(next, result("新答案", List.of()))).isTrue();
        var third = store.begin(owner, session, UUID.randomUUID().toString(), "再一个问题").turn(); expire(third); assertThat(store.begin(owner, session, UUID.randomUUID().toString(), "中断后继续").fresh()).isTrue();
    }
    @Test void excludesFailedTurnsAndLoadsOnlyRecentCompletedOwnedContextInOrder() {
        long owner = owner(), session = session(owner);
        for (int i = 0; i < 12; i++) { var turn = store.begin(owner, session, UUID.randomUUID().toString(), "问题" + i).turn(); assertThat(store.complete(turn, result("答案" + i, List.of()))).isTrue(); }
        var failed = store.begin(owner, session, UUID.randomUUID().toString(), "失败问题").turn(); store.fail(failed, "CHAT_PROVIDER_UNAVAILABLE");
        var next = store.begin(owner, session, UUID.randomUUID().toString(), "当前问题").turn(); var context = store.context(next);
        assertThat(context).hasSize(9); assertThat(context.getFirst().question()).isEqualTo("问题3"); assertThat(context.getLast().question()).isEqualTo("问题11"); assertThat(context).noneMatch(item -> item.question().equals("失败问题"));
    }
    @Test void concurrentReplaysReturnOneTicketAndLogoutPreventsCompletion() throws Exception {
        long owner = owner(), session = session(owner); String key = UUID.randomUUID().toString(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return store.begin(owner, session, key, "问题"); }); var b = executor.submit(() -> { start.await(); return store.begin(owner, session, key, "问题"); }); start.countDown();
            var first = a.get(10, TimeUnit.SECONDS); var second = b.get(10, TimeUnit.SECONDS); assertThat(first.turn().id()).isEqualTo(second.turn().id()); assertThat(first.fresh()).isNotEqualTo(second.fresh());
            jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", owner); code(() -> store.complete(first.turn(), result("注销后晚到答案", List.of())), "SESSION_INVALID");
            store.fail(first.turn(), "CHAT_CANCELLED"); assertThat(store.get(owner, session, first.turn().id()).answer()).isNull();
            jdbc.update("DELETE FROM users WHERE id = ?", owner); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE session_id = ?", Integer.class, session)).isZero();
        }
    }
    @Test void rejectsIncompleteCompletedStateAndSnapshotIdsInconsistentWithOwnedForeignKeys() {
        long owner = owner(), session = session(owner); var card = card(owner); var ticket = store.begin(owner, session, UUID.randomUUID().toString(), "问题").turn();
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_turns SET state = 'COMPLETED', answer = '无完整元数据', completed_at = UTC_TIMESTAMP(3), context_truncated = FALSE WHERE id = ?", ticket.id())).isInstanceOf(DataAccessException.class);
        assertThat(store.complete(ticket, result("答案", List.of(card)))).isTrue();
        jdbc.update("UPDATE chat_turn_sources SET snapshot = JSON_SET(snapshot, '$.fragmentId', 9223372036854775807) WHERE turn_id = ?", ticket.id());
        code(() -> store.get(owner, session, ticket.id()), "CHAT_STORAGE_INVALID");
    }
    private void expire(StoredChatTurn turn) { jdbc.update("UPDATE chat_turns SET created_at = UTC_TIMESTAMP(3) - INTERVAL 2 MINUTE, lease_expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE id = ?", turn.id()); }
    private long owner() { var user = new UserAccount(); String name = "store_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId()); return user.getId(); }
    private long session(long owner) { return sessions.create(owner, UUID.randomUUID().toString(), new CreateSessionRequest("测试对话")).sessionId(); }
    private SearchResponse.Hit card(long owner) { var item = submissions.submit(owner, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); jdbc.update("UPDATE fragments SET status = 'READY' WHERE id = ?", item.fragmentId()); return new SearchResponse.Hit(item.fragmentId(), "导数课程", "老师", LocalDate.of(2026,10,6), "导数与变化率", List.of(), "/api/v1/fragments/" + item.fragmentId() + "/media?kind=VIDEO", null, .5, .5, false, new SearchResponse.Match(0, "TRANSCRIPT", 0, 0L, 2000L, "导数是瞬时变化率")); }
    private ChatTurnResult result(String text, List<SearchResponse.Hit> cards) { return new ChatTurnResult(text, cards, false, 2, 1); }
    private void code(Runnable operation, String code) { assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code)); }
}
