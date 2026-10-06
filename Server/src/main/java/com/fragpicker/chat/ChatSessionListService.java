package com.fragpicker.chat;

import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;

@Service
@Profile("database")
public class ChatSessionListService {
    private final ChatSessionMapper sessions;
    public ChatSessionListService(ChatSessionMapper sessions) { this.sessions = sessions; }
    @Transactional(readOnly = true)
    public ChatSessionListResponse list(long owner, String rawLimit, String rawCursor) {
        int limit = limit(rawLimit); var cursor = cursor(rawCursor);
        var rows = sessions.page(owner, cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(), limit + 1);
        var visible = rows.subList(0, Math.min(limit, rows.size()));
        String next = rows.size() > limit ? encode(visible.getLast().updatedAt().toInstant(ZoneOffset.UTC), visible.getLast().id()) : null;
        var items = visible.stream().map(row -> new ChatSessionListResponse.Item(row.id(), row.title(),
                row.createdAt().toInstant(ZoneOffset.UTC), row.updatedAt().toInstant(ZoneOffset.UTC))).toList();
        return new ChatSessionListResponse(items, next);
    }
    private int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[0-9]{1,2}")) throw invalid();
        int limit = Integer.parseInt(value); if (limit < 1 || limit > 50) throw invalid(); return limit;
    }
    private Cursor cursor(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[A-Za-z0-9_-]{1,96}")) throw invalid();
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            var parts = plain.split("\\|", -1); if (parts.length != 2 || !parts[1].matches("[0-9]{1,19}")) throw invalid();
            var instant = Instant.parse(parts[0]); var time = LocalDateTime.ofInstant(instant, ZoneOffset.UTC); long id = Long.parseLong(parts[1]);
            if (time.getYear() < 1000 || time.getYear() > 9999 || instant.getNano() % 1_000_000 != 0 || id < 1
                    || !encode(instant, id).equals(value)) throw invalid();
            return new Cursor(time, id);
        } catch (IllegalArgumentException | DateTimeException bad) { throw invalid(); }
    }
    private String encode(Instant instant, long id) { return Base64.getUrlEncoder().withoutPadding().encodeToString((instant + "|" + id).getBytes(StandardCharsets.UTF_8)); }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_PAGE", "请使用有效的分页游标和1至50的页大小"); }
    private record Cursor(LocalDateTime time, long id) { }
}
