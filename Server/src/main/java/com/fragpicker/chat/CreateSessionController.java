package com.fragpicker.chat;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@Profile("database")
@RequestMapping("/api/v1/chat/sessions")
public class CreateSessionController {
    private final ChatSessionService sessions;
    public CreateSessionController(ChatSessionService sessions) { this.sessions = sessions; }
    @PostMapping
    public ResponseEntity<ChatSessionResponse> create(@AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody CreateSessionRequest request) {
        var result = sessions.create(user.id(), key, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/chat/sessions/" + result.sessionId())).cacheControl(CacheControl.noStore()).body(result);
    }
}
