package com.fragpicker.history;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.*;
import java.util.List;

public record DailyFragmentsResponse(LocalDate date, List<Item> items, Long nextBefore) {
    public DailyFragmentsResponse { items = List.copyOf(items); }
    @Override public String toString() { return "DailyFragmentsResponse[content=REDACTED]"; }
    public record Item(long fragmentId, LocalDate businessDate, Instant createdAt, String sourceHost, String status, String errorCode,
            String title, boolean titleTruncated, String author, boolean authorTruncated, String summary, String summaryOrigin,
            boolean summaryTruncated, List<Category> categories, String contentPath, String videoMediaPath, String coverMediaPath,
            String displayTitle, String introduction) {
        public Item { categories = List.copyOf(categories); }
        @Override public String toString() { return "DailyFragmentItem[content=REDACTED]"; }
    }
}
