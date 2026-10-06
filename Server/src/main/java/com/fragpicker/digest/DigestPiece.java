package com.fragpicker.digest;

import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.LocalDate;
import java.util.*;

/** Counts and scope are computed by the server, not parsed from model JSON. */
public record DigestPiece(long userId, LocalDate date, long sourceCount, long modelCalls, String summary,
        List<DigestPoint> points, List<Category> categories, List<String> keywords) {
    public DigestPiece {
        DigestText.require(summary, 1000);
        if (userId < 1 || date == null || sourceCount < 0 || modelCalls < 0 || points == null || categories == null || keywords == null
                || points.size() > 8 || categories.size() > 8 || keywords.size() > 10 || new HashSet<>(categories).size() != categories.size()
                || categories.stream().anyMatch(Objects::isNull) || points.stream().anyMatch(Objects::isNull)
                || new HashSet<>(keywords).size() != keywords.size()) throw new IllegalArgumentException("Invalid digest piece");
        if (sourceCount == 0 ? modelCalls != 0 || !points.isEmpty() || !categories.isEmpty() || !keywords.isEmpty()
                : modelCalls < 1 || points.isEmpty() || categories.isEmpty() || keywords.isEmpty()) throw new IllegalArgumentException("Invalid digest coverage");
        keywords.forEach(text -> DigestText.require(text, 32));
        points = List.copyOf(points); categories = List.copyOf(categories); keywords = List.copyOf(keywords);
    }
    @Override public String toString() { return "DigestPiece[content=REDACTED]"; }
}
