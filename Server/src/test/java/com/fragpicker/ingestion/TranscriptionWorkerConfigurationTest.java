package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.tingwu.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranscriptionWorkerConfigurationTest {
    private TranscriptionWorkerProperties worker(Duration lease) {
        return new TranscriptionWorkerProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(1), lease,
                Duration.ofHours(24), 8, Duration.ofSeconds(10));
    }
    private TingwuProperties tingwu(boolean enabled) {
        return new TingwuProperties(enabled, "test-app", "cn", Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(60), 1048576, Duration.ofHours(4));
    }
    private OssProperties oss() {
        return new OssProperties(true, "test-bucket", "oss-cn-shenzhen.aliyuncs.com", 1048576, 1048576, Duration.ofMinutes(5));
    }
    @Test void onlySchedulesWhenEnabled() {
        for (boolean enabled : new boolean[]{false, true}) {
            new ApplicationContextRunner().withUserConfiguration(TranscriptionWorkerConfiguration.class)
                    .withPropertyValues("spring.profiles.active=database", "fragpicker.ingestion.transcription-worker.enabled=" + enabled,
                            "fragpicker.ingestion.transcription-worker.poll-delay=30s")
                    .withBean(TranscriptionJobStore.class, () -> mock(TranscriptionJobStore.class))
                    .withBean(OssMediaStorage.class, () -> mock(OssMediaStorage.class)).withBean(OssProperties.class, this::oss)
                    .withBean(TingwuClient.class, () -> mock(TingwuClient.class)).withBean(TingwuResultReader.class, () -> mock(TingwuResultReader.class))
                    .withBean(TingwuProperties.class, () -> tingwu(true))
                    .withBean(TranscriptionWorkerProperties.class, () -> worker(Duration.ofMinutes(5)))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        if (enabled) {
                            assertThat(ctx).hasSingleBean(TranscriptionWorker.class);
                            assertThat(ctx.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
                        } else assertThat(ctx).doesNotHaveBean(TranscriptionWorker.class);
                    });
        }
    }
    @Test void validatesLimitsDependenciesAndLeaseBudget() {
        var invalid = new TranscriptionWorkerProperties(true, Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofHours(24), 8, Duration.ofSeconds(10));
        assertThatThrownBy(invalid::validate).hasMessageContaining("POLL_DELAY");
        var config = new TranscriptionWorkerConfiguration();
        assertThatThrownBy(() -> config.transcriptionWorker(mock(TranscriptionJobStore.class), mock(OssMediaStorage.class), oss(),
                mock(TingwuClient.class), mock(TingwuResultReader.class), tingwu(false), worker(Duration.ofMinutes(5)))).hasMessageContaining("requires");
        assertThatThrownBy(() -> config.transcriptionWorker(mock(TranscriptionJobStore.class), mock(OssMediaStorage.class), oss(),
                mock(TingwuClient.class), mock(TingwuResultReader.class), tingwu(true), worker(Duration.ofMinutes(1)))).hasMessageContaining("LEASE_DURATION");
        assertThat(worker(Duration.ofMinutes(5)).retryDelaySeconds(8)).isEqualTo(1280);
    }
}
