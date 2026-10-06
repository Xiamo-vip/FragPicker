package com.fragpicker.knowledge;

import java.util.*;

public record EnrichmentResult(String summary, List<String> points, List<Category> categories) {
    public enum Category { LEARNING, TECHNOLOGY, LIFESTYLE, HEALTH, FINANCE, ART, ENTERTAINMENT, OTHER }
    public EnrichmentResult {
        if (summary == null || summary.isBlank() || summary.length() > 2000 || points == null || points.isEmpty()
                || points.size() > 8 || points.stream().anyMatch(text -> text == null || text.isBlank() || text.length() > 300)
                || categories == null || categories.isEmpty() || categories.size() > 3 || categories.stream().anyMatch(Objects::isNull)
                || new HashSet<>(categories).size() != categories.size()) throw new IllegalArgumentException("Invalid enrichment result");
        points = List.copyOf(points); categories = List.copyOf(categories);
    }
    @Override public String toString() { return "EnrichmentResult[content=REDACTED]"; }
}
