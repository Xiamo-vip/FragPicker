package com.fragpicker.chat.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.chat.turn.*;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.search.SearchResponse;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Profile("database")
public class ChatTurnStore {
    private final UserAccountMapper users;
    private final ChatTurnMapper turns;
    private final ChatTurnProperties properties;
    private final ObjectMapper json;
    public ChatTurnStore(UserAccountMapper users, ChatTurnMapper turns, ChatTurnProperties properties, ObjectMapper json) {
        this.users = users; this.turns = turns; this.properties = properties; this.json = json;
    }
    @Transactional
    public Start begin(long owner, long session, String rawKey, String rawQuestion) {
        String question = ChatMessageText.normalize(rawQuestion), key = key(rawKey);
        var user = users.lockById(owner); if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        lockSession(owner, session); turns.expire(owner, session);
        var prior = turns.byKey(owner, session, key);
        if (prior != null) { if (!question.equals(prior.question())) throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"); return new Start(prior, false); }
        if (turns.running(owner, session) > 0) throw error(HttpStatus.CONFLICT, "CHAT_SESSION_BUSY");
        turns.insert(owner, session, key, question, UUID.randomUUID().toString(), properties.timeout().toSeconds() + 31, user.getTokenVersion()); turns.touch(owner, session);
        return new Start(turns.byKey(owner, session, key), true);
    }
    @Transactional
    public boolean complete(StoredChatTurn ticket, ChatTurnResult result) {
        var user = users.lockById(ticket.userId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        lockSession(ticket.userId(), ticket.sessionId()); var valid = turns.valid(ticket.userId(), ticket.sessionId(), ticket.id(), ticket.leaseToken()); if (valid == null) return false;
        if (user.getTokenVersion() != valid.authVersion()) throw error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID");
        if (result == null || result.answer() == null || result.answer().isBlank() || result.answer().length() > 24000 || result.cards().size() > 15
                || result.modelRounds() < 1 || result.modelRounds() > 4 || result.toolCalls() < 0 || result.toolCalls() > 3) throw error(HttpStatus.BAD_GATEWAY, "CHAT_OUTPUT_INVALID");
        var ids = new HashSet<Long>(); int ordinal = 0;
        for (var card : result.cards()) {
            if (!ids.add(card.fragmentId()) || turns.source(ticket.userId(), card.fragmentId()) == null) throw error(HttpStatus.CONFLICT, "CHAT_SOURCE_UNAVAILABLE");
            String path = "/api/v1/fragments/" + card.fragmentId() + "/media?kind=";
            if (card.videoMediaPath() != null && !card.videoMediaPath().equals(path + "VIDEO") || card.coverMediaPath() != null && !card.coverMediaPath().equals(path + "COVER")) throw error(HttpStatus.BAD_GATEWAY, "CHAT_OUTPUT_INVALID");
            try {
                String snapshot = json.writeValueAsString(card); if (snapshot.getBytes(StandardCharsets.UTF_8).length > 32768) throw error(HttpStatus.BAD_GATEWAY, "CHAT_OUTPUT_INVALID");
                turns.sourceSnapshot(ticket.userId(), ticket.id(), card.fragmentId(), ordinal++, snapshot);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.BAD_GATEWAY, "CHAT_OUTPUT_INVALID"); }
        }
        turns.complete(ticket.id(), result.answer(), result.contextTruncated(), result.modelRounds(), result.toolCalls()); turns.touch(ticket.userId(), ticket.sessionId()); return true;
    }
    @Transactional
    public boolean fail(StoredChatTurn ticket, String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("Expected a stable error code");
        if (users.lockById(ticket.userId()) == null || turns.lockSession(ticket.userId(), ticket.sessionId()) == null) return false;
        boolean changed = turns.fail(ticket.userId(), ticket.sessionId(), ticket.id(), ticket.leaseToken(), code) == 1; if (changed) turns.touch(ticket.userId(), ticket.sessionId()); return changed;
    }
    @Transactional
    public ChatTurnSnapshot get(long owner, long session, long id) {
        if (users.lockById(owner) == null) throw error(HttpStatus.NOT_FOUND, "CHAT_TURN_NOT_FOUND"); lockSession(owner, session); turns.expire(owner, session);
        var row = turns.get(owner, session, id); if (row == null) throw error(HttpStatus.NOT_FOUND, "CHAT_TURN_NOT_FOUND");
        return snapshot(owner, row);
    }
    @Transactional
    public ChatTurnPage list(long owner, long session, Long before, int limit) {
        if (limit < 1 || limit > 20 || before != null && before < 1) throw error(HttpStatus.BAD_REQUEST, "INVALID_CHAT_PAGE");
        if (users.lockById(owner) == null) throw error(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND"); lockSession(owner, session); turns.expire(owner, session);
        var rows = turns.page(owner, session, before, limit + 1);
        var visible = rows.subList(0, Math.min(rows.size(), limit));
        Long next = rows.size() > limit ? visible.getLast().id() : null;
        return new ChatTurnPage(visible.stream().map(row -> snapshot(owner, row)).toList(), next);
    }
    private ChatTurnSnapshot snapshot(long owner, StoredChatTurn row) {
        var cards = new ArrayList<SearchResponse.Hit>();
        try { for (var source : turns.snapshots(owner, row.id())) {
            var card = json.readValue(source.snapshot(), SearchResponse.Hit.class); String path = "/api/v1/fragments/" + source.fragmentId() + "/media?kind=";
            if (card.fragmentId() != source.fragmentId() || card.match() == null || card.businessDate() == null
                    || card.videoMediaPath() != null && !card.videoMediaPath().equals(path + "VIDEO") || card.coverMediaPath() != null && !card.coverMediaPath().equals(path + "COVER")) throw error(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_STORAGE_INVALID");
            cards.add(card);
        } }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_STORAGE_INVALID"); }
        return new ChatTurnSnapshot(row.id(), row.sessionId(), row.question(), row.state(), row.answer(), row.errorCode(), row.createdAt().toInstant(ZoneOffset.UTC), row.completedAt() == null ? null : row.completedAt().toInstant(ZoneOffset.UTC), row.contextTruncated(), row.modelRounds(), row.toolCalls(), cards);
    }
    @Transactional(readOnly = true)
    public List<ConversationExchange> context(StoredChatTurn ticket) {
        if (turns.ownedSession(ticket.userId(), ticket.sessionId()) == null) throw error(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND");
        var past = turns.context(ticket.userId(), ticket.sessionId(), ticket.id()); var result = new ArrayList<ConversationExchange>();
        for (var item : past.reversed()) result.add(new ConversationExchange(item.question(), item.answer())); return List.copyOf(result);
    }
    private void lockSession(long owner, long session) { if (turns.lockSession(owner, session) == null) throw error(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND"); }
    private String key(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw error(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY"); return UUID.fromString(raw).toString();
    }
    private ApiException error(HttpStatus status, String code) { return new ApiException(status, code, "请检查对话状态或重新连接"); }
    public record Start(StoredChatTurn turn, boolean fresh) { @Override public String toString() { return "ChatTurnStart[content=REDACTED]"; } }
}
