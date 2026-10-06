package com.fragpicker.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigurationTest {
    @Test
    void rejectsMissingAndWeakSigningKeys() {
        var config = new SecurityConfiguration();
        assertThatThrownBy(() -> config.jwtKey(new AuthProperties(null, Duration.ofMinutes(15), Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SIGNING_KEY");
        assertThatThrownBy(() -> config.jwtKey(new AuthProperties(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofMinutes(15), Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
    }

    @Test
    void rejectsUnboundedAccessTokenLifetime() {
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        assertThatThrownBy(() -> new SecurityConfiguration().jwtKey(new AuthProperties(key, Duration.ofDays(30), Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_ACCESS_TOKEN_TTL");
    }
}
