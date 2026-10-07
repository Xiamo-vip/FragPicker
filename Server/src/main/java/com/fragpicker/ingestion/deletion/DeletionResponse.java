package com.fragpicker.ingestion.deletion;

public record DeletionResponse(long fragmentId,String status,boolean cleanupPending,boolean duplicate) { }
