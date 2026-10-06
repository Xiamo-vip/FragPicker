package com.fragpicker.integration.oss;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OssStorageContractTest {
    @TempDir Path directory;
    private HttpServer fixture;
    private OSS client;
    private OssMediaStorage storage;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String acl = "private";
    private volatile int status = 200;
    private volatile String errorCode = "AccessDenied";
    private volatile byte[] uploaded;
    private volatile com.sun.net.httpserver.Headers uploadHeaders;

    @BeforeEach
    void fixture() throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.aliyun.oss"))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            calls.incrementAndGet();
            String response = "";
            if (status != 200) {
                response = "<Error><Code>" + errorCode + "</Code><Message>provider-secret</Message><RequestId>fixture</RequestId><HostId>fixture</HostId></Error>";
            } else if ("GET".equals(exchange.getRequestMethod())) {
                response = "<AccessControlPolicy><Owner><ID>fixture</ID><DisplayName>fixture</DisplayName></Owner><AccessControlList><Grant>"
                        + acl + "</Grant></AccessControlList></AccessControlPolicy>";
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                uploaded = exchange.getRequestBody().readAllBytes(); uploadHeaders = exchange.getRequestHeaders();
                exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        fixture.start();
        var conf = new ClientBuilderConfiguration();
        conf.setSignatureVersion(SignVersion.V4); conf.setSLDEnabled(true);
        conf.setMaxErrorRetry(0); conf.setConnectionTimeout(2000); conf.setSocketTimeout(2000);
        // The HTTP fixture does not return an OSS CRC64 response header; live tests use CRC checks.
        conf.setCrcCheckEnabled(false);
        client = OSSClientBuilder.create().endpoint("http://127.0.0.1:" + fixture.getAddress().getPort())
                .region("cn-shenzhen").credentialsProvider(new DefaultCredentialProvider("fixture-ak", "fixture-secret"))
                .clientConfiguration(conf).build();
        storage = new OssMediaStorage(client, props(1024), Clock.systemUTC());
    }

    @AfterEach void close() { if (client != null) client.shutdown(); if (fixture != null) fixture.stop(0); }

    @Test
    void usesActualSdkToUploadWithPrivateAclIntegrityHeadersAndStableScopedKeys() throws Exception {
        storage.verifyPrivateBucket();
        var file = Files.write(directory.resolve("cover.png"), new byte[]{1, 2, 3, 4});
        var stored = storage.put(10, 20, MediaKind.COVER, file, "image/png");
        assertThat(stored.key()).startsWith("users/10/fragments/20/cover/").endsWith(stored.sha256());
        assertThat(stored.sizeBytes()).isEqualTo(4);
        assertThat(uploaded).containsExactly(1, 2, 3, 4);
        assertThat(uploadHeaders.getFirst("x-oss-object-acl")).isEqualTo("private");
        assertThat(uploadHeaders.getFirst("Content-MD5")).isEqualTo("CNbAWiFRKnmh3+udKo8mLw==");
        assertThat(uploadHeaders.getFirst("x-oss-meta-sha256")).isEqualTo(stored.sha256());
        assertThat(uploadHeaders.getFirst("Authorization")).startsWith("OSS4-HMAC-SHA256 ");
        assertThat(storage.put(10, 20, MediaKind.COVER, file, "image/png").key()).isEqualTo(stored.key());
    }

    @Test
    void refusesPublicBucketAndMapsAccessErrorsWithoutProviderDetailsOrRetries() throws Exception {
        acl = "public-read";
        assertThatThrownBy(storage::verifyPrivateBucket).isInstanceOfSatisfying(OssStorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(OssStorageFailure.Code.BUCKET_NOT_PRIVATE));
        status = 403;
        var file = Files.write(directory.resolve("cover.png"), new byte[]{1});
        assertThatThrownBy(() -> storage.put(1, 2, MediaKind.COVER, file, "image/png"))
                .isInstanceOfSatisfying(OssStorageFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo(OssStorageFailure.Code.ACCESS_DENIED);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain("provider-secret");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(calls).hasValue(2);
        status = 503; errorCode = "ServiceUnavailable";
        assertThatThrownBy(storage::verifyPrivateBucket).isInstanceOfSatisfying(OssStorageFailure.class,
                failure -> assertThat(failure.retryable()).isTrue());
        assertThat(calls).hasValue(3);
    }

    @Test
    void signsOnlyMatchingOwnerAndKindWithBoundedExpiryAndRedactedToString() {
        String key = "users/10/fragments/20/video/" + "a".repeat(64);
        var signed = storage.signedGet(10, 20, MediaKind.VIDEO, key, Duration.ofSeconds(40));
        assertThat(signed.expiresAt()).isBetween(Instant.now().plusSeconds(38), Instant.now().plusSeconds(41));
        assertThat(signed.url().getRawQuery()).contains("x-oss-signature=").contains("x-oss-expires=");
        assertThat(signed.toString()).doesNotContain("fixture-ak").contains("REDACTED");
        assertThatThrownBy(() -> storage.signedGet(11, 20, MediaKind.VIDEO, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedGet(10, 20, MediaKind.COVER, key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(10, 20, MediaKind.VIDEO, key + "/../other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedGet(10, 20, MediaKind.VIDEO, key, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void signsLongLivedTranscriptionInputWithoutRelaxingPlaybackLimits() {
        String key = "users/10/fragments/20/video/" + "a".repeat(64);
        var input = storage.signedGetForTranscription(10, 20, key, Duration.ofHours(4));
        assertThat(input.expiresAt()).isBetween(Instant.now().plusSeconds(14398), Instant.now().plusSeconds(14401));
        var expires = java.util.regex.Pattern.compile("(?:^|&)x-oss-expires=(\\d+)").matcher(input.url().getRawQuery());
        assertThat(expires.find()).isTrue();
        assertThat(Long.parseLong(expires.group(1))).isBetween(14398L, 14400L);
        assertThatThrownBy(() -> storage.signedGetForTranscription(11, 20, key, Duration.ofHours(4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedGetForTranscription(10, 20, key, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedGetForTranscription(10, 20, key, Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.signedGet(10, 20, MediaKind.VIDEO, key, Duration.ofHours(4))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void validatesFileSizeAndTypesBeforeAnyCloudRequest() throws Exception {
        var file = Files.write(directory.resolve("cover.png"), new byte[]{1, 2, 3});
        var limited = new OssMediaStorage(client, props(2), Clock.systemUTC());
        assertThatThrownBy(() -> limited.put(1, 2, MediaKind.COVER, file, "image/png"))
                .isInstanceOfSatisfying(OssStorageFailure.class, failure -> assertThat(failure.code()).isEqualTo(OssStorageFailure.Code.FILE_TOO_LARGE));
        assertThatThrownBy(() -> storage.put(1, 2, MediaKind.COVER, file, "image/svg+xml")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(0, 2, MediaKind.VIDEO, file, "video/mp4")).isInstanceOf(IllegalArgumentException.class);
        var empty = Files.createFile(directory.resolve("empty"));
        assertThatThrownBy(() -> storage.put(1, 2, MediaKind.VIDEO, empty, "video/mp4")).isInstanceOf(OssStorageFailure.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void configurationRequiresCredentialsAndPublicRegionalHttpsEndpoint() {
        assertThat(props(1024).normalizedEndpoint()).isEqualTo("https://oss-cn-shenzhen.aliyuncs.com");
        assertThat(props(1024).region()).isEqualTo("cn-shenzhen");
        for (String endpoint : List.of("http://oss-cn-shenzhen.aliyuncs.com", "https://evil.example", "oss-cn-shenzhen-internal.aliyuncs.com",
                "https://user:password@oss-cn-shenzhen.aliyuncs.com", "https://oss-cn-shenzhen.aliyuncs.com?x=1")) {
            assertThatThrownBy(() -> new OssProperties(true, "fixture-bucket", endpoint, 1024, 1024, Duration.ofMinutes(5)).validate())
                    .isInstanceOf(IllegalStateException.class);
        }
        var credentials = new AliyunCredentialsProperties("fixture-ak", "fixture-secret", "fixture-sts");
        assertThat(credentials.toString()).doesNotContain("fixture-");
        assertThatThrownBy(() -> new AliyunCredentialsProperties("", "", "").validate()).hasMessageContaining("required");
    }

    private OssProperties props(long coverLimit) {
        return new OssProperties(true, "fixture-bucket", "oss-cn-shenzhen.aliyuncs.com", 1048576, coverLimit, Duration.ofMinutes(5));
    }
}
