package com.fragpicker.ingestion;

import com.fragpicker.integration.parsevideo.ParseVideoClient;
import com.fragpicker.integration.parsevideo.ParseVideoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParseWorkerConfigurationTest {
    private ParseWorkerProperties valid(boolean enabled) {
        return new ParseWorkerProperties(enabled, Duration.ofSeconds(30), Duration.ofMinutes(5), 3, Duration.ofSeconds(10));
    }

    private ApplicationContextRunner context(boolean enabled) {
        return new ApplicationContextRunner().withUserConfiguration(ParseWorkerConfiguration.class)
                .withPropertyValues("spring.profiles.active=database", "fragpicker.ingestion.worker.enabled=" + enabled,
                        "fragpicker.ingestion.worker.poll-delay=30s")
                .withBean(ParseJobStore.class, () -> mock(ParseJobStore.class))
                .withBean(ParseVideoClient.class, () -> mock(ParseVideoClient.class))
                .withBean(ParseWorkerProperties.class, () -> valid(enabled))
                .withBean(ParseVideoProperties.class, () -> new ParseVideoProperties(true, "http://127.0.0.1:8001",
                        Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", ""));
    }

    @Test
    void onlyRegistersScheduledWorkerWhenExplicitlyEnabled() {
        context(false).run(ctx -> assertThat(ctx).doesNotHaveBean(ParseVideoWorker.class));
        context(true).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ParseVideoWorker.class);
            assertThat(ctx.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void rejectsInvalidLimitsAndLeaseShorterThanParseTimeouts() {
        assertThatThrownBy(() -> new ParseWorkerProperties(true, Duration.ZERO, Duration.ofMinutes(5), 3, Duration.ofSeconds(10)).validate())
                .hasMessageContaining("POLL_DELAY");
        assertThatThrownBy(() -> new ParseWorkerProperties(true, Duration.ofSeconds(2), Duration.ofMinutes(5), 11, Duration.ofSeconds(10)).validate())
                .hasMessageContaining("MAX_ATTEMPTS");
        var shortLease = new ParseWorkerProperties(true, Duration.ofSeconds(2), Duration.ofSeconds(15), 3, Duration.ofSeconds(10));
        var parser = new ParseVideoProperties(true, "http://127.0.0.1:8001", Duration.ofSeconds(5), Duration.ofSeconds(45), 1048576, "", "");
        assertThatThrownBy(() -> new ParseWorkerConfiguration().parseVideoWorker(mock(ParseJobStore.class), mock(ParseVideoClient.class), shortLease, parser))
                .hasMessageContaining("LEASE_DURATION");
        assertThat(valid(true).retryDelaySeconds(1)).isEqualTo(10);
        assertThat(valid(true).retryDelaySeconds(3)).isEqualTo(40);
        assertThat(valid(true).retryDelaySeconds(10)).isEqualTo(3600);
    }
}
