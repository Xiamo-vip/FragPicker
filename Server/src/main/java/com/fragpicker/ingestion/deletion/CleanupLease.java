package com.fragpicker.ingestion.deletion;

public record CleanupLease(long userId,long fragmentId,String buckets,long version,String token,int attempt) {
    @Override public String toString() { return "CleanupLease[fragmentId="+fragmentId+"]"; }
}
