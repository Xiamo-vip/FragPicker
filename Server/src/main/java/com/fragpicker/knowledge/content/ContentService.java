package com.fragpicker.knowledge.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
@Profile("database")
public class ContentService {
    private final ContentMapper data;
    private final ObjectMapper json;
    public ContentService(ContentMapper data, ObjectMapper json) { this.data = data; this.json = json; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentResponse get(long user, long id) {
        var header = data.header(user, id);
        if (header == null) throw new ApiException(HttpStatus.NOT_FOUND, "FRAGMENT_NOT_FOUND", "未找到投喂记录");
        var raw = data.knowledge(user, id); ContentResponse.Knowledge knowledge = null;
        if (raw != null) {
            var points = texts(raw.points(), 8, 300); var keywords = texts(raw.keywords(), 100, 100);
            var categories = texts(raw.categories(), 3, 32); List<Category> labels;
            try {
                if (categories.truncated()) throw new IllegalArgumentException();
                labels = categories.items().stream().map(Category::valueOf).toList();
            } catch (IllegalArgumentException corrupt) { throw invalid(); }
            knowledge = new ContentResponse.Knowledge(raw.durationMs(), raw.originalSummaryPreview(), raw.originalSummaryTruncated(), raw.summary(), raw.summaryTruncated(),
                    points.items(), points.truncated(), keywords.items(), keywords.truncated(), labels, utc(raw.completedAt()), utc(raw.enrichedAt()));
        }
        String media = "/api/v1/fragments/" + id + "/media?kind=";
        return new ContentResponse(header.id(), header.sourceUrl(), header.sourceHost(), header.note(), header.businessDate(), header.businessZone(), utc(header.createdAt()), header.status(), header.errorCode(),
                header.title() == null || header.title().isBlank() ? "投喂记录" : header.title(), header.titleTruncated(), header.author(), header.authorTruncated(),
                header.video() ? media + "VIDEO" : null, header.cover() ? media + "COVER" : null, header.sentenceCount(), header.keyPointCount(), knowledge);
    }
    private Texts texts(String encoded, int limit, int maxLength) {
        if (encoded == null) return new Texts(List.of(), false);
        try {
            var array = json.readTree(encoded); if (!array.isArray()) throw new IllegalArgumentException();
            var result = new ArrayList<String>(); boolean truncated = array.size() > limit;
            for (int i = 0; i < Math.min(array.size(), limit); i++) {
                var item = array.get(i); if (!item.isTextual()) throw new IllegalArgumentException(); String value = item.asText();
                if (value.codePointCount(0, value.length()) > maxLength) { value = value.substring(0, value.offsetByCodePoints(0, maxLength)); truncated = true; }
                result.add(value);
            }
            return new Texts(List.copyOf(result), truncated);
        } catch (Exception corrupt) { throw invalid(); }
    }
    private Instant utc(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private ApiException invalid() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONTENT_INVALID", "资料内容暂时无法读取"); }
    private record Texts(List<String> items, boolean truncated) { }
}
