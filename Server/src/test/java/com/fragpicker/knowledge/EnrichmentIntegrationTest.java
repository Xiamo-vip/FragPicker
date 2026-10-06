package com.fragpicker.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.ingestion.*;
import com.fragpicker.integration.chat.*;
import com.fragpicker.integration.tingwu.TingwuResult;
import com.fragpicker.user.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class EnrichmentIntegrationTest {
    private static final String SCHEMA = "fragpicker_enrich_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired EnrichmentStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountMapper users;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> owned = new ArrayList<>();
    private ChatModel model;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create knowledge test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.knowledge.enrichment.enabled", () -> false); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @BeforeEach void model() {
        model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"summary\":\"用导数理解函数变化。\",\"points\":[\"导数是变化率。\"],\"categories\":[\"LEARNING\"]}")).build());
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void atomicallyAddsEnrichmentPreservingOriginalTranscriptAndDoesNoIoInTransaction() {
        var item = pending();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"summary\":\"用导数理解函数变化。\",\"points\":[\"导数是变化率。\"],\"categories\":[\"LEARNING\"]}")).build();
        }).when(model).chat(any(ChatRequest.class));
        assertThat(worker(model).runOnce()).isTrue(); assertThat(stage(item)).isEqualTo("INDEX_PENDING");
        assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo("导数描述变化率。可以通过切线理解函数。");
        assertThat(jdbc.queryForObject("SELECT categories FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).contains("LEARNING");
        assertThat(jdbc.queryForObject("SELECT enriched_summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).contains("导数");
        assertThat(jdbc.queryForObject("SELECT start_ms FROM fragment_sentences WHERE fragment_id = ?", Long.class, item.fragmentId())).isEqualTo(1000);
        assertThat(worker(model).runOnce()).isFalse(); verify(model, times(1)).chat(any(ChatRequest.class));
    }
    @Test void boundsProviderAndMalformedOutputFailuresWithoutDiscardingRawKnowledge() {
        var item = pending(); when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("invalid output")).build());
        for (int i = 0; i < 3; i++) { due(item); worker(model).runOnce(); }
        assertThat(stage(item)).isEqualTo("FAILED"); assertThat(error(item)).isEqualTo("KNOWLEDGE_AI_INVALID_RESPONSE");
        assertThat(jdbc.queryForObject("SELECT enriched_summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_sentences WHERE fragment_id = ?", Integer.class, item.fragmentId())).isEqualTo(1);
        verify(model, times(3)).chat(any(ChatRequest.class));
    }
    @Test void rejectsStaleAndWrongOwnerAndRecoversExpiredClaimsWithoutRewritingOldData() {
        var item = pending(); var first = store.claim().orElseThrow(); expire(item); var next = store.claim().orElseThrow();
        var result = new EnrichmentResult("导数", List.of("变化率"), List.of(EnrichmentResult.Category.LEARNING));
        assertThat(store.complete(first, result, "test-model")).isFalse(); assertThat(store.fail(first, "KNOWLEDGE_TIMEOUT", true)).isFalse();
        var wrong = new EnrichmentLease(next.jobId(), next.fragmentId(), next.userId() + 1, next.version(), next.owner(), next.attempt());
        assertThat(store.complete(wrong, result, "test-model")).isFalse(); assertThatThrownBy(() -> store.source(wrong)).isInstanceOf(IllegalStateException.class);
        assertThat(store.samples(wrong)).isEmpty(); assertThat(store.complete(next, result, "test-model")).isTrue();
        assertThat(stage(item)).isEqualTo("INDEX_PENDING");
    }
    @Test void skipsLockedRowsAndExhaustsAbandonedLeaseBudget() {
        var first = pending(); var second = pending();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM ingestion_jobs WHERE id = ? FOR UPDATE", Long.class, first.jobId());
            try (var pool = Executors.newSingleThreadExecutor()) { assertThat(pool.submit(store::claim).get(3, TimeUnit.SECONDS).orElseThrow().jobId()).isEqualTo(second.jobId()); }
            catch (Exception failure) { throw new AssertionError("Expected locked row to be skipped", failure); }
        });
        for (int i = 0; i < 3; i++) { assertThat(store.claim().orElseThrow().jobId()).isEqualTo(first.jobId()); expire(first); }
        assertThat(store.claim()).isEmpty(); assertThat(error(first)).isEqualTo("KNOWLEDGE_LEASE_EXPIRED");
    }
    @Test void databaseFailureRollsBackEnrichmentAndAllowsStageLocalRetry() {
        var item = pending(); jdbc.update("UPDATE ingestion_jobs SET attempt_count = 100 WHERE id = ?", item.jobId());
        jdbc.execute("CREATE TRIGGER reject_enrichment BEFORE UPDATE ON fragment_knowledge FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test knowledge transaction failure'");
        try {
            worker(model).runOnce(); assertThat(stage(item)).isEqualTo("KNOWLEDGE_PENDING");
            assertThat(jdbc.queryForObject("SELECT categories FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isNull();
        } finally { jdbc.execute("DROP TRIGGER reject_enrichment"); }
        due(item); worker(model).runOnce(); assertThat(stage(item)).isEqualTo("INDEX_PENDING");
        assertThat(jdbc.queryForObject("SELECT knowledge_attempt_count FROM ingestion_jobs WHERE id = ?", Integer.class, item.jobId())).isEqualTo(2);
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "ENRICHMENT_TEST_ENABLED", matches = "true")
    void realDeepSeekEnrichesSavedChineseKnowledgeAndPersistsResults() {
        var item = pending(); String base = System.getenv("AI_CHAT_BASE_URL");
        var properties = new ChatModelProperties(true, base == null || base.isBlank() ? "https://api.deepseek.com" : base,
                System.getenv("AI_CHAT_API_KEY"), System.getenv("AI_CHAT_MODEL"), Duration.ofSeconds(90), 4096);
        new ApplicationContextRunner().withUserConfiguration(ChatModelConfiguration.class)
                .withPropertyValues("fragpicker.integrations.chat.enabled=true").withBean(ChatModelProperties.class, () -> properties)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed(); var live = ctx.getBean(ChatModel.class);
                    assertThat(new EnrichmentWorker(store, new KnowledgeEnricher(live, new ObjectMapper()), properties.model()).runOnce()).isTrue();
                    assertThat(stage(item)).as("real enrichment stage, error=" + error(item)).isEqualTo("INDEX_PENDING");
                    assertThat(jdbc.queryForObject("SELECT enriched_summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).contains("导数");
                    assertThat(jdbc.queryForObject("SELECT categories FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).contains("LEARNING");
                    assertThat(jdbc.queryForObject("SELECT bullet_points FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isNotBlank();
                    assertThat(jdbc.queryForObject("SELECT enrichment_model FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo(properties.model());
                    System.out.println("Real DeepSeek knowledge enrichment: summary, points, category and model identity committed to MySQL.");
                });
    }
    private EnrichmentWorker worker(ChatModel model) { return new EnrichmentWorker(store, new KnowledgeEnricher(model, new ObjectMapper()), "test-model"); }
    private SubmissionResponse pending() {
        String name = "enrich_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); var user = new UserAccount();
        user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId());
        var item = submissions.submit(user.getId(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", "学习导数"));
        jdbc.update("UPDATE ingestion_jobs SET stage = 'KNOWLEDGE_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'KNOWLEDGE_PENDING' WHERE id = ?", item.fragmentId());
        knowledge.insert(item.fragmentId(), user.getId(), 2000, "导数描述变化率。可以通过切线理解函数。", "[\"导数\",\"变化率\"]");
        knowledge.insertSentence(item.fragmentId(), user.getId(), 0, new TingwuResult.Sentence("0", "0", 0, 1000, 2000, "导数是函数变化率，可以从切线的斜率理解。")); return item;
    }
    private String stage(SubmissionResponse item) { return jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private String error(SubmissionResponse item) { return jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private void due(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", item.jobId()); }
    private void expire(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)) WHERE id = ?", item.jobId()); }
}
