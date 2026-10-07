package com.fragpicker.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("fragpicker.digest.manual")
public record DigestManualProperties(@DefaultValue("60s") Duration cooldown) {
    public void validate() {
        if (cooldown==null || cooldown.compareTo(Duration.ofSeconds(1))<0 || cooldown.compareTo(Duration.ofHours(1))>0)
            throw new IllegalStateException("Invalid DIGEST_REBUILD_COOLDOWN");
    }
}
