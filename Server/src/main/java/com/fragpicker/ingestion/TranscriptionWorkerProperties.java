package com.fragpicker.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.ingestion.transcription-worker")
public record TranscriptionWorkerProperties(boolean enabled, Duration pollDelay, Duration queryDelay,
        Duration leaseDuration, Duration maxTaskAge, int maxFailures, Duration retryBaseDelay) {
    public void validate() {
        bounded(pollDelay, Duration.ofMillis(250), Duration.ofMinutes(1), "POLL_DELAY");
        bounded(queryDelay, Duration.ofSeconds(15), Duration.ofMinutes(10), "QUERY_DELAY");
        bounded(leaseDuration, Duration.ofMinutes(1), Duration.ofHours(1), "LEASE_DURATION");
        bounded(maxTaskAge, Duration.ofHours(1), Duration.ofHours(72), "MAX_TASK_AGE");
        bounded(retryBaseDelay, Duration.ofSeconds(1), Duration.ofMinutes(10), "RETRY_BASE_DELAY");
        if (maxFailures < 1 || maxFailures > 10) throw new IllegalStateException("TRANSCRIPTION_MAX_FAILURES must be 1..10");
    }
    public long retryDelaySeconds(int failures) {
        return Math.min(3600, retryBaseDelay.toSeconds() * (1L << (failures - 1)));
    }
    private static void bounded(Duration value, Duration min, Duration max, String field) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalStateException("Invalid TRANSCRIPTION_" + field);
        }
    }
}
