package com.fragpicker.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class RegistrationIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private UserAccountMapper users;
    @Autowired private PasswordEncoder passwords;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }

    @Test
    void registersAndStoresOnlySaltedHash() {
        var username = uniqueName();
        var password = "Test-password-123";
        var response = register(username, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains(username).doesNotContain(password, "passwordHash");
        var saved = users.selectOne(Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getUsernameNormalized, username.toLowerCase()));
        assertThat(saved.getPasswordHash()).startsWith("$2a$12$");
        assertThat(passwords.matches(password, saved.getPasswordHash())).isTrue();
        assertThat(passwords.matches("wrong-password", saved.getPasswordHash())).isFalse();
    }

    @Test
    void rejectsCaseInsensitiveDuplicateUsername() {
        var username = uniqueName();
        assertThat(register(username, "Test-password-123").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var duplicate = register(username.toUpperCase(), "Test-password-456");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("USERNAME_TAKEN");
    }

    @Test
    void rejectsInvalidInputsAndMalformedJsonWithoutEchoingPasswords() {
        var shortPassword = register("bad name", "short");
        assertThat(shortPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(shortPassword.getBody()).contains("VALIDATION_ERROR").doesNotContain("short");
        var longUtf8 = register(uniqueName(), "数".repeat(25));
        assertThat(longUtf8.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longUtf8.getBody()).contains("PASSWORD_TOO_LONG");
        assertThat(http.postForEntity("/api/v1/auth/register", Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var malformed = http.postForEntity("/api/v1/auth/register", new HttpEntity<>("{broken-json", headers), String.class);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody()).contains("INVALID_JSON").doesNotContain("broken-json");
    }

    private org.springframework.http.ResponseEntity<String> register(String username, String password) {
        return http.postForEntity("/api/v1/auth/register", Map.of("username", username, "password", password), String.class);
    }

    private String uniqueName() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
