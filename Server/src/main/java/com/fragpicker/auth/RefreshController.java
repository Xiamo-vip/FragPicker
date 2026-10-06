package com.fragpicker.auth;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/auth")
public class RefreshController {
    private final SessionTokenService sessions;
    public RefreshController(SessionTokenService sessions) { this.sessions = sessions; }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.refresh(request.refreshToken()));
    }
}
