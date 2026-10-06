package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.integration.aliyun.AliyunCredentialsProperties;
import com.fragpicker.integration.oss.*;
import com.fragpicker.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class FragmentMediaIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StoredMediaMapper media;
    @Autowired UserAccountMapper users;
    @Autowired PasswordEncoder passwords;
    @MockitoBean OssMediaStorage storage;
    private final List<Long> owned = new ArrayList<>();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        registry.add("fragpicker.integrations.oss.enabled", () -> false);
        registry.add("fragpicker.integrations.oss.bucket", () -> liveEnabled() ? System.getenv("OSS_BUCKET") : "test-bucket");
    }
    static boolean liveEnabled() { return "true".equals(System.getenv("PLAYBACK_TEST_ENABLED")); }
    @BeforeEach void sign() {
        when(storage.signedGet(anyLong(), anyLong(), any(), anyString())).thenReturn(new SignedMediaUrl(URI.create("https://test-bucket.oss-cn-shenzhen.aliyuncs.com/private?signature=test-only"), Instant.now().plusSeconds(300)));
    }
    @AfterEach void cleanup() { for (long owner : owned) jdbc.update("DELETE FROM users WHERE id = ?", owner); }

    @Test void returnsVideoAndCoverWithServerExpiryAndNoStore() {
        var owner = login(); long fragment = submit(owner.token()); seed(owner.id(), fragment, MediaKind.VIDEO); seed(owner.id(), fragment, MediaKind.COVER);
        for (var kind : MediaKind.values()) {
            var result = get(owner.token(), fragment, "kind=" + kind + "&ttl=43200&userId=999");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
            var body = result.getBody(); assertThat(body.path("kind").asText()).isEqualTo(kind.name());
            assertThat(body.path("sizeBytes").asLong()).isEqualTo(3);
            assertThat(Instant.parse(body.path("expiresAt").asText())).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(301));
            assertThat(body.has("objectKey")).isFalse(); assertThat(body.has("sourceUrl")).isFalse();
            verify(storage).signedGet(eq(owner.id()), eq(fragment), eq(kind), anyString());
        }
        verify(storage, never()).signedGetForTranscription(anyLong(), anyLong(), anyString(), any());
    }
    @Test void hidesForeignAndMissingMediaAndRequiresAuthentication() {
        var owner = login(); var other = login(); long fragment = submit(owner.token()); seed(owner.id(), fragment, MediaKind.VIDEO);
        assertThat(http.getForEntity("/api/v1/fragments/" + fragment + "/media", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var foreign = get(other.token(), fragment, "userId=" + owner.id());
        var missing = get(other.token(), Long.MAX_VALUE, "");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().path("code").asText()).isEqualTo(missing.getBody().path("code").asText()).isEqualTo("MEDIA_NOT_FOUND");
        assertThat(get(owner.token(), fragment, "kind=COVER").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner.token(), fragment, "kind=OTHER").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); verifyNoInteractions(storage);
    }
    @Test void mapsProviderFailuresWithoutExposingSignedAddressesOrDetails() {
        var owner = login(); long fragment = submit(owner.token()); seed(owner.id(), fragment, MediaKind.VIDEO);
        doThrow(new OssStorageFailure(OssStorageFailure.Code.ACCESS_DENIED, false)).when(storage).signedGet(anyLong(), anyLong(), any(), anyString());
        var result = get(owner.token(), fragment, ""); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody().path("code").asText()).isEqualTo("MEDIA_UNAVAILABLE"); assertThat(result.getBody().has("url")).isFalse();
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "PLAYBACK_TEST_ENABLED", matches = "true")
    void realApiReturnsPrivateOssUrlSupportingRange() throws Exception {
        var owner = cloudOwner(); long fragment = submit(owner.token());
        var properties = new OssProperties(true, System.getenv("OSS_BUCKET"), System.getenv("OSS_ENDPOINT"), 5 * 1024 * 1024, 1048576, Duration.ofMinutes(5));
        var credentials = new AliyunCredentialsProperties(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"), System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"), System.getenv("ALIBABA_CLOUD_SECURITY_TOKEN"));
        var client = new OssConfiguration().ossClient(properties, credentials); var real = new OssMediaStorage(client, properties, Clock.systemUTC());
        StoredMedia uploaded = null;
        try {
            real.verifyPrivateBucket(); Path fixture = Path.of(System.getenv("PLAYBACK_TEST_MEDIA_PATH"));
            assertThat(Files.size(fixture)).isBetween(16L, 5L * 1024 * 1024);
            // A synthetic WAV validates private byte delivery; Android video decoding is a separate player test.
            uploaded = real.put(owner.id(), fragment, MediaKind.VIDEO, fixture, "application/octet-stream");
            media.insert(new StoredMediaRecord(fragment, owner.id(), MediaKind.VIDEO, uploaded.bucket(), uploaded.key(), uploaded.sizeBytes(), uploaded.sha256(), uploaded.contentType()));
            doAnswer(invocation -> real.signedGet(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)))
                    .when(storage).signedGet(anyLong(), anyLong(), any(), anyString());
            var response = get(owner.token(), fragment, ""); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var signed = URI.create(response.getBody().path("url").asText());
            assertThat(signed.getScheme()).isEqualTo("https"); assertThat(signed.getHost()).isEqualTo(properties.bucket() + "." + URI.create(properties.normalizedEndpoint()).getHost());
            var network = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            var range = network.send(HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(30)).header("Range", "bytes=0-15").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(range.statusCode()).isEqualTo(206); assertThat(range.body()).hasSize(16);
            try (var input = Files.newInputStream(fixture)) { assertThat(range.body()).containsExactly(input.readNBytes(16)); }
            var unsigned = URI.create(signed.getScheme() + "://" + signed.getHost() + signed.getRawPath());
            var denied = network.send(HttpRequest.newBuilder(unsigned).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(get(login().token(), fragment, "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            System.out.println("Real media API: authenticated ownership, 5-minute V4 signature, 206 byte range and unsigned 403 verified.");
        } finally {
            try { if (uploaded != null) real.delete(owner.id(), fragment, MediaKind.VIDEO, uploaded.key()); }
            finally { client.shutdown(); }
        }
    }

    record Owner(long id, String token) { }
    private Owner cloudOwner() {
        var user = new UserAccount();
        user.setId(System.currentTimeMillis() * 1000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000));
        String name = "oss_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        user.setUsername(name); user.setUsernameNormalized(name); user.setPasswordHash(passwords.encode("Integration-password-123"));
        users.insert(user); owned.add(user.getId());
        var response = http.postForEntity("/api/v1/auth/login", Map.of("username", name, "password", "Integration-password-123"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Owner(user.getId(), response.getBody().path("accessToken").asText());
    }
    private Owner login() {
        var credentials = Map.of("username", "play_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class); assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = registered.getBody().path("id").asLong(); owned.add(id);
        var response = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Owner(id, response.getBody().path("accessToken").asText());
    }
    private long submit(String token) {
        var headers = headers(token); headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var response = http.postForEntity("/api/v1/fragments", new HttpEntity<>(Map.of("shareText", "https://b23.tv/" + UUID.randomUUID() + "/"), headers), SubmissionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); return response.getBody().fragmentId();
    }
    private void seed(long owner, long fragment, MediaKind kind) {
        String hash = "a".repeat(64), bucket = liveEnabled() ? System.getenv("OSS_BUCKET") : "test-bucket";
        media.insert(new StoredMediaRecord(fragment, owner, kind, bucket, "users/" + owner + "/fragments/" + fragment + "/" + kind.segment() + "/" + hash, 3, hash, kind == MediaKind.VIDEO ? "video/mp4" : "image/png"));
    }
    private HttpHeaders headers(String token) { var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON); return headers; }
    private ResponseEntity<JsonNode> get(String token, long fragment, String query) { return http.exchange("/api/v1/fragments/" + fragment + "/media?" + query, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class); }
}
