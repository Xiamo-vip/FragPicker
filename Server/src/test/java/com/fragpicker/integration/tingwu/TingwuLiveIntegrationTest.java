package com.fragpicker.integration.tingwu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fragpicker.integration.oss.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TINGWU_TEST_ENABLED", matches = "true")
class TingwuLiveIntegrationTest {
    record LiveJob(String taskId, long owner, long fragment, String objectKey, String bucket) { }

    @Test void privatelyUploadsSyntheticSpeechCreatesOneTaskAndReadsRealResults() throws Exception {
        for (String logger : new String[]{"com.aliyun.oss", "com.aliyun.tea", "com.aliyun.teaopenapi", "org.apache.http"}) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(logger)).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        var json = new ObjectMapper();
        Path checkpoint = Path.of(System.getenv("TINGWU_TEST_CHECKPOINT_PATH"));
        var properties = new TingwuProperties(true, System.getenv("TINGWU_APP_KEY"), "cn", Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60), 16 * 1024 * 1024, Duration.ofHours(4));
        var credentials = new AliyunCredentialsProperties(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"),
                System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN"));
        var ossProperties = new OssProperties(true, System.getenv("OSS_BUCKET"), System.getenv("OSS_ENDPOINT"),
                5 * 1024 * 1024, 1048576, Duration.ofMinutes(5));
        var oss = new OssConfiguration().ossClient(ossProperties, credentials);
        var storage = new OssMediaStorage(oss, ossProperties, Clock.systemUTC());
        var config = new TingwuConfiguration();
        var client = config.tingwuClient(config.tingwuSdk(properties, credentials), properties);
        LiveJob job = null;
        boolean terminal = false, verified = false;
        try (var reader = config.tingwuResultReader(properties, json)) {
            storage.verifyPrivateBucket();
            if (Files.exists(checkpoint)) {
                job = json.readValue(Files.readAllBytes(checkpoint), LiveJob.class);
                assertThat(job.bucket()).isEqualTo(ossProperties.bucket());
                if (job.taskId() == null) throw new AssertionError("Previous submission outcome is uncertain; reconcile before creating another paid task.");
            } else {
                Path speech = Path.of(System.getenv("TINGWU_TEST_MEDIA_PATH"));
                assertThat(Files.size(speech)).isBetween(1L, 5L * 1024 * 1024);
                long owner = System.currentTimeMillis() * 1000;
                long fragment = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
                var media = storage.put(owner, fragment, MediaKind.VIDEO, speech, "application/octet-stream");
                job = new LiveJob(null, owner, fragment, media.key(), media.bucket());
                // Persist before POST: a timeout must not lead a subsequent test run to recreate the task.
                json.writeValue(checkpoint.toFile(), job);
                var signed = storage.signedGetForTranscription(owner, fragment, media.key(), properties.sourceUrlTtl());
                try {
                    var created = client.create(signed.url(), "fragpicker-test-" + owner);
                    job = new LiveJob(created.id(), owner, fragment, media.key(), media.bucket());
                    json.writeValue(checkpoint.toFile(), job);
                } catch (TingwuFailure failure) {
                    if (failure.code() != TingwuFailure.Code.SUBMISSION_UNCERTAIN) terminal = true;
                    throw failure;
                }
            }
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            TingwuTask task;
            do {
                task = client.get(job.taskId());
                System.out.println("Real Tingwu task status: " + task.status());
                if (task.status() != TingwuTask.Status.ONGOING) break;
                if (System.nanoTime() >= deadline) throw new AssertionError("Live Tingwu task still running; checkpoint retained for querying without another creation.");
                Thread.sleep(15000);
            } while (true);
            terminal = true;
            assertThat(task.status()).as("cloud task terminal state, category=" + task.failure()).isEqualTo(TingwuTask.Status.COMPLETED);
            for (var resource : new java.net.URI[]{task.transcriptionUrl(), task.summaryUrl(), task.assistanceUrl()}) {
                if (resource != null) System.out.println("Real Tingwu result host: " + resource.getHost());
            }
            var result = reader.read(task);
            assertThat(result.durationMs()).isPositive(); assertThat(result.sentences()).isNotEmpty();
            assertThat(result.sentences().stream().anyMatch(sentence -> sentence.text().contains("导数"))).isTrue();
            assertThat(result.summary() != null && !result.summary().isBlank()).as("nonempty real summary").isTrue();
            assertThat(result.keywords()).isNotEmpty();
            assertThat(result.sentences()).allSatisfy(sentence -> {
                assertThat(sentence.startMs()).isNotNegative(); assertThat(sentence.endMs()).isGreaterThanOrEqualTo(sentence.startMs());
            });
            verified = true;
            System.out.printf("Real Tingwu result verified: duration=%d ms, sentences=%d, keywords=%d, points=%d%n",
                    result.durationMs(), result.sentences().size(), result.keywords().size(), result.keyPoints().size());
        } finally {
            try {
                if (job != null && terminal) storage.delete(job.owner(), job.fragment(), MediaKind.VIDEO, job.objectKey());
                if (verified || (terminal && job != null && job.taskId() == null)) Files.deleteIfExists(checkpoint);
            } finally { oss.shutdown(); }
        }
    }
}
