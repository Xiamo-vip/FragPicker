package com.fragpicker.chat.turn;

import com.fragpicker.knowledge.search.SearchResponse;
import java.util.List;

public record ChatTurnResult(String answer, List<SearchResponse.Hit> cards, boolean contextTruncated, int modelRounds, int toolCalls) {
    public ChatTurnResult { cards = List.copyOf(cards); }
    @Override public String toString() { return "ChatTurnResult[content=REDACTED]"; }
}
