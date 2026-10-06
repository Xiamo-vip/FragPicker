package com.fragpicker.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
@Profile("database")
public class LoginService {
    private final UserAccountMapper users;
    private final PasswordEncoder passwords;
    private final SessionTokenService sessions;
    private final String dummyHash;

    public LoginService(UserAccountMapper users, PasswordEncoder passwords, SessionTokenService sessions) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public AuthResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) { throw invalidCredentials(); }
        var user = users.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsernameNormalized, request.username().toLowerCase(Locale.ROOT)));
        boolean matched = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matched || user == null || !Boolean.TRUE.equals(user.getEnabled())) { throw invalidCredentials(); }
        return sessions.create(user.getId());
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }
}
