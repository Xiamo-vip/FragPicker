package com.fragpicker.knowledge.content;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.*;
import java.util.List;

public record ContentResponse(long id, String sourceUrl, String sourceHost, String note, LocalDate businessDate, String businessZone,
                              Instant createdAt, String status, String errorCode, String title, boolean titleTruncated, String author,
                              boolean authorTruncated, String videoMediaPath, String coverMediaPath, long sentenceCount, long keyPointCount, Knowledge knowledge,
                              String displayTitle, String introduction) {
    public record Knowledge(long durationMs, String originalSummaryPreview, boolean originalSummaryTruncated, String summary, boolean summaryTruncated,
                            List<String> points, boolean pointsTruncated, List<String> keywords, boolean keywordsTruncated,
                            List<Category> categories, Instant completedAt, Instant enrichedAt) {
        public Knowledge { points = List.copyOf(points); keywords = List.copyOf(keywords); categories = List.copyOf(categories); }
        @Override public String toString() { return "ContentKnowledge[content=REDACTED]"; }
    }
    @Override public String toString() { return "ContentResponse[content=REDACTED]"; }
}
