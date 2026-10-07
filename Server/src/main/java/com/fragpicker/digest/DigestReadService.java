package com.fragpicker.digest;

import com.fasterxml.jackson.databind.*;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.history.DailyFragmentsService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service
@Profile("database")
public class DigestReadService {
    private final DigestMapper jobs;
    private final DigestReadMapper data;
    private final DailyFragmentsService fragments;
    private final DigestJobProperties worker;
    private final ObjectMapper json;
    public DigestReadService(DigestMapper jobs,DigestReadMapper data,DailyFragmentsService fragments,DigestJobProperties worker,ObjectMapper json) {
        this.jobs=jobs; this.data=data; this.fragments=fragments; this.worker=worker;
        this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DailyDigestResponse get(long owner,String rawDate) {
        var date=date(rawDate); var counts=data.counts(owner,date); var row=jobs.day(owner,date); var change=data.change(owner,date);
        boolean dirty=change!=null && change.version()>change.scheduledVersion();
        var due=row!=null && "QUEUED".equals(row.status()) ? data.nextRun(row) : dirty && (row==null || !"FAILED".equals(row.status())) ? change.dueAt() : null;
        String status=row==null ? counts.total()==0 ? "EMPTY" : "WAITING" : row.status();
        String error=row==null ? null : row.errorCode(); DailyDigestResponse.Result result=null;
        if (row!=null && row.completedRevision()>0) {
            if (data.completedSources(row)!=row.sourceCount()) { status="STALE"; error="DIGEST_SOURCE_CHANGED"; }
            else {
                var piece=read(row.resultJson());
                if (piece.userId()!=owner || !piece.date().equals(date) || piece.sourceCount()!=row.sourceCount()) throw unavailable();
                var ids=piece.points().stream().flatMap(point -> point.sourceIds().stream()).distinct().toList();
                if (!ids.isEmpty() && data.references(row,ids)!=ids.size()) throw unavailable();
                var cards=ids.stream().map(id -> fragments.card(owner,id)).toList();
                result=new DailyDigestResponse.Result(piece.summary(),piece.sourceCount(),piece.points(),piece.categories(),piece.keywords(),cards);
            }
        }
        return new DailyDigestResponse(date,status,counts.total(),counts.ready(),counts.processing(),counts.failed(),
            row==null ? 0 : row.requestedRevision(),row==null ? 0 : row.completedRevision(),row==null ? null : instant(row.generatedAt()),
            instant(due),dirty || row!=null && row.requestedRevision()>row.completedRevision() || "STALE".equals(status),
            worker.enabled() && counts.total()>0,error,result);
    }
    public static LocalDate date(String raw) {
        try {
            if (raw==null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("Invalid");
            var date=LocalDate.parse(raw); if (date.getYear()<1000) throw new DateTimeException("Invalid"); return date;
        } catch (DateTimeException bad) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DIGEST_DATE","请提供有效的 YYYY-MM-DD 日期"); }
    }
    private DigestPiece read(String value) {
        try { if (value==null || value.length()>32768) throw unavailable(); return json.readValue(value,DigestPiece.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw unavailable(); }
    }
    private static Instant instant(LocalDateTime utc) { return utc==null ? null : utc.toInstant(ZoneOffset.UTC); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DIGEST_RESULT_UNAVAILABLE","总结暂时无法读取，请稍后刷新"); }
}
