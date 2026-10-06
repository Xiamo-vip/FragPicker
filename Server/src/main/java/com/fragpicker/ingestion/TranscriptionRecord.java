package com.fragpicker.ingestion;

import java.time.LocalDateTime;

public record TranscriptionRecord(long fragmentId, long userId, String taskKey, String taskId, LocalDateTime submittedAt) {
    @Override public String toString() { return "TranscriptionRecord[fragmentId=" + fragmentId + ", cloudFields=REDACTED]"; }
}
