package com.fragpicker.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.KnowledgeResultMapper;
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
class DailyFragmentsIntegrationTest {
    private static final String SCHEMA = "fragpicker_daily_list_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate jdbc; @Autowired SubmissionService submissions; @Autowired KnowledgeResultMapper knowledge;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create daily list test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }

    @Test void pagesOwnedDayWithBoundedDefaultAndStableIdsAfterDeletionOrNewArrival() {
        var owner = login(); assertThat(get(owner, "?date=2026-10-06").getBody().path("items")).isEmpty(); var expected = new ArrayList<Long>(); for (int i = 0; i < 23; i++) expected.add(seed(owner, "2026-10-06", "QUEUED").fragmentId());
        var first = get(owner, "?date=2026-10-06&limit=20"); assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store"); assertThat(first.getBody().path("items")).hasSize(20); assertThat(get(owner, "?date=2026-10-06").getBody().path("items")).hasSize(10);
        var seen = ids(first.getBody()); long before = first.getBody().path("nextBefore").asLong(); jdbc.update("DELETE FROM fragments WHERE id = ?", before); var arrived = seed(owner, "2026-10-06", "QUEUED"); var last = get(owner, "?date=2026-10-06&limit=20&before=" + before).getBody(); seen.addAll(ids(last)); assertThat(seen).containsExactlyElementsOf(expected.reversed()); assertThat(last.path("nextBefore").isNull()).isTrue(); assertThat(get(owner, "?date=2026-10-06&limit=1").getBody().path("items").get(0).path("fragmentId").asLong()).isEqualTo(arrived.fragmentId());
        var pending = first.getBody().path("items").get(0); assertThat(pending.path("title").asText()).isEqualTo("投喂记录"); assertThat(pending.path("summary").isNull()).isTrue(); assertThat(pending.path("summaryOrigin").asText()).isEqualTo("NONE"); assertThat(pending.path("coverMediaPath").isNull()).isTrue();
    }
    @Test void exposesEnrichedAndPartialTranscriptionPreviewsWithSafeUnicodeAndMediaPaths() {
        var owner = login(); var enriched = seed(owner, "2026-10-06", "READY"); var partial = seed(owner, "2026-10-06", "FAILED");
        knowledge.insert(enriched.fragmentId(), owner.id(), 2000, "原摘要", "[]"); knowledge.insert(partial.fragmentId(), owner.id(), 2000, "😀".repeat(2200), "[]");
        jdbc.update("UPDATE fragment_knowledge SET enriched_summary = '导数描述变化率', bullet_points = JSON_ARRAY('学习导数'), categories = JSON_ARRAY('LEARNING'), enriched_at = UTC_TIMESTAMP(3), enrichment_model = 'test-fixture' WHERE fragment_id = ?", enriched.fragmentId()); jdbc.update("UPDATE ingestion_jobs SET error_code = 'KNOWLEDGE_FAILED' WHERE id = ?", partial.jobId());
        jdbc.update("INSERT INTO fragment_video_metadata (fragment_id, user_id, title, author_name, video_url, parsed_at) VALUES (?, ?, ?, ?, 'https://example.com/private-upstream', UTC_TIMESTAMP(3))", partial.fragmentId(), owner.id(), "😀".repeat(600), "作".repeat(150));
        for (String kind : List.of("VIDEO", "COVER")) jdbc.update("INSERT INTO fragment_stored_media (fragment_id, user_id, kind, bucket, object_key, size_bytes, sha256, content_type, stored_at) VALUES (?, ?, ?, 'test-private', ?, 10, REPEAT('a',64), ?, UTC_TIMESTAMP(3))", partial.fragmentId(), owner.id(), kind, "private-fixture/" + kind, kind.equals("VIDEO") ? "video/mp4" : "image/jpeg");
        var body = get(owner, "?date=2026-10-06").getBody(); var raw = body.path("items").get(0); var ready = body.path("items").get(1);
        assertThat(raw.path("summary").asText()).isEqualTo("😀".repeat(400)); assertThat(raw.path("summaryTruncated").asBoolean()).isTrue(); assertThat(raw.path("summaryOrigin").asText()).isEqualTo("TRANSCRIPTION"); assertThat(raw.path("title").asText()).isEqualTo("😀".repeat(500)); assertThat(raw.path("titleTruncated").asBoolean()).isTrue(); assertThat(raw.path("author").asText()).isEqualTo("作".repeat(100)); assertThat(raw.path("authorTruncated").asBoolean()).isTrue(); assertThat(raw.path("errorCode").asText()).isEqualTo("KNOWLEDGE_FAILED");
        assertThat(raw.path("videoMediaPath").asText()).isEqualTo("/api/v1/fragments/" + partial.fragmentId() + "/media?kind=VIDEO"); assertThat(raw.path("coverMediaPath").asText()).endsWith("kind=COVER"); assertThat(raw.path("contentPath").asText()).isEqualTo("/api/v1/fragments/" + partial.fragmentId() + "/content");
        assertThat(ready.path("summaryOrigin").asText()).isEqualTo("ENRICHED"); assertThat(ready.path("summary").asText()).isEqualTo("导数描述变化率"); assertThat(ready.path("summaryTruncated").asBoolean()).isFalse(); assertThat(ready.path("categories").get(0).asText()).isEqualTo("LEARNING");
        assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, partial.fragmentId())).isEqualTo("😀".repeat(2200)); assertThat(body.toString()).doesNotContain("userId", "objectKey", "sourceUrl", "private-upstream", "private-fixture", "secret-note");
    }
    @Test void isolatesOwnersAndDatesAndRejectsBadInputsOrRevokedLogin() {
        var owner = login(); var foreign = login(); var saved = seed(owner, "2026-10-06", "QUEUED"); seed(owner, "2026-10-07", "QUEUED"); seed(foreign, "2026-10-06", "QUEUED"); assertThat(ids(get(owner, "?date=2026-10-06&userId=" + foreign.id()).getBody())).containsExactly(saved.fragmentId());
        assertThat(http.getForEntity("/api/v1/fragments?date=2026-10-06", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String query : List.of("", "?date=", "?date=2026-02-29", "?date=2026-10-6", "?date=0999-01-01", "?date=10000-01-01", "?date=2026-10-06&limit=0", "?date=2026-10-06&limit=21", "?date=2026-10-06&before=0", "?date=2026-10-06&before=9223372036854775808", "?date=2026-10-06&before=x")) { var invalid = get(owner, query); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_HISTORY_PAGE"); }
        assertThat(get(owner, "?date=9999-12-31&before=9223372036854775807").getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(get(owner, "?date=1000-01-01").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers(owner)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); assertThat(get(owner, "?date=2026-10-06").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void corruptCategoriesFailWholePageWithoutExposingOriginalData() {
        var owner = login(); var saved = seed(owner, "2026-10-06", "READY"); knowledge.insert(saved.fragmentId(), owner.id(), 2000, "secret-original", "[]"); jdbc.update("UPDATE fragment_knowledge SET categories = JSON_ARRAY('INVALID') WHERE fragment_id = ?", saved.fragmentId()); var response = get(owner, "?date=2026-10-06"); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(response.getBody().path("code").asText()).isEqualTo("CONTENT_INVALID"); assertThat(response.getBody().toString()).doesNotContain("secret-original"); assertThat(response.getBody().has("categories")).isFalse(); assertThat(response.getBody().has("items")).isFalse();
    }
    private List<Long> ids(JsonNode page) { var ids = new ArrayList<Long>(); page.path("items").forEach(item -> ids.add(item.path("fragmentId").asLong())); return ids; }
    private SubmissionResponse seed(Account owner, String date, String stage) { var saved = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), "secret-note")); jdbc.update("UPDATE fragments SET business_date = ?, status = ? WHERE id = ?", LocalDate.parse(date), stage, saved.fragmentId()); return saved; }
    private ResponseEntity<JsonNode> get(Account owner, String query) { return http.exchange("/api/v1/fragments" + query, HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); }
    private HttpHeaders headers(Account owner) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private Account login() { var credentials = Map.of("username", "daily_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
