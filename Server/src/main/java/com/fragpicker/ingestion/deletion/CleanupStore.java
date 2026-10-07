package com.fragpicker.ingestion.deletion;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Profile("database")
public class CleanupStore {
    private final CleanupMapper jobs;
    private final CleanupProperties properties;
    public CleanupStore(CleanupMapper jobs,CleanupProperties properties) { properties.validate();this.jobs=jobs;this.properties=properties; }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<CleanupLease> claim() {
        var row=jobs.lockNext();if(row==null)return Optional.empty();var token=UUID.randomUUID().toString();
        if(jobs.claim(row.userId(),row.fragmentId(),token,properties.leaseDuration().toSeconds())!=1)throw new IllegalStateException("Missing cleanup job");
        return Optional.of(new CleanupLease(row.userId(),row.fragmentId(),row.buckets(),row.version()+1,token,row.attempts()+1));
    }
    @Transactional public boolean renew(CleanupLease lease) { return jobs.valid(lease)!=null && jobs.renew(lease,properties.leaseDuration().toSeconds())==1; }
    @Transactional public boolean finish(CleanupLease lease,boolean empty) {
        if(jobs.valid(lease)==null)return false;
        return jobs.finish(lease,empty?"CONFIRMED":"QUEUED",empty?properties.rescanDelay().toSeconds():properties.pollDelay().toSeconds(),null)==1;
    }
    @Transactional public boolean fail(CleanupLease lease,String error,boolean retryable) {
        if(error==null || !error.matches("[A-Z_]{1,64}"))throw new IllegalArgumentException("Invalid cleanup error code");
        if(jobs.valid(lease)==null)return false;
        return jobs.finish(lease,"FAILED",properties.retrySeconds(lease.attempt(),retryable),error)==1;
    }
}
