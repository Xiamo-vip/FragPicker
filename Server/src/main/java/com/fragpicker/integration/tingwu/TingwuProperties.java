package com.fragpicker.integration.tingwu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("fragpicker.integrations.tingwu")
public record TingwuProperties(boolean enabled, String appKey, String sourceLanguage, Duration connectTimeout,
                               Duration readTimeout, Duration resultTimeout, int maxResultBytes, Duration sourceUrlTtl) {
    public void validate() {
        if (appKey == null || !appKey.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalStateException("TINGWU_APP_KEY is required");
        if (!Set.of("auto", "cn", "en", "yue", "ja", "ko").contains(sourceLanguage == null ? "" : sourceLanguage)) {
            throw new IllegalStateException("Invalid TINGWU_SOURCE_LANGUAGE");
        }
        bounded(connectTimeout, Duration.ofSeconds(1), Duration.ofSeconds(30), "TINGWU_CONNECT_TIMEOUT");
        bounded(readTimeout, Duration.ofSeconds(1), Duration.ofMinutes(2), "TINGWU_READ_TIMEOUT");
        bounded(resultTimeout, Duration.ofSeconds(1), Duration.ofMinutes(5), "TINGWU_RESULT_TIMEOUT");
        bounded(sourceUrlTtl, Duration.ofHours(3), Duration.ofHours(12), "TINGWU_SOURCE_URL_TTL");
        if (maxResultBytes < 1024 || maxResultBytes > 64 * 1024 * 1024) throw new IllegalStateException("TINGWU_MAX_RESULT_BYTES must be 1 KiB..64 MiB");
    }
    private static void bounded(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalStateException("Invalid " + name);
    }
    @Override public String toString() { return "TingwuProperties[enabled=" + enabled + ", appKey=REDACTED]"; }
}
