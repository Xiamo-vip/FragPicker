package com.fragpicker.knowledge.content;

import java.time.*;

public record ContentHeader(long id, String sourceUrl, String sourceHost, String note, LocalDate businessDate, String businessZone,
                            LocalDateTime createdAt, String status, String errorCode, String title, boolean titleTruncated, String author,
                            boolean authorTruncated, boolean video, boolean cover, long sentenceCount, long keyPointCount) {
    @Override public String toString() { return "ContentHeader[content=REDACTED]"; }
}
