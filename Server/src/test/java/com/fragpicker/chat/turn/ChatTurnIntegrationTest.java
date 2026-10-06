package com.fragpicker.chat.turn;

import com.fragpicker.auth.CurrentUser;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.knowledge.index.*;
import com.fragpicker.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Opt-in billable streaming turns use synthetic owned MySQL content and real local ONNX vectors. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ChatTurnIntegrationTest {
    private static final String SCHEMA = "fragpicker_turn_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired ChatTurnEngine engine;
    @Autowired UserAccountMapper users;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired IndexStore indexes;
    @Autowired @Lazy LocalEmbeddingService embeddings;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create chat turn test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.chat.enabled", () -> true); registry.add("fragpicker.integrations.chat.timeout", () -> "90s"); registry.add("fragpicker.chat.turn.timeout", () -> "180s");
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test @EnabledIfEnvironmentVariable(named = "CHAT_TURN_TEST_ENABLED", matches = "true")
    void realDeepSeekStreamsTwoContextualTurnsThroughOwnedToolAndVerifiedCards() {
        long owner = owner(), foreign = owner(); var math = pending(owner, "导数与瞬时变化率课程", "数学学习：导数是函数瞬时变化率，用极限和切线斜率理解导数，并用求导公式判断函数单调性。");
        pending(foreign, "别人的秘密课程", "导数数学与瞬时变化率课程。"); var worker = new IndexWorker(indexes, embeddings); assertThat(worker.runOnce()).isTrue(); assertThat(worker.runOnce()).isTrue();
        String firstQuestion = "请帮我找一下我之前想要学习的数学资源有关导数的。";
        var firstListener = new RecordingListener(); var first = engine.stream(new CurrentUser(owner), List.of(), firstQuestion, new TurnCancellation(), firstListener);
        verifyOwned(first, owner, math.fragmentId()); assertThat(first.answer()).contains("导数").doesNotContain("别人的秘密课程"); assertThat(first.toolCalls()).isBetween(1, 3);
        assertThat(firstListener.tokens.get()).isPositive(); assertThat(firstListener.texts.getLast().toString()).isEqualTo(first.answer()); assertThat(firstListener.ends.getLast()).isFalse();
        var secondListener = new RecordingListener(); var second = engine.stream(new CurrentUser(owner), List.of(new ConversationExchange(firstQuestion, first.answer())),
                "请再次检索并用一句话概括刚才那份导数资料，保留资料编号。", new TurnCancellation(), secondListener);
        verifyOwned(second, owner, math.fragmentId()); assertThat(second.answer()).contains("导数", Long.toString(math.fragmentId())).doesNotContain("别人的秘密课程");
        assertThat(second.toolCalls()).isBetween(1, 3); assertThat(second.contextTruncated()).isFalse(); assertThat(secondListener.tokens.get()).isPositive(); assertThat(secondListener.texts.getLast().toString()).isEqualTo(second.answer());
        System.out.println("Real DeepSeek streaming turn engine: two contextual turns, owned MySQL/ONNX retrieval, genuine deltas and verified cards succeeded.");
    }
    private void verifyOwned(ChatTurnResult result, long owner, long expected) {
        assertThat(result.cards()).extracting(card -> card.fragmentId()).contains(expected);
        for (var card : result.cards()) { assertThat(jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, card.fragmentId())).isEqualTo(owner); assertThat(card.videoMediaPath()).isEqualTo("/api/v1/fragments/" + card.fragmentId() + "/media?kind=VIDEO"); assertThat(card.coverMediaPath()).isEqualTo("/api/v1/fragments/" + card.fragmentId() + "/media?kind=COVER"); }
    }
    private long owner() { var user = new UserAccount(); String name = "turn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId()); return user.getId(); }
    private SubmissionResponse pending(long owner, String title, String summary) {
        var item = submissions.submit(owner, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", null)); knowledge.insert(item.fragmentId(), owner, 2000, summary, "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY(?), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'test-model', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, summary, item.fragmentId());
        jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id, user_id, title, video_url, parsed_at) VALUES (?, ?, ?, 'https://example.com/test-only.mp4', UTC_TIMESTAMP(3))", item.fragmentId(), owner, title);
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at) VALUES (?, ?, ?, 'test-private', ?, 10, REPEAT('a', 64), ?, UTC_TIMESTAMP(3))", item.fragmentId(), owner, kind, "private-fixture/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg"); return item;
    }
    private static class RecordingListener implements ChatTurnListener {
        final List<StringBuilder> texts = new ArrayList<>(); final List<Boolean> ends = new ArrayList<>(); final AtomicInteger tokens = new AtomicInteger();
        @Override public void roundStarted(int round) { texts.add(new StringBuilder()); }
        @Override public void delta(int round, String text) { tokens.incrementAndGet(); texts.get(round).append(text); }
        @Override public void roundEnded(int round, boolean intermediate) { ends.add(intermediate); }
    }
}
