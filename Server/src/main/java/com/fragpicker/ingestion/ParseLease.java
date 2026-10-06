package com.fragpicker.ingestion;

/** A fencing token, never returned to API clients. */
public record ParseLease(long jobId, long fragmentId, long userId, long version, String owner,
                         int attemptCount, String sourceUrl) {
    @Override public String toString() { return "ParseLease[jobId=" + jobId + ", version=" + version + "]"; }
}
