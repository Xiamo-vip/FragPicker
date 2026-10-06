package com.fragpicker.chat.persistence;

import java.time.LocalDateTime;

public record StoredChatTurn(long id, long sessionId, long userId, String idempotencyKey, String question, String state,
        String answer, String errorCode, String leaseToken, LocalDateTime leaseExpiresAt, int authVersion, LocalDateTime createdAt,
        LocalDateTime completedAt, Boolean contextTruncated, Integer modelRounds, Integer toolCalls) {
    @Override public String toString() { return "StoredChatTurn[content=REDACTED]"; }
}
