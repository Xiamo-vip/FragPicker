package com.fragpicker.chat;

import java.time.Instant;
import java.util.List;

public record ChatSessionListResponse(List<Item> items, String nextCursor) {
    public ChatSessionListResponse { items = List.copyOf(items); }
    @Override public String toString() { return "ChatSessionListResponse[content=REDACTED]"; }
    public record Item(long sessionId, String title, Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "ChatSessionListItem[content=REDACTED]"; }
    }
}
