package com.fragpicker.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Profile("database")
@ConfigurationProperties("fragpicker.auth")
public record AuthProperties(String signingKey, Duration accessTokenTtl, Duration refreshTokenTtl) {
    @Override
    public String toString() {
        return "AuthProperties[signingKey=REDACTED, accessTokenTtl=" + accessTokenTtl + "]";
    }
}
