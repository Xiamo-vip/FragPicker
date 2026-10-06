package com.fragpicker.ingestion;

import com.fragpicker.integration.media.*;
import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.parsevideo.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaWorkerConfigurationTest {
    private MediaWorkerProperties valid(boolean enabled) {
        return new MediaWorkerProperties(enabled, Duration.ofSeconds(30), Duration.ofMinutes(30), 3, Duration.ofSeconds(10));
    }
    private MediaDownloadProperties media() {
        return new MediaDownloadProperties(Path.of("unused"), 1048576, 1048576,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), 3);
    }
    private ParseVideoProperties parse(boolean enabled) {
        return new ParseVideoProperties(enabled, "http://127.0.0.1:8001", Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", "");
    }
    private OssProperties oss(boolean enabled) {
        return new OssProperties(enabled, "test-bucket", "oss-cn-shenzhen.aliyuncs.com", 1048576, 1048576, Duration.ofMinutes(5));
    }
    @Test void onlySchedulesWhenEnabled() {
        for (boolean enabled : new boolean[]{false, true}) {
            new ApplicationContextRunner().withUserConfiguration(MediaWorkerConfiguration.class)
                    .withPropertyValues("spring.profiles.active=database", "fragpicker.ingestion.media-worker.enabled=" + enabled,
                            "fragpicker.ingestion.media-worker.poll-delay=30s")
                    .withBean(MediaJobStore.class, () -> mock(MediaJobStore.class))
                    .withBean(SafeMediaDownloader.class, () -> mock(SafeMediaDownloader.class))
                    .withBean(OssMediaStorage.class, () -> mock(OssMediaStorage.class))
                    .withBean(ParseVideoClient.class, () -> mock(ParseVideoClient.class))
                    .withBean(MediaWorkerProperties.class, () -> valid(enabled))
                    .withBean(MediaDownloadProperties.class, this::media)
                    .withBean(ParseVideoProperties.class, () -> parse(true))
                    .withBean(OssProperties.class, () -> oss(true))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        if (enabled) {
                            assertThat(ctx).hasSingleBean(MediaStorageWorker.class);
                            assertThat(ctx.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
                        } else assertThat(ctx).doesNotHaveBean(MediaStorageWorker.class);
                    });
        }
    }
    @Test void rejectsUnusableLimitsDependenciesAndLeaseBudget() {
        assertThatThrownBy(() -> new MediaWorkerProperties(true, Duration.ZERO, Duration.ofMinutes(30), 3, Duration.ofSeconds(10)).validate())
                .hasMessageContaining("POLL_DELAY");
        assertThatThrownBy(() -> new MediaWorkerProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(30), 11, Duration.ofSeconds(10)).validate())
                .hasMessageContaining("MAX_ATTEMPTS");
        var config = new MediaWorkerConfiguration();
        assertThatThrownBy(() -> config.mediaStorageWorker(mock(MediaJobStore.class), mock(SafeMediaDownloader.class), mock(OssMediaStorage.class),
                mock(ParseVideoClient.class), valid(true), media(), parse(false), oss(true)))
                .hasMessageContaining("requires");
        var shortLease = new MediaWorkerProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(5), 3, Duration.ofSeconds(10));
        assertThatThrownBy(() -> config.mediaStorageWorker(mock(MediaJobStore.class), mock(SafeMediaDownloader.class), mock(OssMediaStorage.class),
                mock(ParseVideoClient.class), shortLease, media(), parse(true), oss(true))).hasMessageContaining("LEASE_DURATION");
        assertThat(valid(true).retryDelaySeconds(3)).isEqualTo(40);
    }
}
