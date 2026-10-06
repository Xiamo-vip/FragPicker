package com.fragpicker.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Profile("database")
public class SessionTokenService {
    private final UserAccountMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final JwtTokenService accessTokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionTokenService(UserAccountMapper users, RefreshTokenMapper refreshTokens,
                               JwtTokenService accessTokens, AuthProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AuthResponse create(Long userId) {
        var user = users.lockById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) { throw invalidRefresh(); }
        return issue(user, UUID.randomUUID().toString(), now().plus(properties.refreshTokenTtl()));
    }

    // Revocation must commit even when replay is reported to the caller as 401.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawToken) {
        String hash = hash(rawToken);
        var candidate = refreshTokens.selectOne(Wrappers.<RefreshTokenRecord>lambdaQuery().eq(RefreshTokenRecord::getTokenHash, hash));
        if (candidate == null) { throw invalidRefresh(); }
        // All session operations acquire the user lock first, preventing refresh/logout deadlocks.
        var user = users.lockById(candidate.getUserId());
        var stored = refreshTokens.lockByHash(hash);
        if (user == null || stored == null || !Boolean.TRUE.equals(user.getEnabled())) { throw invalidRefresh(); }
        if (!stored.getExpiresAt().isAfter(now())) { throw invalidRefresh(); }
        if (stored.getRevokedAt() != null) {
            revokeAllLocked(user);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED", "登录状态已失效，请重新登录");
        }
        stored.setRevokedAt(now());
        refreshTokens.updateById(stored);
        // Rotation retains the original absolute expiry rather than extending the session indefinitely.
        return issue(user, stored.getFamilyId(), stored.getExpiresAt());
    }

    private AuthResponse issue(UserAccount user, String familyId, LocalDateTime expiresAt) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var stored = new RefreshTokenRecord();
        stored.setUserId(user.getId());
        stored.setTokenHash(hash(rawToken));
        stored.setFamilyId(familyId);
        stored.setExpiresAt(expiresAt);
        refreshTokens.insert(stored);
        return new AuthResponse(accessTokens.issueAccessToken(user), "Bearer", accessTokens.expiresInSeconds(),
                rawToken, Math.max(0, Duration.between(now(), expiresAt).toSeconds()), UserResponse.from(user));
    }

    @Transactional
    public void logout(Long userId) {
        var user = users.lockById(userId);
        if (user == null) { throw invalidRefresh(); }
        revokeAllLocked(user);
    }

    void revokeAllLocked(UserAccount user) {
        refreshTokens.update(null, Wrappers.<RefreshTokenRecord>lambdaUpdate()
                .eq(RefreshTokenRecord::getUserId, user.getId()).isNull(RefreshTokenRecord::getRevokedAt)
                .set(RefreshTokenRecord::getRevokedAt, now()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.updateById(user);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "登录状态已失效，请重新登录");
    }
}
