package com.fragpicker.chat;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Profile("database")
public class ChatSessionService {
    private final UserAccountMapper users;
    private final ChatSessionMapper sessions;
    private final Clock clock;
    public ChatSessionService(UserAccountMapper users, ChatSessionMapper sessions, Clock clock) {
        this.users = users; this.sessions = sessions; this.clock = clock;
    }
    @Transactional
    public ChatSessionResponse create(long owner, String rawKey, CreateSessionRequest input) {
        String key = key(rawKey), title = title(input);
        // Same per-user lock order as auth/submission; release before any future model call.
        var user = users.lockById(owner);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "请重新登录");
        var prior = sessions.byRequest(owner, key);
        if (prior != null) {
            if (!prior.getTitle().equals(title)) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "该幂等键已用于不同的对话标题");
            return response(prior, true);
        }
        var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        var saved = new ChatSessionRecord(); saved.setUserId(owner); saved.setIdempotencyKey(key); saved.setTitle(title);
        saved.setCreatedAt(now); saved.setUpdatedAt(now); sessions.insert(saved);
        return response(saved, false);
    }
    private ChatSessionResponse response(ChatSessionRecord saved, boolean replayed) {
        return new ChatSessionResponse(saved.getId(), saved.getTitle(), saved.getCreatedAt().toInstant(ZoneOffset.UTC), saved.getUpdatedAt().toInstant(ZoneOffset.UTC), replayed);
    }
    private String title(CreateSessionRequest input) {
        if (input == null) throw invalidTitle();
        String value = input.title() == null ? "" : input.title().strip();
        if (value.isEmpty()) return "新对话";
        if (value.length() > 200 || value.codePointCount(0, value.length()) > 100
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp >= 0xD800 && cp <= 0xDFFF)) throw invalidTitle();
        return value;
    }
    private ApiException invalidTitle() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_TITLE", "对话标题最多100个字符，不得包含控制字符"); }
    private String key(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "请使用 UUID 格式的 Idempotency-Key");
        return UUID.fromString(value).toString();
    }
}
