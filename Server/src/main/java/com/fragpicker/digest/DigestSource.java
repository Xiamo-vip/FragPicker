package com.fragpicker.digest;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.LocalDate;
import java.util.List;

/** Internal input; owner and date must come from the database, never from model output. */
public record DigestSource(long fragmentId, long userId, LocalDate date, String title, String author,
        String summary, List<String> points, List<Category> categories, List<String> keywords) {
    public DigestSource { points = List.copyOf(points); categories = List.copyOf(categories); keywords = List.copyOf(keywords); }
    @Override public String toString() { return "DigestSource[content=REDACTED]"; }
}
