package com.fragpicker.history;

import com.fragpicker.common.api.ApiException;
import com.fragpicker.knowledge.content.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.List;

@Service
@Profile("database")
public class DailyFragmentsService {
    private final DailyFragmentsMapper data;
    private final ContentService content;
    public DailyFragmentsService(DailyFragmentsMapper data, ContentService content) { this.data = data; this.content = content; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DailyFragmentsResponse list(long owner, String rawDate, String rawBefore, String rawLimit) {
        LocalDate date; Long before = null; int limit = 10;
        try {
            if (rawDate == null || !rawDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
            date = LocalDate.parse(rawDate); if (date.getYear() < 1000) throw invalid();
            if (rawBefore != null) { if (!rawBefore.matches("[0-9]{1,19}")) throw invalid(); before = Long.parseLong(rawBefore); if (before < 1) throw invalid(); }
            if (rawLimit != null) { if (!rawLimit.matches("[0-9]{1,2}")) throw invalid(); limit = Integer.parseInt(rawLimit); if (limit < 1 || limit > 20) throw invalid(); }
        } catch (DateTimeException | NumberFormatException bad) { throw invalid(); }
        var ids = data.page(owner, date, before, limit + 1); var visible = ids.subList(0, Math.min(ids.size(), limit));
        var items = visible.stream().map(id -> item(content.get(owner, id))).toList();
        return new DailyFragmentsResponse(date, items, ids.size() > limit ? visible.getLast() : null);
    }
    public DailyFragmentsResponse.Item card(long owner,long id) { return item(content.get(owner,id)); }
    private DailyFragmentsResponse.Item item(ContentResponse saved) {
        var knowledge = saved.knowledge(); String summary = null, origin = "NONE"; boolean truncated = false;
        if (knowledge != null) {
            boolean enriched = knowledge.summary() != null; origin = enriched ? "ENRICHED" : "TRANSCRIPTION";
            summary = enriched ? knowledge.summary() : knowledge.originalSummaryPreview(); truncated = enriched ? knowledge.summaryTruncated() : knowledge.originalSummaryTruncated();
            if (summary != null && summary.codePointCount(0, summary.length()) > 400) { summary = summary.substring(0, summary.offsetByCodePoints(0, 400)); truncated = true; }
        }
        return new DailyFragmentsResponse.Item(saved.id(), saved.businessDate(), saved.createdAt(), saved.sourceHost(), saved.status(), saved.errorCode(),
                saved.title(), saved.titleTruncated(), saved.author(), saved.authorTruncated(), summary, origin, truncated,
                knowledge == null ? List.of() : knowledge.categories(), "/api/v1/fragments/" + saved.id() + "/content", saved.videoMediaPath(), saved.coverMediaPath(),
                saved.displayTitle(), saved.introduction());
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HISTORY_PAGE", "请提供有效日期、正整数游标和1至20的页大小"); }
}
