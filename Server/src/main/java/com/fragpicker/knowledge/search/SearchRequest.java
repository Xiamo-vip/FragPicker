package com.fragpicker.knowledge.search;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.LocalDate;

public record SearchRequest(String query, LocalDate fromDate, LocalDate toDate, Category category, String author, String keyword, Integer limit) {
    @Override public String toString() { return "SearchRequest[query=REDACTED, filters=REDACTED]"; }
}
