package com.fragpicker.knowledge;

import java.util.*;

public record EnrichmentResult(String summary, List<String> points, List<Category> categories, String displayTitle, String introduction) {
    // Existing stored knowledge can still be validated without regenerating it.
    public EnrichmentResult(String summary, List<String> points, List<Category> categories) {
        this(summary, points, categories, null, null);
    }
    public enum Category { LEARNING, TECHNOLOGY, LIFESTYLE, HEALTH, FINANCE, ART, ENTERTAINMENT, OTHER }
    public EnrichmentResult {
        if (summary == null || summary.isBlank() || summary.length() > 2000 || points == null || points.isEmpty()
                || points.size() > 8 || points.stream().anyMatch(text -> text == null || text.isBlank() || text.length() > 300)
                || categories == null || categories.isEmpty() || categories.size() > 3 || categories.stream().anyMatch(Objects::isNull)
                || new HashSet<>(categories).size() != categories.size()) throw new IllegalArgumentException("Invalid enrichment result");
        if ((displayTitle == null) != (introduction == null) || displayTitle != null &&
                (displayTitle.isBlank() || displayTitle.codePointCount(0, displayTitle.length()) > 32 || displayTitle.contains("\n") || displayTitle.contains("\r") ||
                 introduction.isBlank() || introduction.codePointCount(0, introduction.length()) > 100 || introduction.contains("\n") || introduction.contains("\r")))
            throw new IllegalArgumentException("Invalid display copy");
        points = List.copyOf(points); categories = List.copyOf(categories);
    }
    @Override public String toString() { return "EnrichmentResult[content=REDACTED]"; }
}
