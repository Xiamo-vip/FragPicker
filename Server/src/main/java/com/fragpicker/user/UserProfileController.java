package com.fragpicker.user;

import com.fragpicker.auth.CurrentUser;
import com.fragpicker.auth.UserResponse;
import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database")
public class UserProfileController {
    private final UserAccountMapper users;
    public UserProfileController(UserAccountMapper users) { this.users = users; }

    @GetMapping("/api/v1/users/me")
    public UserResponse currentUser(@AuthenticationPrincipal CurrentUser principal) {
        var user = users.selectById(principal.id());
        if (user == null) { throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请重新登录"); }
        return UserResponse.from(user);
    }
}
