package com.fragpicker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiError;
import com.fragpicker.user.UserAccountMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Configuration
public class SecurityConfiguration {
    @Bean
    @Profile("bootstrap")
    SecurityFilterChain bootstrapSecurity(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(request -> request.anyRequest().permitAll()).build();
    }

    @Bean
    @Profile("database")
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    @Profile("database")
    SecretKey jwtKey(AuthProperties properties) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.signingKey());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException("JWT_SIGNING_KEY must be a Base64-encoded key of at least 32 bytes");
        }
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SIGNING_KEY must contain at least 32 random bytes");
        }
        var ttl = properties.accessTokenTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalStateException("JWT_ACCESS_TOKEN_TTL must be between 1 second and 1 day");
        }
        var refreshTtl = properties.refreshTokenTtl();
        if (refreshTtl == null || refreshTtl.compareTo(ttl) < 0 || refreshTtl.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalStateException("JWT_REFRESH_TOKEN_TTL must be at least the access token TTL and at most 90 days");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    @Profile("database")
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    @Profile("database")
    JwtDecoder jwtDecoder(SecretKey key) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(JwtTokenService.ISSUER),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(JwtTokenService.AUDIENCE))));
        return decoder;
    }

    @Bean
    @Profile("database")
    SecurityFilterChain applicationSecurity(HttpSecurity http, UserAccountMapper users, ObjectMapper json) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    try {
                        var user = users.selectById(Long.valueOf(token.getSubject()));
                        Number version = token.getClaim("ver");
                        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || version == null
                                || user.getTokenVersion() != version.intValue()) {
                            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                        }
                        return new UsernamePasswordAuthenticationToken(new CurrentUser(user.getId()), null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    } catch (NumberFormatException | ClassCastException invalid) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                })).authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Bearer");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    json.writeValue(response.getOutputStream(), ApiError.of("UNAUTHORIZED", "请登录或刷新登录状态"));
                }));
        return http.build();
    }
}
