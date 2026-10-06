package com.fragpicker.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.ingestion.*;
import com.fragpicker.user.UserAccountMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class CalendarIntegrationTest {
    private static final String SCHEMA = "fragpicker_calendar_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired TestRestTemplate http; @Autowired JdbcTemplate jdbc; @Autowired SubmissionService submissions;
    @Autowired UserAccountMapper users; @Autowired FragmentRecordMapper fragments; @Autowired IngestionJobMapper jobs; @Autowired SubmissionRecordMapper requests; @Autowired ShareLinkResolver links; @Autowired PlatformTransactionManager transactions;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        catch (java.sql.SQLException invalid) { throw new IllegalStateException("Cannot create calendar test schema", invalid); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?'); String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + (query < 0 ? "" : original.substring(query)));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME")); registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD")); registry.add("fragpicker.integrations.chat.enabled", () -> false);
    }
    @AfterAll static void drop() throws Exception { try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); } }
    @AfterEach void clean() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }

    @Test void includesEveryCalendarDayAndHandlesLeapYearAndMysqlDateUpperBoundary() {
        var owner = login(); var leap = get(owner, "?month=2024-02"); assertThat(leap.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(leap.getHeaders().getCacheControl()).isEqualTo("no-store"); assertThat(leap.getBody().path("days")).hasSize(29); assertThat(leap.getBody().path("total").asLong()).isZero();
        assertThat(leap.getBody().path("days").get(28).path("date").asText()).isEqualTo("2024-02-29"); for (var day : leap.getBody().path("days")) for (String count : List.of("total", "ready", "processing", "failed")) assertThat(day.path(count).asLong()).isZero();
        assertThat(get(owner, "?month=2025-02").getBody().path("days")).hasSize(28); seed(owner, "9999-12-31", "READY"); var last = get(owner, "?month=9999-12").getBody(); assertThat(last.path("days")).hasSize(31); assertThat(last.path("days").get(30).path("ready").asLong()).isEqualTo(1); assertThat(last.path("total").asLong()).isEqualTo(1); assertThat(get(owner, "?month=1000-01").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void countsOwnedRecordsOnceAcrossAllPhasesAndRestrictsMonth() {
        var owner = login(); var foreign = login(); var saved = seed(owner, "2026-10-06", "READY");
        var duplicate = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest(jdbc.queryForObject("SELECT source_url FROM fragments WHERE id = ?", String.class, saved.fragmentId()), null)); assertThat(duplicate.duplicate()).isTrue();
        seed(owner, "2026-10-06", "FAILED"); for (String stage : List.of("QUEUED", "MEDIA_PENDING", "TRANSCRIPTION_PENDING", "KNOWLEDGE_PENDING", "INDEX_PENDING")) seed(owner, "2026-10-06", stage);
        seed(owner, "2026-10-07", "READY"); seed(owner, "2026-09-30", "READY"); seed(owner, "2026-11-01", "READY"); seed(foreign, "2026-10-06", "READY");
        var body = get(owner, "?month=2026-10&userId=" + foreign.id()).getBody(); var day = body.path("days").get(5); assertThat(body.path("total").asLong()).isEqualTo(8); assertThat(day.path("total").asLong()).isEqualTo(7); assertThat(day.path("ready").asLong()).isEqualTo(1); assertThat(day.path("failed").asLong()).isEqualTo(1); assertThat(day.path("processing").asLong()).isEqualTo(5); assertThat(body.toString()).doesNotContain("userId", "sourceUrl", "note", "objectKey");
        assertThat(get(foreign, "?month=2026-10").getBody().path("total").asLong()).isEqualTo(1);
    }
    @Test void realSubmissionClockPlacesShanghaiMidnightOnCorrectDayAndKeepsStoredDate() {
        var owner = login(); var before = submitAt(owner, "2026-10-31T15:59:59.999Z"); var after = submitAt(owner, "2026-10-31T16:00:00Z"); assertThat(before.businessDate()).isEqualTo(LocalDate.of(2026,10,31)); assertThat(after.businessDate()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(get(owner, "?month=2026-10").getBody().path("days").get(30).path("total").asLong()).isEqualTo(1); assertThat(get(owner, "?month=2026-11").getBody().path("days").get(0).path("total").asLong()).isEqualTo(1);
        jdbc.update("UPDATE users SET business_zone = 'UTC' WHERE id = ?", owner.id()); assertThat(get(owner, "?month=2026-11").getBody().path("total").asLong()).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT business_zone FROM fragments WHERE id = ?", String.class, after.fragmentId())).isEqualTo("Asia/Shanghai");
    }
    @Test void validatesExplicitMonthAndRejectsMissingOrRevokedLogin() {
        var owner = login(); assertThat(http.getForEntity("/api/v1/calendar?month=2026-10", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String query : List.of("", "?month=", "?month=2026-1", "?month=2026-13", "?month=0999-12", "?month=10000-01", "?month=2026-10-01", "?month=invalid")) { var invalid = get(owner, query); assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_CALENDAR"); }
        assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers(owner)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); assertThat(get(owner, "?month=2026-10").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    private SubmissionResponse submitAt(Account owner, String instant) { var service = new SubmissionService(users, fragments, jobs, requests, links, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)); return new TransactionTemplate(transactions).execute(status -> service.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null))); }
    private SubmissionResponse seed(Account owner, String date, String stage) { var saved = submissions.submit(owner.id(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID(), null)); jdbc.update("UPDATE fragments SET business_date = ?, status = ? WHERE id = ?", LocalDate.parse(date), stage, saved.fragmentId()); return saved; }
    private ResponseEntity<JsonNode> get(Account owner, String query) { return http.exchange("/api/v1/calendar" + query, HttpMethod.GET, new HttpEntity<>(headers(owner)), JsonNode.class); }
    private HttpHeaders headers(Account owner) { var headers = new HttpHeaders(); headers.setBearerAuth(owner.token()); return headers; }
    private Account login() { var credentials = Map.of("username", "calendar_" + UUID.randomUUID().toString().replace("-", "").substring(0,16), "password", "Integration-password-123"); var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED); long id = registered.getBody().path("id").asLong(); owned.add(id); var login = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK); return new Account(id, login.getBody().path("accessToken").asText()); }
    private record Account(long id, String token) { @Override public String toString() { return "Account[REDACTED]"; } }
}
