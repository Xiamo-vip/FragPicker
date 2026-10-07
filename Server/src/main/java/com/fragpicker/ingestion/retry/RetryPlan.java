package com.fragpicker.ingestion.retry;

public record RetryPlan(String stage, boolean requiresNewTranscription) { }
