package com.fragpicker.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("fragpicker.digest.worker")
public record DigestJobProperties(@DefaultValue("false") boolean enabled, @DefaultValue("2s") Duration pollDelay,
                                  @DefaultValue("5m") Duration leaseDuration) {
    public void validate() {
        if (pollDelay == null || pollDelay.compareTo(Duration.ofMillis(250)) < 0 || pollDelay.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalStateException("Invalid DIGEST_POLL_DELAY");
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(15)) < 0 || leaseDuration.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalStateException("Invalid DIGEST_LEASE_DURATION");
    }
}
