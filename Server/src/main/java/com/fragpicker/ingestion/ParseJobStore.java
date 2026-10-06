package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.integration.parsevideo.ParsedVideo;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("database")
public class ParseJobStore {
    private final IngestionJobMapper jobs;
    private final FragmentRecordMapper fragments;
    private final VideoMetadataMapper metadata;
    private final ParseWorkerProperties properties;

    public ParseJobStore(IngestionJobMapper jobs, FragmentRecordMapper fragments,
                         VideoMetadataMapper metadata, ParseWorkerProperties properties) {
        properties.validate();
        this.jobs = jobs; this.fragments = fragments; this.metadata = metadata; this.properties = properties;
    }

    // Short read-committed transactions avoid queue gap locks; no network I/O holds a database lock.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ParseLease> claim() {
        var job = jobs.lockNextParseJob();
        if (job == null) return Optional.empty();
        if (job.getAttemptCount() >= properties.maxAttempts()) {
            jobs.exhaustParse(job.getId());
            updateFragment(job.getFragmentId(), job.getUserId(), "FAILED");
            return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        if (jobs.claimParse(job.getId(), owner, properties.leaseDuration().toSeconds()) != 1) {
            throw new IllegalStateException("Could not claim locked parse job");
        }
        updateFragment(job.getFragmentId(), job.getUserId(), "PARSING");
        var fragment = fragments.selectById(job.getFragmentId());
        return Optional.of(new ParseLease(job.getId(), job.getFragmentId(), job.getUserId(),
                job.getVersion() + 1, owner, job.getAttemptCount() + 1, fragment.getSourceUrl()));
    }

    @Transactional
    public boolean complete(ParseLease lease, ParsedVideo video) {
        // Check and lock the current lease before writing metadata; both writes commit together.
        if (jobs.finishParse(lease, "MEDIA_PENDING", null, 0) != 1) return false;
        if (metadata.insert(VideoMetadata.from(lease, video)) != 1) throw new IllegalStateException("Missing video metadata");
        updateFragment(lease.fragmentId(), lease.userId(), "MEDIA_PENDING");
        return true;
    }

    @Transactional
    public boolean fail(ParseLease lease, String errorCode, boolean retryable) {
        if (errorCode == null || !errorCode.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid stable error code");
        String stage = retryable && lease.attemptCount() < properties.maxAttempts() ? "QUEUED" : "FAILED";
        if (jobs.finishParse(lease, stage, errorCode, properties.retryDelaySeconds(lease.attemptCount())) != 1) return false;
        updateFragment(lease.fragmentId(), lease.userId(), stage);
        return true;
    }

    private void updateFragment(long id, long userId, String stage) {
        int changed = fragments.update(null, Wrappers.<FragmentRecord>lambdaUpdate()
                .eq(FragmentRecord::getId, id).eq(FragmentRecord::getUserId, userId).set(FragmentRecord::getStatus, stage));
        if (changed != 1) throw new IllegalStateException("Missing fragment for durable job");
    }
}
