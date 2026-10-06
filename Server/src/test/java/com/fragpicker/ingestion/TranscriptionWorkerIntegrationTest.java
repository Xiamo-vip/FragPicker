package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.tingwu.*;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class TranscriptionWorkerIntegrationTest {
    private static final String SCHEMA = "fragpicker_transcription_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    @Autowired TranscriptionJobStore store;
    @Autowired StoredMediaMapper media;
    @Autowired UserAccountMapper users;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> owned = new ArrayList<>();
    private OssMediaStorage storage;
    private TingwuClient client;
    private TingwuResultReader reader;
    private final OssProperties oss = new OssProperties(true, "test-bucket", "oss-cn-shenzhen.aliyuncs.com", 1048576, 1048576, Duration.ofMinutes(5));
    private final TingwuProperties tingwu = new TingwuProperties(true, "test-app", "cn", Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60), 1048576, Duration.ofHours(4));

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot create transcription test schema", failure); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?');
        String suffix = query < 0 ? "" : original.substring(query);
        String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + suffix);
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        for (String path : List.of("ingestion.worker", "ingestion.media-worker", "ingestion.transcription-worker", "integrations.oss", "integrations.parsevideo", "integrations.tingwu", "integrations.chat")) {
            registry.add("fragpicker." + path + ".enabled", () -> false);
        }
        registry.add("fragpicker.ingestion.transcription-worker.max-failures", () -> 3);
    }
    @AfterAll static void dropSchema() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"), System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD"));
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE IF EXISTS " + SCHEMA); }
    }
    @BeforeEach void adapters() {
        storage = mock(OssMediaStorage.class); client = mock(TingwuClient.class); reader = mock(TingwuResultReader.class);
        when(storage.signedGetForTranscription(anyLong(), anyLong(), anyString(), any())).thenReturn(new SignedMediaUrl(URI.create("https://test-bucket.oss-cn-shenzhen.aliyuncs.com/video?test-signature=hidden"), Instant.now().plusSeconds(14400)));
        when(client.create(any(), anyString())).thenReturn(task(TingwuTask.Status.ONGOING));
        when(client.get("cloud-id")).thenReturn(task(TingwuTask.Status.COMPLETED));
        when(reader.read(any())).thenReturn(result("cloud-id"));
    }
    @AfterEach void cleanup() { for (long id : owned) jdbc.update("DELETE FROM users WHERE id = ?", id); }

    @Test void persistsIntentBeforeCloudCallAndCommitsNormalizedResultsAtomically() {
        var item = pending();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT task_key FROM fragment_transcriptions WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo(invocation.getArgument(1));
            return task(TingwuTask.Status.ONGOING);
        }).when(client).create(any(), anyString());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return result("cloud-id");
        }).when(reader).read(any());
        assertThat(worker().runOnce()).isTrue(); assertThat(stage(item)).isEqualTo("TRANSCRIBING");
        assertThat(worker().runOnce()).isFalse(); due(item);
        assertThat(worker().runOnce()).isTrue(); assertThat(stage(item)).isEqualTo("KNOWLEDGE_PENDING");
        assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).isEqualTo("导数描述变化率。");
        assertThat(jdbc.queryForObject("SELECT keywords FROM fragment_knowledge WHERE fragment_id = ?", String.class, item.fragmentId())).contains("导数", "变化率");
        assertThat(count("fragment_sentences", item)).isEqualTo(2); assertThat(count("fragment_key_points", item)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT start_ms FROM fragment_sentences WHERE fragment_id = ? AND ordinal = 1", Long.class, item.fragmentId())).isEqualTo(1000);
        verify(client, times(1)).create(any(), anyString()); assertThat(worker().runOnce()).isFalse();
    }
    @Test void uncertainPostIsTerminalAndDoesNotCreateAgainAfterRestart() {
        var item = pending(); when(client.create(any(), anyString())).thenThrow(new TingwuFailure(TingwuFailure.Code.SUBMISSION_UNCERTAIN, false));
        worker().runOnce(); assertThat(stage(item)).isEqualTo("FAILED"); assertThat(error(item)).isEqualTo("TINGWU_SUBMISSION_UNCERTAIN");
        assertThat(count("fragment_transcriptions", item)).isEqualTo(1);
        due(item); assertThat(worker().runOnce()).isFalse(); verify(client, times(1)).create(any(), anyString());
    }
    @Test void abandonedIntentIsUncertainButLateIdReconcilesItsOwnTask() {
        var item = pending(); var first = store.claim().orElseThrow(); var key = store.beginSubmission(first).orElseThrow(); expire(item);
        assertThat(store.claim()).isEmpty(); assertThat(error(item)).isEqualTo("TINGWU_SUBMISSION_UNCERTAIN");
        assertThat(store.recordTask(first, "another-intent", "wrong-id")).isFalse();
        assertThat(store.recordTask(first, key, "cloud-id")).isTrue(); assertThat(stage(item)).isEqualTo("TRANSCRIBING");
        due(item); worker().runOnce(); assertThat(stage(item)).isEqualTo("KNOWLEDGE_PENDING"); verify(client, never()).create(any(), anyString());
    }
    @Test void expiredLeaseWithoutIntentIsSafeToReclaimAndWrongOwnerCannotWrite() {
        var item = pending(); var old = store.claim().orElseThrow(); expire(item); var next = store.claim().orElseThrow();
        assertThat(store.beginSubmission(old)).isEmpty(); assertThat(store.fail(old, "TINGWU_UNAVAILABLE", true)).isFalse();
        var wrong = new TranscriptionLease(next.jobId(), next.fragmentId(), next.userId() + 1, next.version(), next.owner());
        assertThat(store.beginSubmission(wrong)).isEmpty();
        var key = store.beginSubmission(next).orElseThrow(); assertThat(store.recordTask(wrong, key, "cloud-id")).isFalse();
        assertThat(store.recordTask(next, key, "cloud-id")).isTrue();
        assertThat(store.recordTask(next, key, "different-id")).isFalse(); assertThat(store.recordTask(next, key, "cloud-id")).isTrue();
    }
    @Test void resumesKnownTaskAfterLeaseExpiryAndRefreshesExpiredResultLinksWithoutPosting() {
        var item = pending(); worker().runOnce(); due(item); var old = store.claim().orElseThrow(); expire(item);
        when(reader.read(any())).thenThrow(new TingwuFailure(TingwuFailure.Code.RESULT_EXPIRED, true)).thenReturn(result("cloud-id"));
        worker().runOnce(); assertThat(stage(item)).isEqualTo("TRANSCRIBING"); assertThat(error(item)).isEqualTo("TINGWU_RESULT_EXPIRED");
        assertThat(store.complete(old, result("cloud-id"))).isFalse(); due(item); worker().runOnce();
        assertThat(stage(item)).isEqualTo("KNOWLEDGE_PENDING"); verify(client, times(1)).create(any(), anyString()); verify(client, times(2)).get("cloud-id");
    }
    @Test void confirmedRejectionAllowsBoundedRetryWhilePermanentRejectionStops() {
        var item = pending(); when(client.create(any(), anyString())).thenThrow(new TingwuFailure(TingwuFailure.Code.THROTTLED, true));
        for (int i = 0; i < 3; i++) { due(item); worker().runOnce(); }
        assertThat(stage(item)).isEqualTo("FAILED"); assertThat(error(item)).isEqualTo("TINGWU_THROTTLED");
        assertThat(count("fragment_transcriptions", item)).isZero(); verify(client, times(3)).create(any(), anyString());
        var denied = pending(); doThrow(new TingwuFailure(TingwuFailure.Code.ACCESS_DENIED, false)).when(client).create(any(), anyString());
        worker().runOnce(); assertThat(stage(denied)).isEqualTo("FAILED"); assertThat(error(denied)).isEqualTo("TINGWU_ACCESS_DENIED");
    }
    @Test void queryFailuresAreBoundedButOngoingTasksResetFailureBudget() {
        var item = pending(); worker().runOnce();
        when(client.get("cloud-id")).thenThrow(new TingwuFailure(TingwuFailure.Code.UNAVAILABLE, true));
        due(item); worker().runOnce(); assertThat(failures(item)).isEqualTo(1);
        doReturn(task(TingwuTask.Status.ONGOING)).when(client).get("cloud-id"); due(item); worker().runOnce();
        assertThat(failures(item)).isZero();
        when(client.get("cloud-id")).thenThrow(new TingwuFailure(TingwuFailure.Code.UNAVAILABLE, true));
        for (int i = 0; i < 3; i++) { due(item); worker().runOnce(); }
        assertThat(stage(item)).isEqualTo("FAILED"); verify(client, times(1)).create(any(), anyString());
    }
    @Test void ageLimitAndFailedCloudTaskStopWithoutReplacingThePaidTask() {
        var item = pending(); worker().runOnce();
        jdbc.update("UPDATE fragment_transcriptions SET submitted_at = '2000-01-01' WHERE fragment_id = ?", item.fragmentId());
        due(item); assertThat(worker().runOnce()).isFalse(); assertThat(error(item)).isEqualTo("TINGWU_TASK_TIMEOUT"); verify(client, never()).get(anyString());
        var failed = pending(); when(client.create(any(), anyString())).thenReturn(new TingwuTask("second-id", null, TingwuTask.Status.ONGOING, null, null, null, null));
        when(client.get("second-id")).thenReturn(new TingwuTask("second-id", null, TingwuTask.Status.FAILED, TingwuTask.FailureCategory.UNSUPPORTED_MEDIA, null, null, null));
        worker().runOnce(); due(failed); worker().runOnce(); assertThat(error(failed)).isEqualTo("TINGWU_TASK_UNSUPPORTED_MEDIA");
        assertThat(count("fragment_knowledge", failed)).isZero();
    }
    @Test void concurrentClaimSkipsLocksAndDoesNotDuplicateSubmission() throws Exception {
        var first = pending();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return store.claim(); });
            var b = pool.submit(() -> { start.await(); return store.claim(); }); start.countDown();
            assertThat(List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS)).stream().filter(Optional::isPresent)).hasSize(1);
        }
        expire(first); var second = pending();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM ingestion_jobs WHERE id = ? FOR UPDATE", Long.class, first.jobId());
            try (var pool = Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(store::claim).get(3, TimeUnit.SECONDS).orElseThrow().jobId()).isEqualTo(second.jobId());
            } catch (Exception failure) { throw new AssertionError("Claim should skip a locked row", failure); }
        });
    }
    @Test void insertFailureRollsBackEntireKnowledgeResultAndCanRetrySameTask() {
        var item = pending(); worker().runOnce(); due(item);
        jdbc.execute("CREATE TRIGGER reject_transcription_point BEFORE INSERT ON fragment_key_points FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test normalized result failure'");
        try {
            worker().runOnce(); assertThat(stage(item)).isEqualTo("TRANSCRIBING");
            assertThat(count("fragment_knowledge", item)).isZero(); assertThat(count("fragment_sentences", item)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_transcription_point"); }
        due(item); worker().runOnce(); assertThat(stage(item)).isEqualTo("KNOWLEDGE_PENDING");
        verify(client, times(1)).create(any(), anyString());
        jdbc.update("DELETE FROM users WHERE id = ?", owner(item)); assertThat(count("fragment_transcriptions", item)).isZero(); assertThat(count("fragment_knowledge", item)).isZero();
    }
    @Test void rejectsWrongResultTaskAndNoSpeechWithoutInventingKnowledge() {
        var item = pending(); worker().runOnce(); due(item); var lease = store.claim().orElseThrow();
        assertThatThrownBy(() -> store.complete(lease, result("another-task"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("fragment_knowledge", item)).isZero();
        assertThat(store.fail(lease, "TINGWU_INVALID_RESPONSE", false)).isTrue();
        var silent = pending(); when(client.create(any(), anyString())).thenReturn(new TingwuTask("silent-id", null, TingwuTask.Status.ONGOING, null, null, null, null));
        when(client.get("silent-id")).thenReturn(new TingwuTask("silent-id", null, TingwuTask.Status.COMPLETED, null, null, null, null));
        when(reader.read(any())).thenReturn(new TingwuResult("silent-id", 1000, List.of(), null, List.of(), List.of()));
        worker().runOnce(); due(silent); worker().runOnce(); assertThat(error(silent)).isEqualTo("TINGWU_NO_SPEECH");
    }

    record LiveJob(long owner, long fragment, String key, String bucket, String hash, long size, String taskKey, String taskId) { }

    @Test
    @EnabledIfEnvironmentVariable(named = "TRANSCRIPTION_WORKER_TEST_ENABLED", matches = "true")
    void realPrivateOssTingwuWorkerAndMySqlKnowledgePersistence() throws Exception {
        var json = new ObjectMapper();
        Path checkpoint = Path.of(System.getenv("TINGWU_WORKER_TEST_CHECKPOINT_PATH"));
        var credentials = new AliyunCredentialsProperties(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"), System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN"));
        var cloudOss = new OssProperties(true, System.getenv("OSS_BUCKET"), System.getenv("OSS_ENDPOINT"), 5 * 1024 * 1024, 1048576, Duration.ofMinutes(5));
        var cloudTingwu = new TingwuProperties(true, System.getenv("TINGWU_APP_KEY"), "cn", Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60), 16 * 1024 * 1024, Duration.ofHours(4));
        var ossClient = new OssConfiguration().ossClient(cloudOss, credentials);
        var liveStorage = new OssMediaStorage(ossClient, cloudOss, Clock.systemUTC());
        var config = new TingwuConfiguration();
        final LiveJob[] live = new LiveJob[1];
        boolean verified = false;
        try (var liveReader = config.tingwuResultReader(cloudTingwu, json)) {
            liveStorage.verifyPrivateBucket();
            if (Files.exists(checkpoint)) {
                live[0] = json.readValue(Files.readAllBytes(checkpoint), LiveJob.class);
                assertThat(live[0].bucket()).isEqualTo(cloudOss.bucket());
                if (live[0].taskKey() != null && live[0].taskId() == null) throw new AssertionError("Previous paid submission is uncertain; reconcile it before creating another task.");
            } else {
                Path speech = Path.of(System.getenv("TINGWU_TEST_MEDIA_PATH")); assertThat(Files.size(speech)).isBetween(1L, 5L * 1024 * 1024);
                long owner = IDS.incrementAndGet(); long fragment = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 100);
                var uploaded = liveStorage.put(owner, fragment, MediaKind.VIDEO, speech, "application/octet-stream");
                live[0] = new LiveJob(owner, fragment, uploaded.key(), uploaded.bucket(), uploaded.sha256(), uploaded.sizeBytes(), null, null);
                json.writeValue(checkpoint.toFile(), live[0]);
            }
            var saved = live[0];
            var user = new UserAccount(); String name = "live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            user.setId(saved.owner()); user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId());
            jdbc.update("""
                    INSERT INTO fragments (id, user_id, source_url, source_hash, source_host, business_date, business_zone, status, created_at)
                    VALUES (?, ?, 'https://b23.tv/synthetic-speech', ?, 'b23.tv', '2026-10-06', 'Asia/Shanghai', 'TRANSCRIPTION_PENDING', UTC_TIMESTAMP(3))
                    """, saved.fragment(), saved.owner(), saved.hash());
            jdbc.update("INSERT INTO ingestion_jobs (id, fragment_id, user_id, stage, next_attempt_at, created_at) VALUES (?, ?, ?, 'TRANSCRIPTION_PENDING', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", saved.fragment(), saved.fragment(), saved.owner());
            media.insert(new StoredMediaRecord(saved.fragment(), saved.owner(), MediaKind.VIDEO, saved.bucket(), saved.key(), saved.size(), saved.hash(), "application/octet-stream"));
            if (saved.taskId() != null) {
                jdbc.update("INSERT INTO fragment_transcriptions (fragment_id, user_id, task_key, task_id, submitted_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(3))", saved.fragment(), saved.owner(), saved.taskKey(), saved.taskId());
                jdbc.update("UPDATE ingestion_jobs SET stage = 'TRANSCRIBING' WHERE id = ?", saved.fragment());
            }
            var liveClient = new TingwuClient(config.tingwuSdk(cloudTingwu, credentials), cloudTingwu) {
                @Override public TingwuTask create(URI source, String taskKey) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(jdbc.queryForObject("SELECT task_key FROM fragment_transcriptions WHERE fragment_id = ?", String.class, saved.fragment())).isEqualTo(taskKey);
                    try {
                        live[0] = new LiveJob(saved.owner(), saved.fragment(), saved.key(), saved.bucket(), saved.hash(), saved.size(), taskKey, null);
                        json.writeValue(checkpoint.toFile(), live[0]);
                        var created = super.create(source, taskKey);
                        live[0] = new LiveJob(saved.owner(), saved.fragment(), saved.key(), saved.bucket(), saved.hash(), saved.size(), taskKey, created.id());
                        json.writeValue(checkpoint.toFile(), live[0]); return created;
                    } catch (java.io.IOException invalid) { throw new IllegalStateException("Cannot persist live cloud checkpoint"); }
                }
            };
            var liveWorker = new TranscriptionWorker(store, liveStorage, cloudOss, liveClient, liveReader, cloudTingwu);
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            String stage;
            do {
                jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", saved.fragment());
                liveWorker.runOnce();
                stage = jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, saved.fragment());
                System.out.println("Real transcription worker stage: " + stage);
                if (Set.of("KNOWLEDGE_PENDING", "FAILED").contains(stage)) break;
                if (System.nanoTime() >= deadline) throw new AssertionError("Cloud task still running; checkpoint retained for querying the same task.");
                Thread.sleep(15000);
            } while (true);
            String error = jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, saved.fragment());
            assertThat(stage).as("real worker stage, error=" + error).isEqualTo("KNOWLEDGE_PENDING");
            assertThat(jdbc.queryForObject("SELECT duration_ms FROM fragment_knowledge WHERE fragment_id = ? AND user_id = ?", Long.class, saved.fragment(), saved.owner())).isPositive();
            assertThat(jdbc.queryForObject("SELECT summary FROM fragment_knowledge WHERE fragment_id = ?", String.class, saved.fragment())).isNotBlank();
            assertThat(jdbc.queryForList("SELECT content FROM fragment_sentences WHERE fragment_id = ? ORDER BY ordinal", String.class, saved.fragment())).anyMatch(text -> text.contains("导数"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_sentences WHERE fragment_id = ? AND end_ms >= start_ms AND start_ms >= 0", Integer.class, saved.fragment())).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fragment_knowledge WHERE fragment_id = ? AND user_id = ?", Integer.class, saved.fragment(), saved.owner() + 1)).isZero();
            assertThat(jdbc.queryForObject("SELECT task_id FROM fragment_transcriptions WHERE fragment_id = ?", String.class, saved.fragment())).isEqualTo(live[0].taskId());
            verified = true; System.out.println("Real transcription worker: task ID, Chinese summary, keywords and timestamped transcript committed to MySQL.");
        } finally {
            try {
                if (verified) {
                    liveStorage.delete(live[0].owner(), live[0].fragment(), MediaKind.VIDEO, live[0].key());
                    Files.deleteIfExists(checkpoint);
                }
            } finally { ossClient.shutdown(); }
        }
    }

    private TranscriptionWorker worker() { return new TranscriptionWorker(store, storage, oss, client, reader, tingwu); }
    private SubmissionResponse pending() {
        var user = new UserAccount(); String name = "trans_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        user.setId(IDS.incrementAndGet()); user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder"); users.insert(user); owned.add(user.getId());
        var item = submissions.submit(user.getId(), UUID.randomUUID().toString(), new SubmissionRequest("https://b23.tv/" + UUID.randomUUID() + "/", "数学"));
        jdbc.update("UPDATE ingestion_jobs SET stage = 'TRANSCRIPTION_PENDING' WHERE id = ?", item.jobId());
        jdbc.update("UPDATE fragments SET status = 'TRANSCRIPTION_PENDING' WHERE id = ?", item.fragmentId());
        String hash = "a".repeat(64);
        media.insert(new StoredMediaRecord(item.fragmentId(), user.getId(), MediaKind.VIDEO, oss.bucket(), "users/" + user.getId() + "/fragments/" + item.fragmentId() + "/video/" + hash, 3, hash, "video/mp4"));
        return item;
    }
    private TingwuTask task(TingwuTask.Status status) { return new TingwuTask("cloud-id", null, status, null, null, null, null); }
    private TingwuResult result(String id) {
        return new TingwuResult(id, 2000, List.of(new TingwuResult.Sentence("0", "0", 0, 0, 900, "导数描述变化率。"),
                new TingwuResult.Sentence("0", "0", 1, 1000, 2000, "通过切线理解函数。")), "导数描述变化率。", List.of("导数", "变化率"),
                List.of(new TingwuResult.KeyPoint(0, 0, 900, "导数描述变化率。")));
    }
    private long owner(SubmissionResponse item) { return jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, item.fragmentId()); }
    private String stage(SubmissionResponse item) { return jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private String error(SubmissionResponse item) { return jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private int failures(SubmissionResponse item) { return jdbc.queryForObject("SELECT transcription_failures FROM ingestion_jobs WHERE id = ?", Integer.class, item.jobId()); }
    private int count(String table, SubmissionResponse item) {
        if (!Set.of("fragment_transcriptions", "fragment_knowledge", "fragment_sentences", "fragment_key_points").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE fragment_id = ?", Integer.class, item.fragmentId());
    }
    private void due(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", item.jobId()); }
    private void expire(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)) WHERE id = ?", item.jobId()); }
}
