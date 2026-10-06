package com.fragpicker.chat.persistence;

import java.util.List;

public record ChatTurnPage(List<ChatTurnSnapshot> items, Long nextBefore) {
    public ChatTurnPage { items = List.copyOf(items); }
    @Override public String toString() { return "ChatTurnPage[content=REDACTED]"; }
}
