package com.fragpicker.ingestion.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.digest.*;
import com.fragpicker.ingestion.*;
import com.fragpicker.ingestion.retry.RetryMapper;
import com.fragpicker.integration.oss.OssProperties;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.LinkedHashSet;

@Service
@Profile("database")
public class DeletionService {
    private final UserAccountMapper users;
    private final RetryMapper jobs;
    private final DeletionMapper data;
    private final FragmentRecordMapper fragments;
    private final DigestMapper digests;
    private final DigestScheduleStore changes;
    private final OssProperties oss;
    private final ObjectMapper json;
    private final Clock clock;
    public DeletionService(UserAccountMapper users,RetryMapper jobs,DeletionMapper data,FragmentRecordMapper fragments,
        DigestMapper digests,DigestScheduleStore changes,OssProperties oss,ObjectMapper json,Clock clock) {
        this.users=users;this.jobs=jobs;this.data=data;this.fragments=fragments;this.digests=digests;this.changes=changes;this.oss=oss;this.json=json;this.clock=clock;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public DeletionResponse delete(long owner,long fragment) {
        var user=users.lockById(owner);
        if (user==null || !Boolean.TRUE.equals(user.getEnabled())) throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_INVALID","请重新登录");
        var saved=data.find(owner,fragment);
        if (saved!=null) return new DeletionResponse(fragment,"DELETED",!saved.status().equals("CONFIRMED"),true);
        var job=jobs.lockJob(owner,fragment);
        if (job==null) throw new ApiException(HttpStatus.NOT_FOUND,"FRAGMENT_NOT_FOUND","未找到投喂记录");
        var header=fragments.selectById(fragment);
        if (header==null || header.getUserId()!=owner) throw new IllegalStateException("Missing owned fragment");
        // User -> ingestion -> digest -> fragment -> change. A snapshot worker never waits on ingestion.
        var digest=digests.lockDay(owner,header.getBusinessDate());
        if (data.lockFragment(owner,fragment)==null) throw new IllegalStateException("Missing deletion fragment");
        var buckets=new LinkedHashSet<>(data.buckets(owner,fragment));
        if (job.getMediaAttemptCount()>0) {
            if (oss.bucket()==null || oss.bucket().isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CLEANUP_BUCKET_UNCONFIGURED","请先配置原私有存储 Bucket，再删除此内容");
            buckets.add(oss.bucket());
        }
        if (buckets.size()>4 || buckets.stream().anyMatch(bucket->bucket==null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CLEANUP_BUCKET_INVALID","私有媒体清理配置暂不可用");
        var now=clock.instant();var utc=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        var due=buckets.isEmpty()?LocalDateTime.of(9999,12,31,0,0):utc;
        if (job.getLeaseExpiresAt()!=null && job.getLeaseExpiresAt().isAfter(utc)) due=job.getLeaseExpiresAt().plusMinutes(2);
        try {
            if (data.insert(owner,fragment,header.getBusinessDate(),json.writeValueAsString(buckets),buckets.isEmpty(),due)!=1) throw new IllegalStateException("Missing deletion record");
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Cannot encode cleanup targets"); }
        if (data.removeFragment(owner,fragment)!=1) throw new IllegalStateException("Missing removed fragment");
        if (digest!=null && data.dayTotal(owner,header.getBusinessDate())==0) data.removeEmptyDigest(owner,digest.id());
        else if (digest!=null) {
            data.clearCheckpoints(owner,digest.id());data.clearSnapshots(owner,digest.id());
            if (data.invalidateDigest(owner,digest.id(),DigestScheduleStore.dueAt(header.getBusinessDate(),now))!=1) throw new IllegalStateException("Missing affected digest");
        }
        changes.changed(owner,header.getBusinessDate());
        return new DeletionResponse(fragment,"DELETED",!buckets.isEmpty(),false);
    }
}
