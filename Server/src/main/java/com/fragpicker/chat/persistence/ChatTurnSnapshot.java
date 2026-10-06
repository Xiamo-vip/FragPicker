package com.fragpicker.chat.persistence;

import com.fragpicker.knowledge.search.SearchResponse;
import java.time.Instant;
import java.util.List;

public record ChatTurnSnapshot(long turnId, long sessionId, String question, String state, String answer, String errorCode,
        Instant createdAt, Instant completedAt, Boolean contextTruncated, Integer modelRounds, Integer toolCalls, List<SearchResponse.Hit> cards) {
    public ChatTurnSnapshot { cards = List.copyOf(cards); }
    @Override public String toString() { return "ChatTurnSnapshot[content=REDACTED]"; }
}
