package com.fragpicker.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.ingestion.worker")
public record ParseWorkerProperties(boolean enabled, Duration pollDelay, Duration leaseDuration,
                                    int maxAttempts, Duration retryBaseDelay) {
    public void validate() {
        bounded(pollDelay, Duration.ofMillis(250), Duration.ofMinutes(1), "INGESTION_POLL_DELAY");
        bounded(leaseDuration, Duration.ofSeconds(15), Duration.ofMinutes(10), "INGESTION_LEASE_DURATION");
        bounded(retryBaseDelay, Duration.ofSeconds(1), Duration.ofMinutes(10), "INGESTION_RETRY_BASE_DELAY");
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalStateException("INGESTION_MAX_ATTEMPTS must be 1..10");
    }

    public long retryDelaySeconds(int attempt) {
        return Math.min(3600, Math.max(1, retryBaseDelay.toSeconds()) * (1L << (attempt - 1)));
    }

    private static void bounded(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalStateException("Invalid " + name);
        }
    }
}
