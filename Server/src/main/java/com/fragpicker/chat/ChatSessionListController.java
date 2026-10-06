package com.fragpicker.chat;

import com.fragpicker.auth.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/chat/sessions")
public class ChatSessionListController {
    private final ChatSessionListService sessions;
    public ChatSessionListController(ChatSessionListService sessions) { this.sessions = sessions; }
    @GetMapping
    public ResponseEntity<ChatSessionListResponse> list(@AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.list(user.id(), limit, cursor));
    }
}
