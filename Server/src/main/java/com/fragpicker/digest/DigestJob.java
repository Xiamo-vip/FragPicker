package com.fragpicker.digest;

import java.time.*;

public record DigestJob(long id, long userId, LocalDate businessDate, String status, long requestedRevision,
        long workingRevision, long completedRevision, String resultJson, long sourceCount, long modelCalls,
        String completedSourceHash, String workingSourceHash, long workingSourceCount, String inFlightHash,
        String errorCode, LocalDateTime generatedAt, LocalDateTime lastManualAt) {
    @Override public String toString() { return "DigestJob[id=" + id + ", status=" + status + ", content=REDACTED]"; }
}
