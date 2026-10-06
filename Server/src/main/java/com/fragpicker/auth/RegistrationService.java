package com.fragpicker.auth;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.user.UserAccount;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@Profile("database")
public class RegistrationService {
    private final UserAccountMapper users;
    private final PasswordEncoder passwords;

    public RegistrationService(UserAccountMapper users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "密码UTF-8长度不能超过72字节");
        }
        var user = new UserAccount();
        user.setUsername(request.username());
        user.setUsernameNormalized(request.username().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwords.encode(request.password()));
        user.setBusinessZone("Asia/Shanghai");
        try {
            users.insert(user);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被使用");
        }
        return UserResponse.from(user);
    }
}
