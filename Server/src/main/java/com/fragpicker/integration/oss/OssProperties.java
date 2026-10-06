package com.fragpicker.integration.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties("fragpicker.integrations.oss")
public record OssProperties(boolean enabled, String bucket, String endpoint, long maxVideoBytes,
                            long maxCoverBytes, Duration signedUrlTtl) {
    private static final Pattern HOST = Pattern.compile("oss-([a-z0-9]+(?:-[a-z0-9]+)+)\\.aliyuncs\\.com");

    public void validate() {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw new IllegalStateException("Invalid OSS_BUCKET");
        normalizedEndpoint();
        if (maxVideoBytes < 1 || maxVideoBytes > 5L * 1024 * 1024 * 1024) throw new IllegalStateException("Invalid OSS_MAX_VIDEO_BYTES");
        if (maxCoverBytes < 1 || maxCoverBytes > 50L * 1024 * 1024) throw new IllegalStateException("Invalid OSS_MAX_COVER_BYTES");
        validateTtl(signedUrlTtl);
    }

    public String normalizedEndpoint() {
        try {
            URI uri = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOST.matcher(uri.getHost()).matches()
                    || uri.getHost().contains("-internal.") || uri.getUserInfo() != null || uri.getPort() != -1
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return "https://" + uri.getHost();
        } catch (RuntimeException failure) { throw new IllegalStateException("OSS_ENDPOINT must be a public regional aliyuncs.com HTTPS endpoint"); }
    }

    public String region() {
        var match = HOST.matcher(URI.create(normalizedEndpoint()).getHost());
        if (!match.matches()) throw new IllegalStateException("Invalid OSS region");
        return match.group(1);
    }

    public static void validateTtl(Duration ttl) {
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0 || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("OSS signed URL TTL must be 30 seconds..1 hour");
        }
    }
}
