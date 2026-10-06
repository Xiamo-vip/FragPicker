package com.fragpicker.chat.turn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("fragpicker.chat.turn")
public record ChatTurnProperties(@DefaultValue("180s") Duration timeout, @DefaultValue("2") int maxConcurrent) {
    public void validate() {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalStateException("CHAT_TURN_TIMEOUT must be between 1s and 10m");
        if (maxConcurrent < 1 || maxConcurrent > 8) throw new IllegalStateException("CHAT_MAX_CONCURRENT must be between 1 and 8");
    }
}
