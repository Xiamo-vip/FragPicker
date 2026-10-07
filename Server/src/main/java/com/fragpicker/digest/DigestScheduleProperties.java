package com.fragpicker.digest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("fragpicker.digest.schedule")
public record DigestScheduleProperties(@DefaultValue("false") boolean enabled,@DefaultValue("10s") Duration pollDelay) {
    public void validate() {
        if (pollDelay==null || pollDelay.compareTo(Duration.ofMillis(250))<0 || pollDelay.compareTo(Duration.ofMinutes(1))>0)
            throw new IllegalStateException("Invalid DIGEST_SCHEDULE_POLL_DELAY");
    }
}
