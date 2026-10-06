package com.fragpicker.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("database")
public class LogoutController {
    private final SessionTokenService sessions;
    public LogoutController(SessionTokenService sessions) { this.sessions = sessions; }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal CurrentUser user) {
        sessions.logout(user.id());
    }
}
