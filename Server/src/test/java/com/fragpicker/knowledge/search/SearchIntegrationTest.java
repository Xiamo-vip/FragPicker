package com.fragpicker.knowledge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import com.fragpicker.knowledge.index.*;
import com.fragpicker.integration.tingwu.TingwuResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class SearchIntegrationTest {
    private static final String SCHEMA = "fragpicker_search_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    @Autowired IndexStore indexes;
    @Autowired SearchMapper data;
    @Autowired @Lazy LocalEmbeddingService model;
    @Autowired ObjectMapper json;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create search test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.knowledge.index.enabled", () -> false); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void realHttpAndOnnxFindsDerivativeWithoutLiteralOverlapAndReturnsOwnedMediaCards() {
        var owner = login(); var foreign = login();
        var math = pending(owner, "数学课程：导数的定义和计算。通过切线斜率与极限理解微分，讲解求导公式和函数单调性。", "数学入门", "数学老师", "LEARNING");
        pending(owner, "家常菜教程：番茄炒鸡蛋的做法，食材准备、油温控制和调味步骤。", "菜谱", "厨房老师", "LIFESTYLE");
        pending(foreign, "数学课程：导数的定义和计算。通过切线斜率与极限理解微分，讲解求导公式和函数单调性。", "别人的数学资料", "数学老师", "LEARNING");
        jdbc.update("UPDATE fragment_knowledge SET display_title='变化率入门', introduction='了解变化率。' WHERE fragment_id=?", math.fragmentId());
        var worker = new IndexWorker(indexes, model); for (int i = 0; i < 3; i++) assertThat(worker.runOnce()).isTrue();
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at) VALUES (?, ?, ?, 'test-private', ?, 10, REPEAT('a', 64), ?, UTC_TIMESTAMP(3))", math.fragmentId(), owner.id(), kind, "test-only/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg");
        var response = post(owner, Map.of("query", "我想学习函数在某一点的瞬时变化率，找之前保存的数学学习资源", "limit", 5, "userId", foreign.id()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var items = response.getBody().path("items"); assertThat(items.isEmpty()).isFalse();
        var first = items.get(0); assertThat(first.path("fragmentId").asLong()).isEqualTo(math.fragmentId()); assertThat(first.path("literalMatch").asBoolean()).isFalse();
        assertThat(first.path("semanticScore").asDouble()).isGreaterThan(.4); assertThat(first.path("summary").asText()).contains("导数"); assertThat(first.path("categories").get(0).asText()).isEqualTo("LEARNING");
        assertThat(first.path("title").asText()).isEqualTo("变化率入门");
        assertThat(first.path("introduction").asText()).isEqualTo("了解变化率。");
        assertThat(first.path("summary").asText()).contains("函数单调性");
        assertThat(first.path("videoMediaPath").asText()).isEqualTo("/api/v1/fragments/" + math.fragmentId() + "/media?kind=VIDEO");
        assertThat(first.path("coverMediaPath").asText()).endsWith("kind=COVER"); assertThat(items.toString()).doesNotContain("别人的", "object_key", "test-only", "embedding", "video_url");
        System.out.println("Real HTTP + MySQL + ONNX semantic search: rate-of-change query returns owned derivative card; foreign owner excluded.");
    }
    @Test void combinesInclusiveDateCategoryAuthorAndLiteralKeywordFiltersWithoutWildcards() {
        var owner = login(); var kept = pending(owner, "数学导数资料，独有线索包含%_符号", "导数教程", "王老师", "LEARNING"); ready(kept, 1, "独有线索包含%_符号");
        jdbc.update("UPDATE fragments SET business_date = '2026-10-06' WHERE id = ?", kept.fragmentId());
        var other = pending(owner, "导数资料", "导数教程", "李老师", "TECHNOLOGY"); ready(other, 1, "导数资料"); jdbc.update("UPDATE fragments SET business_date = '2026-10-05' WHERE id = ?", other.fragmentId());
        var filters = Map.of("query", "线索", "fromDate", "2026-10-06", "toDate", "2026-10-06", "category", "LEARNING", "author", "王", "keyword", "%_", "limit", 10);
        var result = post(owner, filters); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(result.getBody().path("items")).hasSize(1);
        var match = result.getBody().path("items").get(0).path("match"); assertThat(match.path("sourceKind").asText()).isEqualTo("TRANSCRIPT"); assertThat(match.path("startMs").asLong()).isEqualTo(1000); assertThat(match.path("endMs").asLong()).isEqualTo(2000);
        assertThat(post(owner, Map.of("query", "导数", "keyword", "not-present")).getBody().path("items")).isEmpty();
        assertThat(post(owner, Map.of("query", "导数", "category", "ART")).getBody().path("items")).isEmpty();
        assertThat(post(owner, Map.of("query", "导数", "author", "%")).getBody().path("items")).isEmpty();
    }
    @Test void scansAllKeysetPagesGroupsVideosAndKeepsOnlyMatchingModelAndReadyState() {
        var owner = login(); var many = pending(owner, "数学", "长视频", "老师", "LEARNING"); ready(many, 270, "导数正文");
        var next = pending(owner, "数学", "短视频", "老师", "LEARNING"); ready(next, 1, "导数正文");
        var hidden = pending(owner, "数学", "未完成", "老师", "LEARNING"); ready(hidden, 1, "导数正文"); jdbc.update("UPDATE fragments SET status = 'INDEXING' WHERE id = ?", hidden.fragmentId());
        var old = pending(owner, "数学", "过期模型", "老师", "LEARNING"); ready(old, 1, "导数正文"); jdbc.update("UPDATE fragment_indexes SET model_id = 'old-model' WHERE fragment_id = ?", old.fragmentId());
        var scope = new SearchScope(owner.id(), "导数", null, null, null, null, null, 20);
        assertThat(data.countChunks(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION)).isEqualTo(271);
        var first = data.page(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION, 0, -1, 256); assertThat(first).hasSize(256);
        var second = data.page(scope, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION, first.getLast().fragmentId(), first.getLast().ordinal(), 256); assertThat(second).hasSize(15); assertThat(second.getLast().fragmentId()).isEqualTo(next.fragmentId());
        var response = post(owner, Map.of("query", "导数", "limit", 20)); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("items")).hasSize(2); assertThat(response.getBody().path("scannedChunks").asInt()).isEqualTo(271);
        var limited = new SearchService(data, model, new SearchProperties(100, Duration.ofSeconds(15), .4, .1), json);
        assertThatThrownBy(() -> limited.search(owner.id(), new SearchRequest("导数", null, null, null, null, null, 10))).isInstanceOf(com.fragpicker.common.api.ApiException.class)
                .satisfies(e -> assertThat(((com.fragpicker.common.api.ApiException) e).code()).isEqualTo("SEARCH_SCOPE_TOO_LARGE"));
    }
    @Test void requiresLoginValidatesInputsAndDoesNotReturnForeignData() {
        assertThat(http.postForEntity("/api/v1/fragments/search", Map.of("query", "导数"), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var owner = login(); var stranger = login(); var item = pending(owner, "资料", "机密资料", "作者", "LEARNING"); ready(item, 1, "机密");
        assertThat(post(stranger, Map.of("query", "机密", "userId", owner.id())).getBody().path("items")).isEmpty();
        assertThat(post(stranger, Map.of("query", "机密", "userId", owner.id())).getBody().path("scannedChunks").asInt()).isZero();
        for (Map<String, ?> body : List.of(Map.of("query", ""), Map.of("query", "导数", "limit", 21), Map.of("query", "导数", "fromDate", "2026-10-07", "toDate", "2026-10-06"), Map.of("query", "导数", "category", "UNKNOWN")))
            assertThat(post(owner, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void keywordFilterReadsOriginalTextAcrossChunkBoundaries() {
        var owner = login(); var item = pending(owner, "数学资料", "视频", "老师", "LEARNING");
        String keyword = "甲".repeat(63) + "乙";
        knowledge.insertSentence(item.fragmentId(), owner.id(), 0, new TingwuResult.Sentence("0", "0", 0, 1000, 2000, "导数" + "学".repeat(333) + keyword + "学".repeat(50)));
        assertThat(new IndexWorker(indexes, model).runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_index_chunks WHERE fragment_id = ? AND LOCATE(?, content) > 0", Integer.class, item.fragmentId(), keyword)).isZero();
        var response = post(owner, Map.of("query", "导数", "keyword", keyword)); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("items")).hasSize(1); assertThat(response.getBody().path("items").get(0).path("fragmentId").asLong()).isEqualTo(item.fragmentId());
    }
    @Test void refusesCorruptVectorWithoutExposingDataAndRecoversAfterIndexRepair() {
        var owner = login(); var item = pending(owner, "资料", "资料", "老师", "LEARNING"); ready(item, 1, "导数");
        jdbc.update("UPDATE fragment_index_chunks SET embedding = ? WHERE fragment_id = ?", new byte[2048], item.fragmentId());
        var invalid = post(owner, Map.of("query", "导数")); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(invalid.getBody().path("code").asText()).isEqualTo("SEARCH_INDEX_INVALID");
        float[] unit = new float[512]; unit[0] = 1; jdbc.update("UPDATE fragment_index_chunks SET embedding = ? WHERE fragment_id = ?", VectorCodec.encode(unit), item.fragmentId());
        assertThat(post(owner, Map.of("query", "导数")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private SubmissionResponse pending(Account owner, String summary, String title, String author, String category) {
        var item = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", null));
        jdbc.update("UPDATE ingestion_jobs SET stage = 'INDEX_PENDING' WHERE id = ?", item.jobId()); jdbc.update("UPDATE fragments SET status = 'INDEX_PENDING' WHERE id = ?", item.fragmentId());
        knowledge.insert(item.fragmentId(), owner.id(), 2000, summary, "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = ?, bullet_points = JSON_ARRAY(?), categories = JSON_ARRAY(?), enrichment_model = 'test-model', enriched_at = UTC_TIMESTAMP(3) WHERE fragment_id = ?", summary, summary, category, item.fragmentId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id, user_id, title, video_url, author_name, parsed_at) VALUES (?, ?, ?, 'https://example.com/test-only.mp4', ?, UTC_TIMESTAMP(3))", item.fragmentId(), owner.id(), title, author); return item;
    }
    private void ready(SubmissionResponse item, int count, String content) {
        var lease = indexes.claim().orElseThrow(); assertThat(lease.fragmentId()).isEqualTo(item.fragmentId()); float[] unit = new float[512]; unit[0] = 1;
        var chunks = new ArrayList<IndexedChunk>(); for (int i = 0; i < count; i++) chunks.add(new IndexedChunk(new TextChunk("TRANSCRIPT", i, 1000L, 2000L, content), VectorCodec.encode(unit)));
        indexes.complete(lease, chunks);
    }
    private ResponseEntity<JsonNode> post(Account owner, Map<String, ?> body) {
        var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/v1/fragments/search", new HttpEntity<>(body, headers), JsonNode.class);
    }
    private Account login() {
        var credentials = Map.of("username", "search_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = registered.getBody().path("id").asLong(); owned.add(id);
        var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText());
    }
    private record Account(long id, String token) { }
}
