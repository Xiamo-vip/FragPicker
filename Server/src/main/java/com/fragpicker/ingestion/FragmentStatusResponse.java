package com.fragpicker.ingestion;

import java.time.Instant;
import java.time.LocalDate;

public record FragmentStatusResponse(Long id, String sourceUrl, String sourceHost, String note,
                                     LocalDate businessDate, String businessZone, Instant createdAt,
                                     String status, int attemptCount, String errorCode) { }
