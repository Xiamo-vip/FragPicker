package com.fragpicker.knowledge.search;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.LocalDate;
import java.util.List;

public record SearchResponse(List<Hit> items, int scannedChunks) {
    public SearchResponse { items = List.copyOf(items); }
    public record Hit(long fragmentId, String title, String author, LocalDate businessDate, String summary, List<Category> categories,
                      String videoMediaPath, String coverMediaPath, double score, double semanticScore, boolean literalMatch, Match match, String introduction) {
        public Hit(long fragmentId, String title, String author, LocalDate businessDate, String summary, List<Category> categories,
                   String videoMediaPath, String coverMediaPath, double score, double semanticScore, boolean literalMatch, Match match) {
            this(fragmentId, title, author, businessDate, summary, categories, videoMediaPath, coverMediaPath, score, semanticScore, literalMatch, match, null);
        }
        public Hit { categories = List.copyOf(categories); }
        @Override public String toString() { return "SearchHit[content=REDACTED]"; }
    }
    public record Match(int chunkOrdinal, String sourceKind, Integer sourceOrdinal, Long startMs, Long endMs, String text) {
        @Override public String toString() { return "SearchMatch[text=REDACTED]"; }
    }
}
