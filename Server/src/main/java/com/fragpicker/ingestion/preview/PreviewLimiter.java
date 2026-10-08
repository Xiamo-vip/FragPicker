package com.fragpicker.ingestion.preview;

import com.fragpicker.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@org.springframework.context.annotation.Profile("database")
@Component
public class PreviewLimiter {
    private final Clock clock;
    private final Map<Long,Instant> attempts=new LinkedHashMap<>();
    private final Semaphore slots=new Semaphore(2);
    public PreviewLimiter(Clock clock){this.clock=clock;}
    public AutoCloseable acquire(long owner) {
        if(!slots.tryAcquire())throw limited();
        try { reserve(owner); } catch(RuntimeException rejected){slots.release();throw rejected;}
        return slots::release;
    }
    private synchronized void reserve(long owner) {
        Instant now=clock.instant();
        attempts.entrySet().removeIf(entry->entry.getValue().plusSeconds(600).isBefore(now));
        Instant previous=attempts.get(owner);
        if(previous!=null && previous.plusSeconds(20).isAfter(now) || previous==null && attempts.size()>=1024)throw limited();
        attempts.put(owner,now);
    }
    private ApiException limited(){return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"PREVIEW_RATE_LIMIT","分享预览请求较多，请稍后重试");}
}
