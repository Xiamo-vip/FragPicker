package com.fragpicker.chat;

import java.time.Instant;

public record ChatSessionResponse(long sessionId, String title, Instant createdAt, Instant updatedAt, boolean replayed) {
    @Override public String toString() { return "ChatSessionResponse[title=REDACTED]"; }
}
