package com.fragpicker.auth;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class LogoutIntegrationTest {
    @Autowired private TestRestTemplate http;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void logoutRevokesCurrentUsersTokensWithoutAffectingAnotherUser() {
        var own = login();
        var other = login();
        var headers = bearer(own.path("accessToken").asText());
        assertThat(http.exchange("/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.exchange("/api/v1/protected-check", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", own.path("refreshToken").asText()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/protected-check", HttpMethod.GET, new HttpEntity<>(bearer(other.path("accessToken").asText())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cannotLogoutWithoutAuthentication() {
        assertThat(http.postForEntity("/api/v1/auth/logout", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private JsonNode login() {
        var credentials = Map.of("username", "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), "password", "Test-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class).getBody();
    }
}
