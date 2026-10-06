package com.fragpicker.chat.api;

import com.fragpicker.auth.CurrentUser;
import com.fragpicker.chat.persistence.*;
import com.fragpicker.common.api.ApiException;
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
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(turns.get(user.id(), id(sessionId), id(turnId)));
    }
    private long id(String value) {
        try { if (value == null || !value.matches("[0-9]{1,19}")) throw new NumberFormatException(); long id = Long.parseLong(value); if (id < 1) throw new NumberFormatException(); return id; }
        catch (NumberFormatException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_ID", "请使用有效的对话和消息编号"); }
    }
}
