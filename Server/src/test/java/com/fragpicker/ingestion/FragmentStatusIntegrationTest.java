package com.fragpicker.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class FragmentStatusIntegrationTest {
    @Autowired private TestRestTemplate http;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void returnsOwnDurableStateAndUtcTimestampWithoutWorkerInternals() {
        var token = login();
        var headers = headers(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var submitted = http.postForEntity("/api/v1/fragments", new HttpEntity<>(Map.of(
                "shareText", "https://v.douyin.com/status/", "note", "学习导数"), headers), SubmissionResponse.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var result = get(token, submitted.getBody().fragmentId());
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = result.getBody();
        assertThat(body.path("note").asText()).isEqualTo("学习导数");
        assertThat(body.path("status").asText()).isEqualTo("QUEUED");
        assertThat(body.path("attemptCount").asInt()).isZero();
        assertThat(body.path("errorCode").isNull()).isTrue();
        assertThat(Instant.parse(body.path("createdAt").asText())).isBetween(Instant.now().minusSeconds(30), Instant.now());
        assertThat(body.has("leaseOwner")).isFalse();
        assertThat(body.has("sourceHash")).isFalse();
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void hidesAnotherUsersRecordAndIgnoresUserIdQueryParameters() {
        var owner = login();
        var stranger = login();
        var headers = headers(owner); headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var submitted = http.postForEntity("/api/v1/fragments", new HttpEntity<>(Map.of("shareText", "https://b23.tv/owned/"), headers), SubmissionResponse.class);
        long id = submitted.getBody().fragmentId();
        assertThat(get(stranger, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var injected = http.exchange("/api/v1/fragments/" + id + "?userId=1", HttpMethod.GET,
                new HttpEntity<>(headers(stranger)), JsonNode.class);
        assertThat(injected.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(owner, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void requiresAuthenticationAndReturnsNotFoundForMissingRecords() {
        assertThat(http.getForEntity("/api/v1/fragments/1", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(login(), Long.MAX_VALUE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<JsonNode> get(String token, long id) {
        return http.exchange("/api/v1/fragments/" + id, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }
    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON); return headers;
    }
    private String login() {
        var credentials = Map.of("username", "status_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "password", "Integration-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register", credentials, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var response = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("accessToken").asText();
    }
}
