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
@RequestMapping("/api/v1/chat/sessions/{sessionId}/messages")
public class ChatTurnListController {
    private final ChatTurnStore turns;
    public ChatTurnListController(ChatTurnStore turns) { this.turns = turns; }
    @GetMapping
    public ResponseEntity<ChatTurnPage> list(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId,
            @RequestParam(required = false) String before, @RequestParam(required = false) String limit) {
        long session = ChatIds.parse(sessionId); int count = 10; Long position = null;
        if (limit != null) {
            if (!limit.matches("[0-9]{1,2}")) throw invalid();
            count = Integer.parseInt(limit); if (count < 1 || count > 20) throw invalid();
        }
        if (before != null) { try { position = ChatIds.parse(before); } catch (ApiException malformed) { throw invalid(); } }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(turns.list(user.id(), session, position, count));
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_PAGE", "请使用正整数消息游标和1至20的页大小"); }
}
