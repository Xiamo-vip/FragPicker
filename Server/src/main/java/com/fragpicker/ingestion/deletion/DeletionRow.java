package com.fragpicker.ingestion.deletion;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DeletionRow(long fragmentId,long userId,LocalDate businessDate,String buckets,String status,
    long version,int attempts,LocalDateTime deletedAt,LocalDateTime nextScanAt,String leaseOwner,LocalDateTime leaseExpiresAt) { }
