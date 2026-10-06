package com.fragpicker.ingestion;

public record TranscriptionLease(long jobId, long fragmentId, long userId, long version, String owner) { }
