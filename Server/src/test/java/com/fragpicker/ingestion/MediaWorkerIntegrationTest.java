package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fragpicker.integration.media.*;
import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.parsevideo.*;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class MediaWorkerIntegrationTest {
    private static final String SCHEMA = "fragpicker_media_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    @Autowired private MediaJobStore store;
    @Autowired private ParseJobStore parseStore;
    @Autowired private StoredMediaMapper media;
    @Autowired private VideoMetadataMapper metadata;
    @Autowired private SubmissionService submissions;
    @Autowired private UserAccountMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @TempDir Path directory;
    private final List<Long> ownedUsers = new ArrayList<>();
    private SafeMediaDownloader downloader;
    private OssMediaStorage storage;
    private ParseVideoClient parser;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"),
                System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot create media test schema", failure); }
        String original = System.getenv("DB_TEST_URL"); int query = original.indexOf('?');
        String suffix = query < 0 ? "" : original.substring(query);
        String address = query < 0 ? original : original.substring(0, query);
        registry.add("spring.datasource.url", () -> address.substring(0, address.lastIndexOf('/') + 1) + SCHEMA + suffix);
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.ingestion.worker.enabled", () -> false);
        registry.add("fragpicker.ingestion.media-worker.enabled", () -> false);
        registry.add("fragpicker.integrations.oss.enabled", () -> false);
        registry.add("fragpicker.integrations.parsevideo.enabled", () -> false);
    }
    @AfterAll static void dropSchema() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("DB_TEST_URL"),
                System.getenv("DB_TEST_USERNAME"), System.getenv("DB_TEST_PASSWORD")); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }
    @BeforeEach void adapters() throws Exception {
        downloader = mock(SafeMediaDownloader.class); storage = mock(OssMediaStorage.class); parser = mock(ParseVideoClient.class);
        when(downloader.download(any(), any(), any())).thenAnswer(invocation -> {
            var file = Files.createTempFile(directory, "fixture-", ".bin");
            Files.write(file, new byte[]{1, 2, 3});
            MediaKind kind = invocation.getArgument(2);
            return new DownloadedMedia(file, 3, kind == MediaKind.VIDEO ? "video/mp4" : "image/png");
        });
        when(storage.put(anyLong(), anyLong(), any(), any(), anyString())).thenAnswer(invocation -> {
            long owner = invocation.getArgument(0); long fragment = invocation.getArgument(1);
            return uploaded(owner, fragment, invocation.getArgument(2));
        });
    }
    @AfterEach void cleanup() throws Exception {
        for (long id : ownedUsers) jdbc.update("DELETE FROM users WHERE id = ?", id);
        try (var files = Files.list(directory)) { assertThat(files.count()).as("temporary download cleanup").isZero(); }
    }
    @Test void commitsVideoAndCoverBeforeAdvancingAndDoesNoIoInsideTransactions() throws Exception {
        var submitted = pending(true);
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return uploaded(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(storage).put(anyLong(), anyLong(), any(), any(), anyString());
        assertThat(worker().runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("TRANSCRIPTION_PENDING");
        assertThat(count(submitted)).isEqualTo(2);
        assertThat(media.find(submitted.fragmentId(), owner(submitted), MediaKind.VIDEO).contentType()).isEqualTo("video/mp4");
        assertThat(media.find(submitted.fragmentId(), owner(submitted) + 1, MediaKind.VIDEO)).isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM ingestion_jobs WHERE id = ?", Integer.class, submitted.jobId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT media_attempt_count FROM ingestion_jobs WHERE id = ?", Integer.class, submitted.jobId())).isEqualTo(1);
        verifyNoInteractions(parser);
        assertThat(worker().runOnce()).isFalse();
    }
    @Test void restartsFromVideoCheckpointWhenCoverUploadFails() {
        var submitted = pending(true);
        doThrow(new OssStorageFailure(OssStorageFailure.Code.UNAVAILABLE, true)).when(storage)
                .put(anyLong(), anyLong(), eq(MediaKind.COVER), any(), anyString());
        assertThat(worker().runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("MEDIA_PENDING"); assertThat(count(submitted)).isEqualTo(1);
        assertThat(worker().runOnce()).isFalse();
        doReturn(uploaded(owner(submitted), submitted.fragmentId(), MediaKind.COVER)).when(storage)
                .put(anyLong(), anyLong(), eq(MediaKind.COVER), any(), anyString());
        due(submitted);
        assertThat(new MediaStorageWorker(store, downloader, storage, parser).runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("TRANSCRIPTION_PENDING");
        verify(downloader, times(1)).download(any(), any(), eq(MediaKind.VIDEO));
        verify(storage, times(1)).put(anyLong(), anyLong(), eq(MediaKind.VIDEO), any(), anyString());
        verify(downloader, times(2)).download(any(), any(), eq(MediaKind.COVER));
    }
    @Test void refreshesExpiredSourcesOnceAndPersistsFreshMetadata() {
        var submitted = pending(false);
        doThrow(new MediaDownloadFailure(MediaDownloadFailure.Code.SOURCE_EXPIRED, true)).when(downloader)
                .download(eq(URI.create("https://cdn.example.com/old.mp4")), any(), eq(MediaKind.VIDEO));
        when(parser.parse(anyString())).thenReturn(parsed(false, "new"));
        assertThat(worker().runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("TRANSCRIPTION_PENDING");
        verify(parser, times(1)).parse(anyString());
        assertThat(metadata.find(submitted.fragmentId(), owner(submitted)).title()).isEqualTo("new");
    }
    @Test void refreshLoopIsBoundedAndMediaAttemptBudgetIsIndependentOfParseAttempts() {
        var submitted = pending(false);
        jdbc.update("UPDATE ingestion_jobs SET attempt_count = 3 WHERE id = ?", submitted.jobId());
        doThrow(new MediaDownloadFailure(MediaDownloadFailure.Code.SOURCE_EXPIRED, true)).when(downloader).download(any(), any(), any());
        when(parser.parse(anyString())).thenReturn(parsed(false, "still-expired"));
        for (int i = 1; i <= 3; i++) { due(submitted); assertThat(worker().runOnce()).isTrue(); }
        assertThat(stage(submitted)).isEqualTo("FAILED");
        assertThat(error(submitted)).isEqualTo("MEDIA_SOURCE_EXPIRED");
        verify(parser, times(3)).parse(anyString()); verify(downloader, times(6)).download(any(), any(), any());
        assertThat(worker().runOnce()).isFalse();
    }
    @Test void permanentDownloadFailureStopsWithoutUploadOrReparse() {
        var submitted = pending(false);
        doThrow(new MediaDownloadFailure(MediaDownloadFailure.Code.TARGET_REJECTED, false)).when(downloader).download(any(), any(), any());
        assertThat(worker().runOnce()).isTrue();
        assertThat(stage(submitted)).isEqualTo("FAILED"); assertThat(error(submitted)).isEqualTo("MEDIA_TARGET_REJECTED");
        verifyNoInteractions(storage, parser);
    }
    @Test void concurrentClaimAndExpiredLeaseCannotCheckpointOrOverwriteNewMetadata() throws Exception {
        var submitted = pending(false); MediaLease first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return store.claim(); });
            var b = pool.submit(() -> { start.await(); return store.claim(); }); start.countDown();
            var results = List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            first = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        }
        expire(submitted);
        assertThat(store.checkpoint(first, MediaKind.VIDEO, uploaded(first.userId(), first.fragmentId(), MediaKind.VIDEO))).isFalse();
        var next = store.claim().orElseThrow();
        assertThat(store.refreshMetadata(first, parsed(false, "stale"))).isFalse();
        assertThat(store.fail(first, "MEDIA_TIMEOUT", true)).isFalse();
        assertThat(store.complete(first)).isFalse();
        var uploaded = uploaded(next.userId(), next.fragmentId(), MediaKind.VIDEO);
        assertThat(store.checkpoint(next, MediaKind.VIDEO, uploaded)).isTrue();
        assertThat(store.checkpoint(next, MediaKind.VIDEO, uploaded)).isTrue();
        assertThat(store.complete(next)).isTrue(); assertThat(count(submitted)).isEqualTo(1);
        assertThat(metadata.find(next.fragmentId(), next.userId()).title()).isEqualTo("old");
    }
    @Test void skipsLockedJobsAndExhaustsAbandonedLeases() {
        var first = pending(false); var second = pending(false);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM ingestion_jobs WHERE id = ? FOR UPDATE", Long.class, first.jobId());
            try (var pool = Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(store::claim).get(3, TimeUnit.SECONDS).orElseThrow().jobId()).isEqualTo(second.jobId());
            } catch (Exception failure) { throw new AssertionError("Claim should skip locked row", failure); }
        });
        for (int i = 1; i <= 3; i++) { assertThat(store.claim().orElseThrow().jobId()).isEqualTo(first.jobId()); expire(first); }
        assertThat(store.claim()).isEmpty(); assertThat(stage(first)).isEqualTo("FAILED");
        assertThat(error(first)).isEqualTo("MEDIA_LEASE_EXPIRED");
    }
    @Test void databaseFailureRollsBackCheckpointAndRejectsPrematureCompletionAndWrongOwnerKey() {
        var submitted = pending(false); var lease = store.claim().orElseThrow();
        assertThatThrownBy(() -> store.complete(lease)).hasMessageContaining("not been saved");
        assertThatThrownBy(() -> store.checkpoint(lease, MediaKind.VIDEO, uploaded(lease.userId() + 1, lease.fragmentId(), MediaKind.VIDEO)))
                .isInstanceOf(IllegalArgumentException.class);
        String trigger = "reject_media_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON fragment_stored_media FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Test media checkpoint failure'");
        try {
            assertThatThrownBy(() -> store.checkpoint(lease, MediaKind.VIDEO, uploaded(lease.userId(), lease.fragmentId(), MediaKind.VIDEO)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count(submitted)).isZero(); assertThat(stage(submitted)).isEqualTo("MEDIA_SAVING");
        } finally { jdbc.execute("DROP TRIGGER " + trigger); }
        assertThat(store.checkpoint(lease, MediaKind.VIDEO, uploaded(lease.userId(), lease.fragmentId(), MediaKind.VIDEO))).isTrue();
        assertThat(store.complete(lease)).isTrue();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEDIA_WORKER_TEST_ENABLED", matches = "true")
    void realParseDownloadPrivateOssAndDatabaseCheckpoints() throws Exception {
        var submitted = queued("https://www.bilibili.com/video/BV1GJ411x7h7");
        var parseProperties = new ParseVideoProperties(true, System.getenv("MEDIA_WORKER_TEST_PARSEVIDEO_BASE_URL"),
                Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", "");
        var liveParser = new ParseVideoConfiguration().parseVideoClient(parseProperties, new ObjectMapper());
        assertThat(new ParseVideoWorker(parseStore, liveParser).runOnce()).isTrue();
        var ossProperties = new OssProperties(true, System.getenv("OSS_BUCKET"), System.getenv("OSS_ENDPOINT"),
                128L * 1024 * 1024, 10L * 1024 * 1024, Duration.ofMinutes(5));
        var credentials = new AliyunCredentialsProperties(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"),
                System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN"));
        // Production OSS client (V4, CRC, no retries). Track only objects created in this unique namespace.
        var client = new OssConfiguration().ossClient(ossProperties, credentials);
        var uploaded = new ArrayList<StoredMedia>();
        var liveStorage = new OssMediaStorage(client, ossProperties, Clock.systemUTC()) {
            @Override public StoredMedia put(long userId, long fragmentId, MediaKind kind, Path file, String contentType) {
                var result = super.put(userId, fragmentId, kind, file, contentType); uploaded.add(result); return result;
            }
        };
        var downloadProperties = new MediaDownloadProperties(directory, 128L * 1024 * 1024, 10L * 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), 3);
        try (var liveDownloader = new MediaDownloadConfiguration().safeMediaDownloader(downloadProperties)) {
            liveStorage.verifyPrivateBucket();
            assertThat(new MediaStorageWorker(store, liveDownloader, liveStorage, liveParser).runOnce()).isTrue();
            assertThat(stage(submitted)).as("real media task stage, error=" + error(submitted)).isEqualTo("TRANSCRIPTION_PENDING");
            assertThat(count(submitted)).isEqualTo(2);
            for (var kind : MediaKind.values()) {
                var saved = media.find(submitted.fragmentId(), owner(submitted), kind);
                assertThat(saved.bucket()).isEqualTo(ossProperties.bucket()); assertThat(saved.sizeBytes()).isPositive();
                var cloud = client.getObjectMetadata(saved.bucket(), saved.objectKey());
                assertThat(cloud.getContentLength()).isEqualTo(saved.sizeBytes());
                assertThat(cloud.getUserMetadata().get("sha256")).isEqualTo(saved.sha256());
                assertThat(client.getObjectAcl(saved.bucket(), saved.objectKey()).getPermission()).isEqualTo(com.aliyun.oss.model.ObjectPermission.Private);
            }
            System.out.println("Real media worker: video and cover privately stored; checkpoints committed.");
        } finally {
            try {
                for (var saved : uploaded) {
                    var kind = saved.contentType().startsWith("image/") ? MediaKind.COVER : MediaKind.VIDEO;
                    liveStorage.delete(owner(submitted), submitted.fragmentId(), kind, saved.key());
                }
            } finally { client.shutdown(); }
        }
    }
    private MediaStorageWorker worker() { return new MediaStorageWorker(store, downloader, storage, parser); }
    private SubmissionResponse queued(String source) {
        var user = new UserAccount(); String name = "media_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        user.setId(IDS.incrementAndGet()); user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash("test-placeholder");
        users.insert(user); ownedUsers.add(user.getId());
        return submissions.submit(user.getId(), UUID.randomUUID().toString(), new SubmissionRequest(source, "学习资料"));
    }
    private SubmissionResponse pending(boolean cover) {
        var submitted = queued("https://b23.tv/" + UUID.randomUUID() + "/");
        var lease = parseStore.claim().orElseThrow(); assertThat(lease.jobId()).isEqualTo(submitted.jobId());
        assertThat(parseStore.complete(lease, parsed(cover, "old"))).isTrue(); due(submitted); return submitted;
    }
    private ParsedVideo parsed(boolean cover, String title) {
        return new ParsedVideo(title, URI.create("https://cdn.example.com/" + title + ".mp4"),
                cover ? URI.create("https://cdn.example.com/cover.png") : null, "数学老师", "author", null);
    }
    private StoredMedia uploaded(long owner, long fragment, MediaKind kind) {
        String hash = (kind == MediaKind.VIDEO ? "a" : "b").repeat(64);
        return new StoredMedia("test-bucket", "users/" + owner + "/fragments/" + fragment + "/" + kind.segment() + "/" + hash,
                3, hash, kind == MediaKind.VIDEO ? "video/mp4" : "image/png");
    }
    private long owner(SubmissionResponse item) { return jdbc.queryForObject("SELECT user_id FROM fragments WHERE id = ?", Long.class, item.fragmentId()); }
    private String stage(SubmissionResponse item) { return jdbc.queryForObject("SELECT stage FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private String error(SubmissionResponse item) { return jdbc.queryForObject("SELECT error_code FROM ingestion_jobs WHERE id = ?", String.class, item.jobId()); }
    private int count(SubmissionResponse item) { return jdbc.queryForObject("SELECT COUNT(*) FROM fragment_stored_media WHERE fragment_id = ?", Integer.class, item.fragmentId()); }
    private void due(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET next_attempt_at = '2000-01-01' WHERE id = ?", item.jobId()); }
    private void expire(SubmissionResponse item) { jdbc.update("UPDATE ingestion_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(3)) WHERE id = ?", item.jobId()); }
}
