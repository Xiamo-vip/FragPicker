package com.fragpicker.digest;

import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
@Profile("database")
public class DigestManualService {
    private final DigestMapper jobs;
    private final DigestManualMapper requests;
    private final DigestReadMapper data;
    private final DigestChangeMapper changes;
    private final DigestJobProperties worker;
    private final DigestManualProperties properties;
    private final Clock clock;
    public DigestManualService(DigestMapper jobs,DigestManualMapper requests,DigestReadMapper data,DigestChangeMapper changes,
            DigestJobProperties worker,DigestManualProperties properties,Clock clock) {
        properties.validate(); this.jobs=jobs; this.requests=requests; this.data=data; this.changes=changes;
        this.worker=worker; this.properties=properties; this.clock=clock;
    }
    @Transactional
    public DigestRegenerateResponse regenerate(long owner,String rawDate,String rawKey) {
        var date=DigestReadService.date(rawDate); var now=clock.instant();
        if (date.isAfter(now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DIGEST_DATE","不能生成未来日期的总结");
        if (rawKey==null || !rawKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","请使用 UUID 格式的 Idempotency-Key");
        var key=UUID.fromString(rawKey).toString();
        if (jobs.lockUser(owner)==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_INVALID","请重新登录");
        var saved=requests.find(owner,key);
        if (saved!=null) {
            if (!saved.date().equals(date)) throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","该请求标识已用于另一个日期");
            return new DigestRegenerateResponse(date,saved.revision(),saved.status(),true);
        }
        if (!worker.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DIGEST_DISABLED","每日总结服务尚未启用");
        if (data.counts(owner,date).total()==0) throw new ApiException(HttpStatus.NOT_FOUND,"DIGEST_DAY_EMPTY","这一天没有投喂记录");
        var row=jobs.lockDay(owner,date); var utc=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        if (row!=null && row.lastManualAt()!=null && utc.isBefore(row.lastManualAt().plus(properties.cooldown())))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"DIGEST_REBUILD_COOLDOWN","请稍后再重新整理这一天");
        if (row!=null && "RUNNING".equals(row.status()) && requests.expired(row.id())==1) {
            jobs.fail(row.id(),"DIGEST_AI_UNCONFIRMED"); row=jobs.lockDay(owner,date);
        }
        if (row==null) jobs.insert(owner,date,utc);
        else if ("READY".equals(row.status()) || "FAILED".equals(row.status()) || "RUNNING".equals(row.status()) && row.requestedRevision()==row.workingRevision())
            jobs.queueRevision(row.id(),utc);
        row=jobs.lockDay(owner,date); requests.expedite(row.id(),utc);
        requests.remember(owner,key,row.id(),row.requestedRevision());
        // Same user->digest->change order as automatic scheduling. New READY events create fresh versions afterwards.
        changes.acknowledge(owner,date);
        return new DigestRegenerateResponse(date,row.requestedRevision(),row.status(),false);
    }
}
