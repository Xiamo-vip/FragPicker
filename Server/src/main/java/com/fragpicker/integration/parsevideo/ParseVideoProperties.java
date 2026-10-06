package com.fragpicker.integration.parsevideo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("fragpicker.integrations.parsevideo")
public record ParseVideoProperties(boolean enabled, String baseUrl, Duration connectTimeout, Duration readTimeout,
                                   int maxResponseBytes, String username, String password) {
    public void validateEnabled() {
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException e) { throw new IllegalStateException("PARSEVIDEO_BASE_URL must be an absolute HTTP(S) URL"); }
        if (!Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("PARSEVIDEO_BASE_URL must be an absolute HTTP(S) URL without credentials or query");
        }
        if (connectTimeout == null || connectTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalStateException("Invalid PARSEVIDEO_CONNECT_TIMEOUT");
        if (readTimeout == null || readTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || readTimeout.compareTo(Duration.ofMinutes(2)) > 0) throw new IllegalStateException("Invalid PARSEVIDEO_READ_TIMEOUT");
        if (maxResponseBytes < 16384 || maxResponseBytes > 4194304) throw new IllegalStateException("Invalid PARSEVIDEO_MAX_RESPONSE_BYTES");
        boolean hasUser = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isEmpty();
        if (hasUser != hasPassword) throw new IllegalStateException("PARSEVIDEO_USERNAME and PARSEVIDEO_PASSWORD must be set together");
        if (hasUser && (!"https".equals(uri.getScheme())
                && !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) {
            throw new IllegalStateException("Authenticated parsevideo requires HTTPS or loopback HTTP");
        }
    }

    @Override public String toString() { return "ParseVideoProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
