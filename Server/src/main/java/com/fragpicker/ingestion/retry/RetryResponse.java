package com.fragpicker.ingestion.retry;

public record RetryResponse(long fragmentId, String status, String nextStage, long jobVersion, boolean duplicate) { }
