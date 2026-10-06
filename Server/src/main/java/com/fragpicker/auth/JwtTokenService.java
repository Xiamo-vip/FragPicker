package com.fragpicker.auth;

import com.fragpicker.user.UserAccount;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("database")
public class JwtTokenService {
    public static final String ISSUER = "fragpicker";
    public static final String AUDIENCE = "fragpicker-android";
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueAccessToken(UserAccount user) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(user.getId().toString()).id(UUID.randomUUID().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("ver", user.getTokenVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public long expiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
