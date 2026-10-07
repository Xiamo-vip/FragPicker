package com.fragpicker.ingestion.deletion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("fragpicker.ingestion.cleanup-worker")
public record CleanupProperties(boolean enabled,Duration pollDelay,Duration leaseDuration,Duration rescanDelay,Duration retryBaseDelay) {
    public void validate() {
        range(pollDelay,Duration.ofMillis(250),Duration.ofMinutes(1));range(leaseDuration,Duration.ofMinutes(2),Duration.ofHours(1));
        range(rescanDelay,Duration.ofMinutes(1),Duration.ofDays(30));range(retryBaseDelay,Duration.ofSeconds(1),Duration.ofHours(1));
    }
    private void range(Duration value,Duration min,Duration max) {
        if (value==null || value.compareTo(min)<0 || value.compareTo(max)>0) throw new IllegalStateException("Invalid media cleanup duration");
    }
    public long retrySeconds(int attempt,boolean retryable) {
        return retryable ? Math.min(rescanDelay.toSeconds(),retryBaseDelay.toSeconds()*(1L<<Math.min(10,Math.max(0,attempt-1)))) : rescanDelay.toSeconds();
    }
}
