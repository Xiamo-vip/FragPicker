package com.fragpicker.knowledge;

public record EnrichmentLease(long jobId, long fragmentId, long userId, long version, String owner, int attempt) { }
