package com.fragpicker.chat.api;

import com.fragpicker.auth.CurrentUser;
import com.fragpicker.chat.persistence.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("database")
@RequestMapping("/api/v1/chat/sessions/{sessionId}/messages/{turnId}")
public class ChatTurnReadController {
    private final ChatTurnStore turns;
    public ChatTurnReadController(ChatTurnStore turns) { this.turns = turns; }
    @GetMapping
    public ResponseEntity<ChatTurnSnapshot> get(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId, @PathVariable String turnId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(turns.get(user.id(), ChatIds.parse(sessionId), ChatIds.parse(turnId)));
    }
}
