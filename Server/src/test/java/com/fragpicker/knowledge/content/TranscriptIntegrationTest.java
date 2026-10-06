package com.fragpicker.knowledge.content;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class TranscriptIntegrationTest {
    private static final String SCHEMA = "fragpicker_transcript_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionService submissions;
    @Autowired KnowledgeResultMapper knowledge;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create transcript test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }
    @AfterAll static void drop() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }
    @Test void reconstructsFullUnicodeLongSentenceAcrossPagesWithOriginalTimesAndOrdinalGaps() {
        var owner = login(); var item = fragment(owner); String original = "😀".repeat(4001) + "原文尾部";
        knowledge.insertSentence(item.fragmentId(), owner.id(), 0, new TingwuResult.Sentence("0", "😀".repeat(129), 77, 1200, 9600, original));
        knowledge.insertSentence(item.fragmentId(), owner.id(), 5, new TingwuResult.Sentence("1", null, 78, 9600, 10000, "下一句"));
        knowledge.insertSentence(item.fragmentId(), owner.id(), 8, new TingwuResult.Sentence("1", null, 79, 10000, 10000, ""));
        var rebuilt = new StringBuilder(); var ordinals = new ArrayList<Integer>(); var cursors = new HashSet<String>(); String params = "limit=2"; int pages = 0, expectedOffset = 0;
        while (true) {
            var response = get(owner, item.fragmentId(), params); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            var body = response.getBody(); assertThat(body.path("transcriptionAvailable").asBoolean()).isTrue(); assertThat(body.path("items").size()).isBetween(1, 2);
            for (var segment : body.path("items")) {
                int ordinal = segment.path("ordinal").asInt(); ordinals.add(ordinal); String text = segment.path("text").asText(); assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(1000);
                if (ordinal == 0) {
                    assertThat(segment.path("offset").asInt()).isEqualTo(expectedOffset); assertThat(segment.path("continuation").asBoolean()).isEqualTo(expectedOffset > 0); expectedOffset += text.codePointCount(0, text.length());
                    assertThat(segment.path("startMs").asLong()).isEqualTo(1200); assertThat(segment.path("endMs").asLong()).isEqualTo(9600); assertThat(segment.path("sentenceId").asLong()).isEqualTo(77);
                    assertThat(segment.path("speakerTruncated").asBoolean()).isTrue(); assertThat(segment.path("speakerId").asText()).isEqualTo("😀".repeat(128)); rebuilt.append(text);
                }
            }
            assertThat(++pages).isLessThanOrEqualTo(10); var next = body.path("nextCursor"); if (next.isNull()) break;
            assertThat(cursors.add(next.toString())).isTrue(); params = "limit=2&ordinal=" + next.path("ordinal").asInt() + "&offset=" + next.path("offset").asInt();
        }
        assertThat(rebuilt.toString()).isEqualTo(original); assertThat(ordinals).containsExactly(0, 0, 0, 0, 0, 5, 8);
        assertThat(jdbc.queryForObject("SELECT content FROM fragment_sentences WHERE fragment_id = ? AND ordinal = 0", String.class, item.fragmentId())).isEqualTo(original);
    }
    @Test void pagesAllKeyPointsSeparatelyWithUnchangedSourceSentenceIdAndTime() {
        var owner = login(); var item = fragment(owner); String original = "重点".repeat(1001);
        knowledge.insertPoint(item.fragmentId(), owner.id(), 0, new TingwuResult.KeyPoint(123, 2000, 8000, original));
        String params = "kind=KEY_POINT&limit=1"; var rebuilt = new StringBuilder(); int pages = 0;
        while (true) {
            var response = get(owner, item.fragmentId(), params); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); var body = response.getBody(); assertThat(body.path("kind").asText()).isEqualTo("KEY_POINT");
            var segment = body.path("items").get(0); assertThat(segment.path("sentenceId").asLong()).isEqualTo(123); assertThat(segment.path("startMs").asLong()).isEqualTo(2000); assertThat(segment.path("endMs").asLong()).isEqualTo(8000); assertThat(segment.path("speakerId").isNull()).isTrue();
            rebuilt.append(segment.path("text").asText()); assertThat(++pages).isLessThanOrEqualTo(3); var next = body.path("nextCursor"); if (next.isNull()) break;
            params = "kind=KEY_POINT&limit=1&ordinal=" + next.path("ordinal").asInt() + "&offset=" + next.path("offset").asInt();
        }
        assertThat(rebuilt.toString()).isEqualTo(original); assertThat(get(owner, item.fragmentId(), "").getBody().path("items")).isEmpty();
    }
    @Test void distinguishesPendingTranscriptionFromAnAvailableEmptyOrEndedTranscript() {
        var owner = login(); var item = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", null));
        var pending = get(owner, item.fragmentId(), "").getBody(); assertThat(pending.path("transcriptionAvailable").asBoolean()).isFalse(); assertThat(pending.path("items")).isEmpty(); assertThat(pending.path("nextCursor").isNull()).isTrue();
        knowledge.insert(item.fragmentId(), owner.id(), 0, null, "[]"); var empty = get(owner, item.fragmentId(), "ordinal=100").getBody(); assertThat(empty.path("transcriptionAvailable").asBoolean()).isTrue(); assertThat(empty.path("items")).isEmpty(); assertThat(empty.path("nextCursor").isNull()).isTrue();
    }
    @Test void authenticatesOwnerAndRejectsInvalidKindsNumbersAndOffsets() {
        var owner = login(); var foreign = login(); var item = fragment(owner); knowledge.insertSentence(item.fragmentId(), owner.id(), 0, new TingwuResult.Sentence("0", "0", 0, 0, 1000, "原文"));
        assertThat(http.getForEntity("/api/v1/fragments/" + item.fragmentId() + "/transcript", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(foreign, item.fragmentId(), "userId=" + owner.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(get(foreign, Long.MAX_VALUE, "").getBody().path("code").asText()).isEqualTo("FRAGMENT_NOT_FOUND");
        for (String params : List.of("kind=UNKNOWN", "ordinal=-1", "ordinal=2147483648", "offset=-1", "offset=16777216", "limit=21", "limit=0", "limit=NaN", "offset=3", "offset=2", "ordinal=1&offset=1")) {
            var response = get(owner, item.fragmentId(), params); assertThat(response.getStatusCode()).as(params).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_TRANSCRIPT");
        }
        assertThat(get(owner, item.fragmentId(), "ordinal=1").getBody().path("items")).isEmpty();
    }
    @Test void rejectsDanglingOffsetAtAnOrdinalGapAndPreservesMaximumOrdinalEnd() {
        var owner = login(); var item = fragment(owner);
        knowledge.insertSentence(item.fragmentId(), owner.id(), 5, new TingwuResult.Sentence("0", "0", 3, 0, 1000, "原文"));
        assertThat(get(owner, item.fragmentId(), "ordinal=3&offset=1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        knowledge.insertSentence(item.fragmentId(), owner.id(), Integer.MAX_VALUE, new TingwuResult.Sentence("0", "0", 4, 1000, 2000, "最后一句"));
        var ended = get(owner, item.fragmentId(), "ordinal=2147483647&limit=1").getBody(); assertThat(ended.path("items")).hasSize(1); assertThat(ended.path("nextCursor").isNull()).isTrue();
    }
    private SubmissionResponse fragment(Account owner) { var item = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", null)); knowledge.insert(item.fragmentId(), owner.id(), 20000, "摘要", "[]"); return item; }
    private ResponseEntity<JsonNode> get(Account owner, long id, String params) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return http.exchange("/api/v1/fragments/" + id + "/transcript" + (params.isEmpty() ? "" : "?" + params), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class); }
    private Account login() {
        var credentials = Map.of("username", "transcript_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id);
        var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText());
    }
    private record Account(long id, String token) { }
}
