package com.fragpicker.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.knowledge.enrichment")
public record EnrichmentProperties(boolean enabled, Duration pollDelay, Duration leaseDuration, int maxAttempts, Duration retryBaseDelay) {
    public void validate() {
        bounded(pollDelay, Duration.ofMillis(250), Duration.ofMinutes(1), "POLL_DELAY");
        bounded(leaseDuration, Duration.ofMinutes(1), Duration.ofMinutes(15), "LEASE_DURATION");
        bounded(retryBaseDelay, Duration.ofSeconds(1), Duration.ofMinutes(10), "RETRY_BASE_DELAY");
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalStateException("KNOWLEDGE_MAX_ATTEMPTS must be 1..10");
    }
    public long retryDelay(int attempt) { return Math.min(3600, retryBaseDelay.toSeconds() * (1L << (attempt - 1))); }
    private void bounded(Duration value, Duration min, Duration max, String field) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalStateException("Invalid KNOWLEDGE_" + field);
    }
}
