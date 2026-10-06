package com.fragpicker.integration.oss;

import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "OSS_TEST_ENABLED", matches = "true")
class OssStorageLiveIntegrationTest {
    @TempDir Path directory;

    @Test
    void realPrivateBucketUploadSignedReadUnsignedDenialAndExpiredUrl() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.aliyun.oss"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        var properties = new OssProperties(true, System.getenv("OSS_BUCKET"), System.getenv("OSS_ENDPOINT"),
                1048576, 1048576, Duration.ofSeconds(30));
        var credentials = new AliyunCredentialsProperties(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"),
                System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN"));
        var client = new OssConfiguration().ossClient(properties, credentials);
        var storage = new OssMediaStorage(client, properties, Clock.systemUTC());
        // A unique test owner namespace avoids every existing account/object. Delete only this test object.
        long owner = System.currentTimeMillis();
        long fragment = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        StoredMedia stored = null;
        try {
            storage.verifyPrivateBucket();
            byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZBqAAAAAASUVORK5CYII=");
            var file = Files.write(directory.resolve("pixel.png"), png);
            stored = storage.put(owner, fragment, MediaKind.COVER, file, "image/png");
            assertThat(storage.put(owner, fragment, MediaKind.COVER, file, "image/png").key()).isEqualTo(stored.key());
            var signed = storage.signedGet(owner, fragment, MediaKind.COVER, stored.key());
            assertThat(read(signed.url(), true).body()).containsExactly(png);
            URI unsigned = URI.create(properties.normalizedEndpoint().replace("https://", "https://" + properties.bucket() + ".") + "/" + stored.key());
            assertThat(read(unsigned, false).status()).isEqualTo(403);
            long wait = Duration.between(Instant.now(), signed.expiresAt()).toMillis() + 1500;
            if (wait > 0) Thread.sleep(wait);
            assertThat(read(signed.url(), false).status()).isEqualTo(403);
        } finally {
            try { if (stored != null) storage.delete(owner, fragment, MediaKind.COVER, stored.key()); }
            finally { client.shutdown(); }
        }
    }

    private Result read(URI uri, boolean expectedSuccess) {
        // Never expose presigned URLs or response bodies in network exceptions/assertion messages.
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            byte[] bytes = new byte[0];
            if (expectedSuccess) {
                assertThat(status).isEqualTo(200);
                try (var input = connection.getInputStream()) { bytes = input.readNBytes(1048577); }
            }
            return new Result(status, bytes);
        } catch (java.io.IOException failure) { throw new AssertionError("Real OSS HTTP verification failed (details redacted)"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private record Result(int status, byte[] body) { }
}
