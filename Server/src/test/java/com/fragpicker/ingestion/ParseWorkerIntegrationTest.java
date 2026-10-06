package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.parsevideo.*;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ParseWorkerIntegrationTest {
    private static final String SCHEMA = "fragpicker_worker_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private ParseJobStore store;
    @Autowired private SubmissionService submissions;
    @Autowired private FragmentStatusService status;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserAccountMapper users;
    @Autowired private PlatformTransactionManager transactions;
    private final List<Long> ownedUsers = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer fixture;
    private volatile int httpStatus = 200;
    private volatile String body = """
            {"code":200,"data":{"video_url":"https://cdn.example.com/video.mp4?sign=temporary",
              "cover_url":"https://cdn.example.com/cover.jpg","title":"导数学习🧮",
              "author":{"uid":123,"name":"数学老师","avatar":"https://cdn.example.com/avatar.jpg"}}}
            """;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"),
                System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot create isolated worker test schema", failure); }
        String original = System.getenv("DB_TEST_URL");
        int query = original.indexOf('?');
        String suffix = query < 0 ? "" : original.substring(query);
        String address = query < 0 ? original : original.substring(0, query);
        String isolated = address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + suffix;
        registry.add("spring.datasource.url", () -> isolated);
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.ingestion.worker.enabled", () -> false);
    }

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"),
                System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD"));
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }

    @BeforeEach
    void startFixture() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/video/share/url/parse", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        fixture.start();
    }

    @AfterEach
    void cleanFixtures() {
        if (fixture != null) fixture.stop(0);
        for (long userId : ownedUsers) jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void parsesOutsideTransactionAndPersistsMetadataWithTheNextStage() {
        var submitted = queued("https://v.douyin.com/metadata/");
        assertThat(worker(fixtureUrl()).runOnce()).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(stage(submitted)).isEqualTo("MEDIA_PENDING");
        assertThat(jdbc.queryForMap("SELECT * FROM fragment_video_metadata WHERE fragment_id = ?", submitted.fragmentId()))
                .containsEntry("title", "导数学习🧮").containsEntry("author_name", "数学老师")
                .containsEntry("author_uid", "123").containsEntry("video_url", "https://cdn.example.com/video.mp4?sign=temporary");
        var queried = status.get(ownedUsers.getFirst(), submitted.fragmentId());
        assertThat(queried.status()).isEqualTo("MEDIA_PENDING");
        assertThat(queried.attemptCount()).isEqualTo(1);
        assertThat(queried.errorCode()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM ingestion_jobs WHERE id = ?", String.class, submitted.jobId())).isNull();
    }

    @Test
    void schedulesTransientFailuresWithBackoffThenStopsAtTheAttemptLimit() {
        var submitted = queued("https://b23.tv/retry/");
        httpStatus = 503; body = "{}";
        var worker = worker(fixtureUrl());
        assertThat(worker.runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, submitted.jobId()))
                .isEqualTo("PARSE_PROVIDER_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), next_attempt_at) FROM ingestion_jobs WHERE id = ?", Integer.class, submitted.jobId()))
                .isBetween(8, 10);
        // This row is ineligible until its persisted due time, even after restarting the worker.
        assertThat(new ParseVideoWorker(store, client(fixtureUrl())).runOnce()).isFalse();
        for (int attempt = 2; attempt <= 3; attempt++) {
            makeDue(submitted);
            assertThat(worker.runOnce()).isTrue();
        }
        assertThat(calls).hasValue(3);
        assertThat(stage(submitted)).isEqualTo("FAILED");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void stopsPermanentFailureWithoutSavingAnyMetadata() {
        var submitted = queued("https://v.douyin.com/image-post/");
        body = "{\"code\":200,\"data\":{\"images\":[{}]}}";
        assertThat(worker(fixtureUrl()).runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, submitted.jobId()))
                .isEqualTo("PARSE_UNSUPPORTED_CONTENT");
        assertThat(metadataCount(submitted)).isZero();
        assertThat(worker(fixtureUrl()).runOnce()).isFalse();
    }

    @Test
    void concurrentWorkersClaimOnlyOnceAndExpiredWorkerCannotOverwriteNewResults() throws Exception {
        var submitted = queued("https://b23.tv/lease/");
        ParseLease first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return store.claim(); });
            var b = pool.submit(() -> { start.await(); return store.claim(); });
            start.countDown();
            var claimed = List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
            assertThat(claimed.stream().filter(Optional::isPresent)).hasSize(1);
            first = claimed.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        }
        expire(submitted);
        assertThat(store.complete(first, parsed("Expired result"))).isFalse();
        var next = store.claim().orElseThrow();
        assertThat(next.version()).isGreaterThan(first.version());
        assertThat(next.owner()).isNotEqualTo(first.owner());
        assertThat(store.complete(next, parsed("正确的新结果"))).isTrue();
        assertThat(store.fail(first, "PARSE_TIMEOUT", true)).isFalse();
        assertThat(store.complete(first, parsed("Stale result"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT title FROM fragment_video_metadata WHERE fragment_id = ?", String.class, submitted.fragmentId()))
                .isEqualTo("正确的新结果");
        assertThat(stage(submitted)).isEqualTo("MEDIA_PENDING");
    }

    @Test
    void skipsLockedRowsSoAnotherWorkerCanClaimTheNextJob() throws Exception {
        var first = queued("https://b23.tv/locked/");
        var second = queued("https://b23.tv/unlocked/");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM ingestion_jobs WHERE id = ? FOR UPDATE", Long.class, first.jobId());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var claim = pool.submit(store::claim);
                try { assertThat(claim.get(3, TimeUnit.SECONDS).orElseThrow().jobId()).isEqualTo(second.jobId()); }
                catch (Exception failure) { throw new AssertionError("Queue claim should skip the locked row", failure); }
            }
        });
        assertThat(stage(first)).isEqualTo("QUEUED");
    }

    @Test
    void crashRecoveryExhaustsAbandonedLeasesWithoutCallingProviderAgain() {
        var submitted = queued("https://b23.tv/abandoned/");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(store.claim().orElseThrow().attemptCount()).isEqualTo(attempt);
            expire(submitted);
        }
        assertThat(store.claim()).isEmpty();
        assertThat(stage(submitted)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, submitted.jobId()))
                .isEqualTo("PARSE_LEASE_EXPIRED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void metadataWriteFailureRollsBackStageTransitionAndRemainsRetryable() {
        var submitted = queued("https://b23.tv/metadata-rollback/");
        var lease = store.claim().orElseThrow();
        String trigger = "reject_meta_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fragment_video_metadata FOR EACH ROW BEGIN "
                + "IF NEW.fragment_id = " + submitted.fragmentId() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test metadata failure'; END IF; END");
        try {
            assertThatThrownBy(() -> store.complete(lease, parsed("transaction"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(stage(submitted)).isEqualTo("PARSING");
            assertThat(metadataCount(submitted)).isZero();
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertThat(store.complete(lease, parsed("retry same lease"))).isTrue();
        assertThat(stage(submitted)).isEqualTo("MEDIA_PENDING");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PARSEVIDEO_TEST_BASE_URL", matches = ".+")
    void persistsMetadataFromTheUsersRealDeployedParseService() {
        var submitted = queued("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertThat(worker(System.getenv("PARSEVIDEO_TEST_BASE_URL")).runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("MEDIA_PENDING");
        var saved = jdbc.queryForMap("SELECT title, author_name, video_url, cover_url FROM fragment_video_metadata WHERE fragment_id = ?", submitted.fragmentId());
        for (String field : List.of("title", "author_name", "video_url", "cover_url")) {
            // Avoid including live signed media URLs in assertion messages or logs.
            assertThat(saved.get(field) instanceof String && !((String) saved.get(field)).isBlank()).as(field + " is populated").isTrue();
        }
    }

    private SubmissionResponse queued(String source) {
        var user = new UserAccount();
        String name = "worker_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-only-placeholder");
        users.insert(user); ownedUsers.add(user.getId());
        var submitted = submissions.submit(user.getId(), UUID.randomUUID().toString(), new SubmissionRequest(source, "学习资料"));
        makeDue(submitted);
        return submitted;
    }
    private void makeDue(SubmissionResponse submitted) {
        // Persist a due time without sleeping; this schema contains only worker fixtures.
        jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", submitted.jobId());
    }
    private void expire(SubmissionResponse submitted) {
        jdbc.update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)) WHERE id = ?", submitted.jobId());
    }
    private String stage(SubmissionResponse submitted) {
        return jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, submitted.jobId());
    }
    private int metadataCount(SubmissionResponse submitted) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fragment_video_metadata WHERE fragment_id = ?", Integer.class, submitted.fragmentId());
    }
    private ParsedVideo parsed(String title) { return new ParsedVideo(title, URI.create("https://cdn.example.com/a.mp4"), null, null, null, null); }
    private String fixtureUrl() { return "http://127.0.0.1:" + fixture.getAddress().getPort(); }
    private ParseVideoWorker worker(String baseUrl) { return new ParseVideoWorker(store, client(baseUrl)); }
    private ParseVideoClient client(String baseUrl) {
        var props = new ParseVideoProperties(true, baseUrl, Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", "");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout()); factory.setReadTimeout(props.readTimeout());
        return new ParseVideoClient(RestClient.builder().requestFactory(factory).build(), props, new ObjectMapper());
    }
}
