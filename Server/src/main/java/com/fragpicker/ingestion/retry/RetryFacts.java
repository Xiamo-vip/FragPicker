package com.fragpicker.ingestion.retry;

public record RetryFacts(boolean knowledge, boolean enriched, boolean metadata, boolean video, boolean coverRequired, boolean cover) { }
