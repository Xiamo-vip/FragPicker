package com.fragpicker.chat;

import java.time.LocalDateTime;

public record ChatSessionListRow(long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {
    @Override public String toString() { return "ChatSessionListRow[content=REDACTED]"; }
}
