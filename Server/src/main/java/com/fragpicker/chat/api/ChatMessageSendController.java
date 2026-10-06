package com.fragpicker.chat.api;
import com.fragpicker.auth.CurrentUser;
import com.fragpicker.common.api.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController
@Profile("database")
@RequestMapping("/api/v1/chat/sessions/{sessionId}/messages")
public class ChatMessageSendController {
    private final ChatMessageSseService messages;
    public ChatMessageSendController(ChatMessageSseService messages) { this.messages = messages; }
    @PostMapping
    public ResponseEntity<SseEmitter> send(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody SendChatMessage request) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(messages.send(user, ChatIds.parse(sessionId), key, request));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> error(ApiException error) { return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(ApiError.of(error.code(), error.getMessage())); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> jsonError() { return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(ApiError.of("INVALID_JSON", "请求格式无效")); }
}
