package com.fragpicker.knowledge.index;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.knowledge.index")
public record IndexProperties(boolean enabled, Duration pollDelay, Duration leaseDuration, int maxAttempts, Duration retryBaseDelay, int maxChunks) {
    public void validate() {
        bounded(pollDelay, Duration.ofMillis(250), Duration.ofMinutes(1));
        bounded(leaseDuration, Duration.ofMinutes(1), Duration.ofHours(1));
        bounded(retryBaseDelay, Duration.ofSeconds(1), Duration.ofMinutes(10));
        if (maxAttempts < 1 || maxAttempts > 10 || maxChunks < 16 || maxChunks > 50000) throw new IllegalStateException("Invalid semantic index limits");
    }
    public long retryDelay(int attempt) { return Math.min(3600, retryBaseDelay.toSeconds() * (1L << (attempt - 1))); }
    private void bounded(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalStateException("Invalid semantic index duration");
    }
}
