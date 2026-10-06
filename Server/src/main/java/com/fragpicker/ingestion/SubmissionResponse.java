package com.fragpicker.ingestion;

import java.time.LocalDate;

public record SubmissionResponse(Long fragmentId, Long jobId, String status, LocalDate businessDate, boolean duplicate) { }
