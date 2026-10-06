package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class SubmissionIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void persistsRecordAndJobTogetherAndReplaysTheSameRequest() throws Exception {
        var user = account();
        var key = UUID.randomUUID().toString();
        var response = submit(user.token(), key, "课程 https://v.douyin.com/example/", "想学导数");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode result = json.readTree(response.getBody());
        long id = result.path("fragmentId").asLong();
        assertThat(result.path("status").asText()).isEqualTo("QUEUED");
        assertThat(result.path("businessDate").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        assertThat(result.path("duplicate").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT note FROM fragments WHERE id = ? AND user_id = ?", String.class, id, user.id())).isEqualTo("想学导数");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE fragment_id = ? AND user_id = ?", Integer.class, id, user.id())).isEqualTo(1);
        assertThat(submit(user.token(), key.toUpperCase(), "https://v.douyin.com/example/#view", "想学导数").getBody()).isEqualTo(response.getBody());
        var conflict = submit(user.token(), key, "https://v.douyin.com/different/", "想学导数");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    void deduplicatesPerUserAndDoesNotReplaceOriginalNoteOrDate() throws Exception {
        var firstUser = account();
        var secondUser = account();
        String link = "https://www.bilibili.com/video/example/";
        var first = json.readTree(submit(firstUser.token(), UUID.randomUUID().toString(), link, "原始备注").getBody());
        var duplicate = json.readTree(submit(firstUser.token(), UUID.randomUUID().toString(), link, "后来的备注").getBody());
        var separate = json.readTree(submit(secondUser.token(), UUID.randomUUID().toString(), link, "另一个用户").getBody());
        assertThat(duplicate.path("duplicate").asBoolean()).isTrue();
        assertThat(duplicate.path("fragmentId").asLong()).isEqualTo(first.path("fragmentId").asLong());
        assertThat(separate.path("fragmentId").asLong()).isNotEqualTo(first.path("fragmentId").asLong());
        assertThat(jdbc.queryForObject("SELECT note FROM fragments WHERE id = ?", String.class, first.path("fragmentId").asLong())).isEqualTo("原始备注");
    }

    @Test
    void concurrentSubmissionsCreateOneJobAndOneIdempotencyRecord() throws Exception {
        var user = account();
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submit(user.token(), key, "https://xhslink.com/example", ""));
            var second = executor.submit(() -> submit(user.token(), key, "https://xhslink.com/example", ""));
            assertThat(first.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submission_requests WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    void rollsBackTheFragmentWhenJobInsertionFails() {
        var user = account();
        String trigger = "reject_job_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON ingestion_jobs FOR EACH ROW BEGIN "
                + "IF NEW.user_id = " + user.id() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test job failure'; END IF; END");
        try {
            var failed = submit(user.token(), UUID.randomUUID().toString(), "https://v.douyin.com/rollback/", "测试事务");
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id = ?", Integer.class, user.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs WHERE user_id = ?", Integer.class, user.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submission_requests WHERE user_id = ?", Integer.class, user.id())).isZero();
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
    }

    @Test
    void rejectsMissingAuthenticationInvalidKeyAndUnsupportedLinksWithoutWritingRows() {
        var unauthenticated = http.postForEntity("/api/v1/fragments", Map.of("shareText", "https://v.douyin.com/a"), String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var user = account();
        assertThat(submit(user.token(), "bad-key", "https://v.douyin.com/a", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(user.token(), UUID.randomUUID().toString(), "http://127.0.0.1/private", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragments WHERE user_id = ?", Integer.class, user.id())).isZero();
    }

    private ResponseEntity<String> submit(String token, String key, String share, String note) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/v1/fragments", new HttpEntity<>(Map.of("shareText", share, "note", note, "userId", -1), headers), String.class);
    }

    private Account account() {
        var username = "feed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        var credentials = Map.of("username", username, "password", "Integration-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register", credentials, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var response = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Account(response.getBody().path("user").path("id").asLong(), response.getBody().path("accessToken").asText());
    }

    private record Account(long id, String token) { }
}
