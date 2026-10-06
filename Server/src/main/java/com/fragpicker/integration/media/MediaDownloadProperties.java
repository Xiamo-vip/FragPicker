package com.fragpicker.integration.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("fragpicker.integrations.media")
public record MediaDownloadProperties(Path tempDirectory, long maxVideoBytes, long maxCoverBytes,
                                      Duration connectTimeout, Duration readTimeout, Duration totalTimeout, int maxRedirects) {
    public void validate() {
        if (tempDirectory == null) throw new IllegalStateException("MEDIA_TEMP_DIR is required");
        if (maxVideoBytes < 1 || maxVideoBytes > 5L * 1024 * 1024 * 1024
                || maxCoverBytes < 1 || maxCoverBytes > 50L * 1024 * 1024) throw new IllegalStateException("Invalid media size limit");
        bounded(connectTimeout, Duration.ofMillis(250), Duration.ofSeconds(30));
        bounded(readTimeout, Duration.ofMillis(250), Duration.ofMinutes(2));
        bounded(totalTimeout, Duration.ofSeconds(1), Duration.ofMinutes(30));
        if (maxRedirects < 0 || maxRedirects > 5) throw new IllegalStateException("MEDIA_MAX_REDIRECTS must be 0..5");
    }
    private void bounded(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalStateException("Invalid media timeout");
    }
}
