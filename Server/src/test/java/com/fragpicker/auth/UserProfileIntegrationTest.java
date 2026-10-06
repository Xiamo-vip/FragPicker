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
class UserProfileIntegrationTest {
    @Autowired private TestRestTemplate http;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void returnsOnlyAuthenticatedUsersProfileAndNoPasswordHash() {
        var username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var credentials = Map.of("username", username, "password", "Test-password-123");
        var registered = http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class).getBody();
        var session = http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class).getBody();
        var headers = new HttpHeaders();
        headers.setBearerAuth(session.path("accessToken").asText());
        var response = http.exchange("/api/v1/users/me?userId=999999", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("id").asLong()).isEqualTo(registered.path("id").asLong());
        assertThat(response.getBody().path("username").asText()).isEqualTo(username);
        assertThat(response.getBody().has("passwordHash")).isFalse();
        assertThat(response.getBody().has("password")).isFalse();
    }

    @Test
    void anonymousProfileAccessIsRejected() {
        assertThat(http.getForEntity("/api/v1/users/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
