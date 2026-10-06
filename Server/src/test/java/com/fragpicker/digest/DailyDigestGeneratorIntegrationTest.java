package com.fragpicker.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.content.ContentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DIGEST_GENERATOR_TEST_ENABLED", matches = "true")
class DailyDigestGeneratorIntegrationTest {
    private static final String SCHEMA = "fragpicker_digest_live_" + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate DATE = LocalDate.of(2026,10,6);
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate jdbc; @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge; @Autowired ContentService content; @Autowired DailyDigestGenerator generator;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create digest test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> true); registry.add("fragpicker.integrations.chat.timeout", () -> "90s");
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void realOwnedMySqlSummariesAreGroupedByDeepSeekAndMergedWithVerifiedReferences() {
        long owner = register(), foreign = register(); for (int i = 0; i < 11; i++) seed(owner); seed(foreign);
        var ids = jdbc.queryForList("SELECT id FROM fragments WHERE user_id = ? AND business_date = ? AND status = 'READY' ORDER BY id DESC", Long.class, owner, DATE);
        var sources = ids.stream().map(id -> { var item = content.get(owner,id); var data = item.knowledge(); return new DigestSource(id,owner,item.businessDate(),item.title(),item.author(),data.summary(),data.points(),data.categories(),data.keywords()); }).toList();
        var checkpoints = new ArrayList<DigestPiece>(); var result = generator.generate(owner, DATE, sources.iterator(), () -> false, checkpoints::add);
        assertThat(result.userId()).isEqualTo(owner); assertThat(result.date()).isEqualTo(DATE); assertThat(result.sourceCount()).isEqualTo(11); assertThat(result.modelCalls()).isEqualTo(3); assertThat(checkpoints).hasSize(3); assertThat(result.summary()).contains("导数"); assertThat(result.points()).isNotEmpty();
        for (var point : result.points()) for (long id : point.sourceIds()) { assertThat(ids).contains(id); assertThat(jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, id)).isEqualTo(owner); }
        assertThat(generator.generate(owner, DATE, Collections.emptyIterator(), () -> false, ignored -> { throw new AssertionError(); }).modelCalls()).isZero();
        System.out.println("Real daily digest generator: owned MySQL summaries, two DeepSeek leaf calls and verified merge succeeded.");
    }
    private void seed(long owner) {
        var saved = submissions.submit(owner, UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); String summary = "导数是函数的瞬时变化率，也对应曲线在一点的切线斜率。通过极限定义理解导数，用幂函数求导公式练习基础运算，再用导数符号判断函数单调性。";
        knowledge.insert(saved.fragmentId(), owner, 3000, summary, "[\"导数\",\"变化率\"]"); jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY('导数表示瞬时变化率','切线斜率帮助理解导数','根据导数符号判断函数单调性'), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'test-fixture', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, saved.fragmentId()); jdbc.update("UPDATE fragments SET status = 'READY', business_date = ? WHERE id = ?", DATE, saved.fragmentId());
    }
    private long register() { var credentials = Map.of("username", "digest_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var response = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = response.getBody().path("id").asLong(); owned.add(id); return id; }
}
