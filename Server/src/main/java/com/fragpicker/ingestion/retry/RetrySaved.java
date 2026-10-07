package com.fragpicker.ingestion.retry;

public record RetrySaved(long fragmentId, String requestHash, String nextStage, long jobVersion) { }
