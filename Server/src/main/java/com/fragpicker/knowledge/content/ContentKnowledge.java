package com.fragpicker.knowledge.content;

import java.time.LocalDateTime;

public record ContentKnowledge(long durationMs, String originalSummaryPreview, boolean originalSummaryTruncated, String summary,
                               boolean summaryTruncated, String points, String keywords, String categories, LocalDateTime completedAt, LocalDateTime enrichedAt) {
    @Override public String toString() { return "ContentKnowledge[content=REDACTED]"; }
}
