package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Profile("database")
public class SubmissionService {
    private final UserAccountMapper users;
    private final FragmentRecordMapper fragments;
    private final IngestionJobMapper jobs;
    private final SubmissionRecordMapper requests;
    private final ShareLinkResolver links;
    private final Clock clock;
    private final com.fragpicker.digest.DigestScheduleStore digestChanges;

    public SubmissionService(UserAccountMapper users, FragmentRecordMapper fragments, IngestionJobMapper jobs,
                             SubmissionRecordMapper requests, ShareLinkResolver links, Clock clock, com.fragpicker.digest.DigestScheduleStore digestChanges) {
        this.users = users; this.fragments = fragments; this.jobs = jobs;
        this.requests = requests; this.links = links; this.clock = clock;
        this.digestChanges = digestChanges;
    }

    @Transactional
    public SubmissionResponse submit(long userId, String rawKey, SubmissionRequest input) {
        String key = idempotencyKey(rawKey);
        var source = links.resolve(input.shareText());
        String note = input.note() == null ? "" : input.note().strip();
        String url = source.toASCIIString();
        String requestHash = sha256(url.length() + ":" + url + note.length() + ":" + note);
        // Same lock order as session operations. Per-user serialization avoids duplicate paid jobs.
        var user = users.lockById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "请重新登录");
        var prior = requests.selectOne(Wrappers.<SubmissionRecord>lambdaQuery()
                .eq(SubmissionRecord::getUserId, userId).eq(SubmissionRecord::getIdempotencyKey, key));
        if (prior != null) {
            if (!requestHash.equals(prior.getRequestHash())) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "该幂等键已用于不同的投喂内容");
            var saved = fragments.selectById(prior.getFragmentId());
            return response(saved, Boolean.TRUE.equals(prior.getDuplicate()));
        }

        String sourceHash = sha256(url);
        var fragment = fragments.selectOne(Wrappers.<FragmentRecord>lambdaQuery()
                .eq(FragmentRecord::getUserId, userId).eq(FragmentRecord::getSourceHash, sourceHash));
        boolean duplicate = fragment != null;
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (!duplicate) {
            fragment = new FragmentRecord();
            fragment.setUserId(userId); fragment.setSourceUrl(url); fragment.setSourceHash(sourceHash);
            fragment.setSourceHost(source.getHost()); fragment.setNote(note.isEmpty() ? null : note);
            fragment.setBusinessDate(now.atZone(ZoneId.of(user.getBusinessZone())).toLocalDate());
            fragment.setBusinessZone(user.getBusinessZone()); fragment.setStatus("QUEUED"); fragment.setCreatedAt(utcNow);
            fragments.insert(fragment);
            var job = new IngestionJob();
            job.setFragmentId(fragment.getId()); job.setUserId(userId); job.setStage("QUEUED");
            job.setAttemptCount(0); job.setVersion(0L); job.setNextAttemptAt(utcNow); job.setCreatedAt(utcNow);
            jobs.insert(job);
            digestChanges.changed(userId, fragment.getBusinessDate());
        }
        var request = new SubmissionRecord();
        request.setUserId(userId); request.setIdempotencyKey(key); request.setRequestHash(requestHash);
        request.setFragmentId(fragment.getId()); request.setDuplicate(duplicate); request.setCreatedAt(utcNow);
        requests.insert(request);
        return response(fragment, duplicate);
    }

    private SubmissionResponse response(FragmentRecord fragment, boolean duplicate) {
        var job = jobs.selectOne(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getFragmentId, fragment.getId()).eq(IngestionJob::getUserId, fragment.getUserId()));
        if (job == null) throw new IllegalStateException("Fragment has no durable ingestion job");
        return new SubmissionResponse(fragment.getId(), job.getId(), job.getStage(), fragment.getBusinessDate(), duplicate);
    }

    private String idempotencyKey(String raw) {
        if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "请使用 UUID 格式的 Idempotency-Key");
        }
        return UUID.fromString(raw).toString();
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}
