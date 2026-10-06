package com.fragpicker.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class RefreshIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void rotatesTokensAndCommitsReplayRevocation() {
        var initial = login();
        var rotated = refresh(initial.path("refreshToken").asText());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        var next = rotated.getBody().path("refreshToken").asText();
        assertThat(next).isNotEqualTo(initial.path("refreshToken").asText());
        assertThat(rotated.getBody().path("refreshExpiresIn").asLong()).isLessThanOrEqualTo(initial.path("refreshExpiresIn").asLong());
        var replay = refresh(initial.path("refreshToken").asText());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().path("code").asText()).isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(refresh(next).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers = new HttpHeaders();
        headers.setBearerAuth(rotated.getBody().path("accessToken").asText());
        assertThat(http.exchange("/api/v1/protected-check", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL", Integer.class,
                initial.path("user").path("id").asLong())).isZero();
    }

    @Test
    void serializesConcurrentRefreshesAndNeverIssuesTwoActiveSessions() throws Exception {
        var initial = login();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = CompletableFuture.supplyAsync(() -> raceRefresh(start, initial.path("refreshToken").asText()), executor);
            var second = CompletableFuture.supplyAsync(() -> raceRefresh(start, initial.path("refreshToken").asText()), executor);
            start.countDown();
            var responses = java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(ResponseEntity::getStatusCode)
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL", Integer.class,
                    initial.path("user").path("id").asLong())).isZero();
        }
    }

    @Test
    void storesOnlyHashesAndRejectsUnknownExpiredTokens() {
        var session = login();
        long userId = session.path("user").path("id").asLong();
        String raw = session.path("refreshToken").asText();
        var hash = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE user_id=?", String.class, userId);
        assertThat(hash).hasSize(64).isNotEqualTo(raw);
        assertThat(refresh("A".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE refresh_tokens SET expires_at='2000-01-01' WHERE user_id=?", userId);
        assertThat(refresh(raw).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE refresh_tokens SET revoked_at='2000-01-01' WHERE user_id=?", userId);
        var newSession = http.postForEntity("/api/v1/auth/login", Map.of("username", session.path("user").path("username").asText(),
                "password", "Test-password-123"), JsonNode.class).getBody();
        assertThat(refresh(raw).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refresh(newSession.path("refreshToken").asText()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode login() {
        var credentials = Map.of("username", "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), "password", "Test-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var response = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> refresh(String token) {
        return http.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", token), JsonNode.class);
    }

    private ResponseEntity<JsonNode> raceRefresh(CountDownLatch start, String token) {
        try {
            start.await();
            return refresh(token);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
