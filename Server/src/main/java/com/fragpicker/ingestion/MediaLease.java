package com.fragpicker.ingestion;

public record MediaLease(long jobId, long fragmentId, long userId, long version,
                         String owner, int attemptCount, String sourceUrl) {
    @Override public String toString() { return "MediaLease[jobId=" + jobId + ", sensitiveFields=REDACTED]"; }
}
