package com.fragpicker.digest;

import com.fragpicker.history.DailyFragmentsResponse;
import com.fragpicker.knowledge.EnrichmentResult.Category;
import java.time.*;
import java.util.List;

public record DailyDigestResponse(LocalDate date,String status,long total,long ready,long processing,long failed,
        long requestedRevision,long completedRevision,Instant generatedAt,Instant nextRunAt,boolean outdated,
        boolean canRegenerate,String errorCode,Result result) {
    public record Result(String summary,long sourceCount,List<DigestPoint> points,List<Category> categories,
                         List<String> keywords,List<DailyFragmentsResponse.Item> sources) {
        public Result { points=List.copyOf(points); categories=List.copyOf(categories); keywords=List.copyOf(keywords); sources=List.copyOf(sources); }
        @Override public String toString() { return "DigestResult[content=REDACTED]"; }
    }
    @Override public String toString() { return "DailyDigestResponse[content=REDACTED]"; }
}
