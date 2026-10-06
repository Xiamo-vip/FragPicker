package com.fragpicker.integration.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@ConfigurationProperties("fragpicker.integrations.chat")
public record ChatModelProperties(boolean enabled, String baseUrl, String apiKey,
                                  String model, Duration timeout, Integer maxOutputTokens) {
    public void validateEnabled() {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("AI_CHAT_API_KEY is required");
        if (model == null || model.isBlank()) throw new IllegalStateException("AI_CHAT_MODEL is required");
        URI uri;
        try { uri = URI.create(baseUrl); }
        catch (RuntimeException e) { throw new IllegalStateException("AI_CHAT_BASE_URL must be an absolute HTTP(S) URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean localHttp = "http".equals(scheme)
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if ((!"https".equals(scheme) && !localHttp) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("AI_CHAT_BASE_URL requires HTTPS (HTTP allowed only on loopback), without credentials or query");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("AI_CHAT_TIMEOUT must be between 1s and 5m");
        }
        if (maxOutputTokens == null || maxOutputTokens < 1 || maxOutputTokens > 32768) {
            throw new IllegalStateException("AI_CHAT_MAX_OUTPUT_TOKENS must be between 1 and 32768");
        }
    }

    @Override
    public String toString() {
        return "ChatModelProperties[enabled=" + enabled + ", apiKey=REDACTED]";
    }
}
