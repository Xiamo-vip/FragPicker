package com.fragpicker.knowledge.content;

import com.fasterxml.jackson.databind.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.integration.tingwu.TingwuResult;
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
class ContentIntegrationTest {
    private static final String SCHEMA = "fragpicker_content_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired ObjectMapper json;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create content test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void returnsOwnSummaryMetadataCountsUtcTimesAndProtectedMediaPaths() {
        var owner = login(); var item = fragment(owner); fill(owner, item, "原始听悟摘要：导数描述变化率。");
        knowledge.insertSentence(item.fragmentId(), owner.id(), 0, new TingwuResult.Sentence("0", "0", 0, 1000, 2000, "导数是变化率。"));
        knowledge.insertPoint(item.fragmentId(), owner.id(), 0, new TingwuResult.KeyPoint(0, 1000, 2000, "变化率重点"));
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at) VALUES (?, ?, ?, 'test-private', ?, 10, REPEAT('a', 64), ?, UTC_TIMESTAMP(3))", item.fragmentId(), owner.id(), kind, "private-test/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg");
        var response = get(owner, item.fragmentId()); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var body = response.getBody(); assertThat(body.path("title").asText()).isEqualTo("导数课程"); assertThat(body.path("author").asText()).isEqualTo("数学老师");
        assertThat(body.path("status").asText()).isEqualTo("READY"); assertThat(body.path("businessZone").asText()).isEqualTo("Asia/Shanghai"); assertThat(body.path("note").asText()).isEqualTo("学习数学");
        assertThat(body.path("sentenceCount").asLong()).isEqualTo(1); assertThat(body.path("keyPointCount").asLong()).isEqualTo(1);
        assertThat(body.path("videoMediaPath").asText()).endsWith("/media?kind=VIDEO"); assertThat(body.path("coverMediaPath").asText()).endsWith("/media?kind=COVER");
        var result = body.path("knowledge"); assertThat(result.path("summary").asText()).isEqualTo("增强摘要：用切线理解导数。"); assertThat(result.path("originalSummaryPreview").asText()).contains("原始听悟");
        assertThat(result.path("categories").get(0).asText()).isEqualTo("LEARNING"); assertThat(result.path("points")).hasSize(1); assertThat(result.path("keywords").get(0).asText()).isEqualTo("导数");
        for (String time : List.of("completedAt", "enrichedAt")) assertThat(Instant.parse(result.path(time).asText())).isBetween(Instant.now().minusSeconds(30), Instant.now());
        assertThat(body.toString()).doesNotContain("embedding", "video_url", "object_key", "private-test", "task_id", "leaseOwner", "https://example.com");
    }
    @Test void exposesAvailablePartialKnowledgeAndFailureStatusWithoutInventingSummary() {
        var owner = login(); var item = fragment(owner); var queued = get(owner, item.fragmentId()).getBody();
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED"); assertThat(queued.path("knowledge").isNull()).isTrue(); assertThat(queued.path("videoMediaPath").isNull()).isTrue();
        knowledge.insert(item.fragmentId(), owner.id(), 0, null, "[]");
        jdbc.update("UPDATE fragments SET status = 'FAILED' WHERE id = ?", item.fragmentId()); jdbc.update("UPDATE ingestion_jobs SET stage = 'FAILED', error_code = 'KNOWLEDGE_AI_UNAVAILABLE' WHERE id = ?", item.jobId());
        var failed = get(owner, item.fragmentId()).getBody(); assertThat(failed.path("errorCode").asText()).isEqualTo("KNOWLEDGE_AI_UNAVAILABLE"); assertThat(failed.path("knowledge").path("summary").isNull()).isTrue();
        assertThat(failed.path("knowledge").path("categories")).isEmpty(); assertThat(failed.path("knowledge").path("enrichedAt").isNull()).isTrue();
    }
    @Test void boundsUnicodePreviewsWithExplicitFlagsWhilePreservingFullDatabaseValues() throws Exception {
        var owner = login(); var item = fragment(owner); String original = "😀".repeat(4001); fill(owner, item, original);
        jdbc.update("UPDATE fragment_video_metadata SET title = ?, author_name = ? WHERE fragment_id = ?", "😀".repeat(501), "😀".repeat(101), item.fragmentId());
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = ?, keywords = ? WHERE fragment_id = ?", "😀".repeat(2001), json.writeValueAsString(Collections.nCopies(9, "😀".repeat(301))), json.writeValueAsString(Collections.nCopies(101, "😀".repeat(101))), item.fragmentId());
        var body = get(owner, item.fragmentId()).getBody(); assertThat(body.path("titleTruncated").asBoolean()).isTrue(); assertThat(body.path("title").asText().codePointCount(0, body.path("title").asText().length())).isEqualTo(500);
        assertThat(body.path("authorTruncated").asBoolean()).isTrue(); var result = body.path("knowledge");
        assertThat(result.path("originalSummaryTruncated").asBoolean()).isTrue(); assertThat(result.path("summaryTruncated").asBoolean()).isTrue(); assertThat(result.path("pointsTruncated").asBoolean()).isTrue(); assertThat(result.path("keywordsTruncated").asBoolean()).isTrue();
        assertThat(result.path("originalSummaryPreview").asText()).isEqualTo("😀".repeat(4000)); assertThat(result.path("summary").asText()).isEqualTo("😀".repeat(2000));
        assertThat(result.path("points")).hasSize(8); assertThat(result.path("points").get(0).asText()).isEqualTo("😀".repeat(300)); assertThat(result.path("keywords")).hasSize(100);
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(summary) FROM fragment_knowledge WHERE fragment_id = ?", Integer.class, item.fragmentId())).isEqualTo(4001);
        assertThat(jdbc.queryForObject("SELECT JSON_LENGTH(keywords) FROM fragment_knowledge WHERE fragment_id = ?", Integer.class, item.fragmentId())).isEqualTo(101);
    }
    @Test void requiresLoginAndHidesForeignRecordsEvenWithOwnerQueryParameter() {
        var owner = login(); var foreign = login(); var item = fragment(owner); fill(owner, item, "本人资料");
        assertThat(http.getForEntity("/api/v1/fragments/" + item.fragmentId() + "/content", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var hidden = get(foreign, item.fragmentId()); var missing = get(foreign, Long.MAX_VALUE); assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody().path("code").asText()).isEqualTo(missing.getBody().path("code").asText());
        assertThat(http.exchange("/api/v1/fragments/" + item.fragmentId() + "/content?userId=" + owner.id(), HttpMethod.GET, new HttpEntity<>(headers(foreign)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner, item.fragmentId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void rejectsInvalidJsonContentWithStableErrorAndDoesNotModifySourceData() {
        var owner = login(); var item = fragment(owner); fill(owner, item, "原始资料");
        for (String invalid : List.of("{\"private\":\"supplier-body\"}", "[123]", "[\"UNKNOWN\"]")) {
            jdbc.update("UPDATE fragment_knowledge SET categories = ? WHERE fragment_id = ?", invalid, item.fragmentId());
            var result = get(owner, item.fragmentId()); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(result.getBody().path("code").asText()).isEqualTo("CONTENT_INVALID"); assertThat(result.getBody().toString()).doesNotContain("supplier-body", "UNKNOWN");
        }
        assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo("原始资料");
    }
    private SubmissionResponse fragment(Account owner) { return submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", "学习数学")); }
    private void fill(Account owner, SubmissionResponse item, String original) {
        knowledge.insert(item.fragmentId(), owner.id(), 2000, original, "[\"导数\",\"变化率\"]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = '增强摘要：用切线理解导数。', bullet_points = JSON_ARRAY('导数描述变化率。'), categories = JSON_ARRAY('LEARNING'), enrichment_model = 'test-model', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", item.fragmentId());
        jdbc.update("UPDATE fragments SET status = 'READY' WHERE id = ?", item.fragmentId()); jdbc.update("UPDATE ingestion_jobs SET stage = 'READY' WHERE id = ?", item.jobId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id, user_id, title, video_url, author_name, parsed_at) VALUES (?, ?, '导数课程', 'https://example.com/test-only.mp4', '数学老师', UTC_TIMESTAMP(3))", item.fragmentId(), owner.id());
    }
    private ResponseEntity<JsonNode> get(Account owner, long id) { return http.exchange("/api/v1/fragments/" + id + "/content", HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); }
    private HttpHeaders headers(Account owner) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private Account login() {
        var credentials = Map.of("username", "content_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText());
    }
    private record Account(long id, String token) { }
}
