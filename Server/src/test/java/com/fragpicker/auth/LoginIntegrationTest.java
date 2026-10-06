package com.fragpicker.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fragpicker.user.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class LoginIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtEncoder encoder;
    @Autowired private UserAccountMapper users;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void logsInWithCaseInsensitiveUsernameAndValidatesSignedToken() {
        String name = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var credentials = Map.of("username", name, "password", "Test-password-123");
        assertThat(http.postForEntity("/api/v1/auth/register", credentials, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var response = http.postForEntity("/api/v1/auth/login", Map.of("username", name.toUpperCase(), "password", "Test-password-123"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var token = response.getBody().path("accessToken").asText();
        assertThat(decoder.decode(token).getSubject()).isEqualTo(response.getBody().path("user").path("id").asText());
        assertThat(response.getBody().path("expiresIn").asLong()).isEqualTo(900);
        assertThat(protectedRequest(token)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(protectedRequest(token.substring(0, token.length()-1))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/v1/auth/login", Map.of("username", name, "password", "Wrong-password"), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var account = users.selectById(Long.valueOf(decoder.decode(token).getSubject()));
        account.setTokenVersion(account.getTokenVersion() + 1);
        users.updateById(account);
        assertThat(protectedRequest(token)).isEqualTo(HttpStatus.UNAUTHORIZED);
        account.setEnabled(false);
        users.updateById(account);
        assertThat(http.postForEntity("/api/v1/auth/login", credentials, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMissingExpiredAndWrongAudienceTokens() {
        assertThat(http.getForEntity("/api/v1/protected-check", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var now = Instant.now();
        var expired = JwtClaimsSet.builder().issuer(JwtTokenService.ISSUER).subject("1")
                .audience(List.of(JwtTokenService.AUDIENCE)).issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(1)).claim("ver", 0).build();
        assertThat(protectedRequest(encode(expired))).isEqualTo(HttpStatus.UNAUTHORIZED);
        var wrongAudience = JwtClaimsSet.builder().issuer(JwtTokenService.ISSUER).subject("1")
                .audience(List.of("other-app")).issuedAt(now).expiresAt(now.plusSeconds(900)).claim("ver", 0).build();
        assertThat(protectedRequest(encode(wrongAudience))).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpStatusCode protectedRequest(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange("/api/v1/protected-check", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode();
    }
}
