package com.fragpicker.knowledge.index;

import com.fragpicker.ingestion.*;
import com.fragpicker.integration.tingwu.TingwuResult;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.user.*;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class IndexIntegrationTest {
    private static final String SCHEMA = "fragpicker_index_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired IndexStore store;
    @Autowired IndexMapper indexes;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> owned = new ArrayList<>();
    private LocalEmbeddingService embeddings;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create semantic index test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.knowledge.index.enabled", () -> false); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @BeforeEach void mockModel() {
        embeddings = mock(LocalEmbeddingService.class);
        when(embeddings.embedDocuments(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            float[] unit = new float[512]; unit[0] = 1;
            List<String> documents = call.getArgument(0); return documents.stream().map(d -> Embedding.from(unit.clone())).toList();
        });
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void realOnnxVectorsSurviveMySqlAndRankDerivativeAboveCookingForRateOfChange() {
        var math = pending("数学课程：导数的定义和计算。通过切线斜率与极限理解微分，讲解求导公式和函数单调性。");
        var cooking = pending("家常菜教程：番茄炒鸡蛋的做法，食材准备、油温控制和调味步骤。");
        var foreign = pending("其他用户私有的导数资料。");
        var actual = new LocalEmbeddingService(); var worker = new IndexWorker(store, actual);
        for (int i = 0; i < 3; i++) assertThat(worker.runOnce()).isTrue(); assertThat(worker.runOnce()).isFalse();
        var query = actual.embedQuery("我想学习函数在某一点的瞬时变化率，找之前保存的数学学习资源").vector();
        double mathScore = score(query, math), cookingScore = score(query, cooking);
        assertThat(mathScore).isGreaterThan(cookingScore + 0.05);
        assertThat(jdbc.queryForObject("SELECT model_id FROM fragment_indexes WHERE fragment_id = ?", String.class, math.fragmentId())).isEqualTo(LocalEmbeddingService.MODEL_ID);
        assertThat(jdbc.queryForObject("SELECT chunker_version FROM fragment_indexes WHERE fragment_id = ?", String.class, math.fragmentId())).isEqualTo(UnicodeChunker.VERSION);
        assertThat(stage(math)).isEqualTo("READY"); assertThat(stage(cooking)).isEqualTo("READY"); assertThat(stage(foreign)).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT fragment_id FROM fragment_index_chunks WHERE user_id = ?", Long.class, user(math))).containsOnly(math.fragmentId());
        System.out.printf("Real ONNX + MySQL index: derivative=%.4f, cooking=%.4f; owner scope and model metadata verified.%n", mathScore, cookingScore);
    }
    @Test void indexesAllTranscriptPagesAndPreservesTailAndSentenceAttribution() {
        var item = pending("摘要"); long user = user(item);
        for (int i = 0; i < 130; i++) knowledge.insertSentence(item.fragmentId(), user, i,
                new TingwuResult.Sentence("0", "0", i, i * 1000L, (i + 1) * 1000L, i == 129 ? "学😀".repeat(400) + "转写尾部不可截断" : "第" + i + "段原文"));
        knowledge.insertPoint(item.fragmentId(), user, 0, new TingwuResult.KeyPoint(0, 0, 1000, "重点内容"));
        assertThat(new IndexWorker(store, embeddings).runOnce()).isTrue(); assertThat(stage(item)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT source_ordinal) FROM fragment_index_chunks WHERE fragment_id = ? AND source_kind = 'TRANSCRIPT'", Integer.class, item.fragmentId())).isEqualTo(130);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_index_chunks WHERE fragment_id = ? AND content LIKE '%转写尾部不可截断%' AND start_ms = 129000 AND end_ms = 130000", Integer.class, item.fragmentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_index_chunks WHERE fragment_id = ? AND source_kind = 'KEY_POINT'", Integer.class, item.fragmentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT chunk_count FROM fragment_indexes WHERE fragment_id = ?", Integer.class, item.fragmentId()))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_index_chunks WHERE fragment_id = ?", Integer.class, item.fragmentId()));
        assertThat(jdbc.queryForObject("SELECT MAX(ordinal) FROM fragment_index_chunks WHERE fragment_id = ?", Integer.class, item.fragmentId()))
                .isEqualTo(jdbc.queryForObject("SELECT chunk_count - 1 FROM fragment_indexes WHERE fragment_id = ?", Integer.class, item.fragmentId()));
    }
    @Test void fencesOldOrForeignClaimsAndRenewalsAndRecoversExpiredLease() {
        var item = pending("导数课程"); var old = store.claim().orElseThrow(); expire(item); var next = store.claim().orElseThrow();
        assertThat(store.renew(old)).isFalse(); assertThat(store.complete(old, saved())).isFalse(); assertThat(store.fail(old, "INDEX_INTERNAL_ERROR", true)).isFalse();
        var wrong = new IndexLease(next.jobId(), next.fragmentId(), next.userId() + 1, next.version(), next.owner(), next.attempt());
        assertThat(store.renew(wrong)).isFalse(); assertThat(store.complete(wrong, saved())).isFalse();
        assertThatThrownBy(() -> store.chunks(wrong)).isInstanceOf(IndexFailure.class); assertThat(indexes.metadata(wrong)).isEmpty(); assertThat(indexes.sentences(wrong, -1)).isEmpty();
        assertThat(store.renew(next)).isTrue(); assertThat(store.complete(next, saved())).isTrue(); assertThat(stage(item)).isEqualTo("READY");
        assertThat(store.renew(next)).isFalse();
    }
    @Test void skipsLockedRowsAndExhaustsIndependentLeaseBudget() {
        var first = pending("一"); var second = pending("二");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM ingestion_jobs WHERE id = ? FOR UPDATE", Long.class, first.jobId());
            try (var pool = Executors.newSingleThreadExecutor()) { assertThat(pool.submit(store::claim).get(3, TimeUnit.SECONDS).orElseThrow().jobId()).isEqualTo(second.jobId()); }
            catch (Exception failure) { throw new AssertionError("Expected locked index row to be skipped", failure); }
        });
        jdbc.update("UPDATE ingestion_jobs SET attempt_count = 100 WHERE id = ?", first.jobId());
        for (int i = 0; i < 3; i++) { assertThat(store.claim().orElseThrow().jobId()).isEqualTo(first.jobId()); expire(first); }
        assertThat(store.claim()).isEmpty(); assertThat(stage(first)).isEqualTo("FAILED"); assertThat(error(first)).isEqualTo("INDEX_LEASE_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ingestion_jobs WHERE id = ?", Integer.class, first.jobId())).isEqualTo(103);
    }
    @Test void workerDiscardsLateInferenceAfterAnotherExecutorRecoversItsLease() {
        var item = pending("导数学习资料"); var recovered = new java.util.concurrent.atomic.AtomicReference<IndexLease>();
        when(embeddings.embedDocuments(anyList())).thenAnswer(call -> {
            expire(item); recovered.set(store.claim().orElseThrow());
            float[] unit = new float[512]; unit[0] = 1; List<String> documents = call.getArgument(0);
            return documents.stream().map(d -> Embedding.from(unit.clone())).toList();
        });
        assertThat(new IndexWorker(store, embeddings).runOnce()).isTrue(); assertThat(stage(item)).isEqualTo("INDEXING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_indexes WHERE fragment_id = ?", Integer.class, item.fragmentId())).isZero();
        assertThat(store.complete(recovered.get(), saved())).isTrue(); assertThat(stage(item)).isEqualTo("READY");
        assertThat(new IndexWorker(store, embeddings).runOnce()).isFalse(); verify(embeddings, times(1)).embedDocuments(anyList());
    }
    @Test void rollsBackWholeReplacementOnBatchFailureAndPreservesExistingIndexUntilRetry() {
        var item = pending("资料"); var first = store.claim().orElseThrow(); store.complete(first, saved());
        jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        jdbc.execute("CREATE TRIGGER reject_index BEFORE INSERT ON fragment_index_chunks FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test index transaction failure'");
        try {
            new IndexWorker(store, embeddings).runOnce(); assertThat(stage(item)).isEqualTo("INDEX_PENDING");
            assertThat(jdbc.queryForObject("SELECT content FROM fragment_index_chunks WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo("旧索引");
            assertThat(jdbc.queryForObject("SELECT chunk_count FROM fragment_indexes WHERE fragment_id = ?", Integer.class, item.fragmentId())).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER reject_index"); }
        due(item); new IndexWorker(store, embeddings).runOnce(); assertThat(stage(item)).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT content FROM fragment_index_chunks WHERE fragment_id = ?", String.class, item.fragmentId())).doesNotContain("旧索引");
    }
    @Test void rejectsOversizedInputWithoutInferenceOrSilentTruncationAndBoundsFailures() {
        var large = pending("学".repeat(10000));
        var limited = new IndexStore(indexes, mock(FragmentRecordMapper.class), new IndexProperties(false, java.time.Duration.ofSeconds(2), java.time.Duration.ofMinutes(10), 3, java.time.Duration.ofSeconds(10), 16));
        var lease = store.claim().orElseThrow(); assertThatThrownBy(() -> limited.chunks(lease)).isInstanceOf(IndexFailure.class);
        store.fail(lease, "INDEX_TOO_LARGE", false); assertThat(stage(large)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(summary) FROM fragment_knowledge WHERE fragment_id = ?", Integer.class, large.fragmentId())).isEqualTo(10000);
        verifyNoInteractions(embeddings);
        var failed = pending("正常资料"); when(embeddings.embedDocuments(anyList())).thenThrow(new IllegalStateException("private input must not be logged"));
        for (int i = 0; i < 3; i++) { due(failed); new IndexWorker(store, embeddings).runOnce(); }
        assertThat(stage(failed)).isEqualTo("FAILED"); assertThat(error(failed)).isEqualTo("INDEX_INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_indexes WHERE fragment_id = ?", Integer.class, failed.fragmentId())).isZero();
    }
    @Test void databaseEnforcesOwnerVectorAndTimestampConstraintsAndCascades() {
        var item = pending("导数"); var other = pending("菜谱"); var lease = store.claim().orElseThrow(); store.complete(lease, saved());
        assertThatThrownBy(() -> jdbc.update("UPDATE fragment_index_chunks SET user_id = ? WHERE fragment_id = ?", user(other), item.fragmentId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE fragment_index_chunks SET embedding = ? WHERE fragment_id = ?", new byte[4], item.fragmentId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(e -> assertThat(((java.sql.SQLException) e.getCause()).getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("UPDATE fragment_index_chunks SET start_ms = 2, end_ms = 1 WHERE fragment_id = ?", item.fragmentId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(e -> assertThat(((java.sql.SQLException) e.getCause()).getErrorCode()).isEqualTo(3819));
        jdbc.update("DELETE FROM users WHERE id = ?", user(item));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_index_chunks WHERE fragment_id = ?", Integer.class, item.fragmentId())).isZero();
    }
    private SubmissionResponse pending(String summary) {
        String name = "index_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); var user = new UserAccount();
        user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId());
        var item = submissions.submit(user.getId(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", null));
        jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        knowledge.insert(item.fragmentId(), user.getId(), 200000, summary, "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY(?), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'test-model', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, summary.length() > 300 ? "要点" : summary, item.fragmentId()); return item;
    }
    private List<IndexedChunk> saved() { float[] unit = new float[512]; unit[0] = 1; return List.of(new IndexedChunk(new TextChunk("SUMMARY", null, null, null, "旧索引"), VectorCodec.encode(unit))); }
    private double score(float[] query, SubmissionResponse item) {
        byte[] bytes = jdbc.queryForObject("SELECT embedding FROM fragment_index_chunks WHERE fragment_id = ? AND user_id = ? AND source_kind = 'SUMMARY' ORDER BY ordinal LIMIT 1", byte[].class, item.fragmentId(), user(item));
        float[] vector = VectorCodec.decode(bytes); double score = 0; for (int i = 0; i < vector.length; i++) score += (double) query[i] * vector[i]; return score;
    }
    private long user(SubmissionResponse item) { return jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, item.fragmentId()); }
    private String stage(SubmissionResponse item) { return jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private String error(SubmissionResponse item) { return jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private void due(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", item.jobId()); }
    private void expire(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)) WHERE id = ?", item.jobId()); }
}
