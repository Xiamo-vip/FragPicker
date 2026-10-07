package com.fragpicker.knowledge.index;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.ingestion.*;
import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Profile("database")
public class IndexStore {
    private final IndexMapper jobs;
    private final FragmentRecordMapper fragments;
    private final IndexProperties properties;
    private final com.fragpicker.digest.DigestChangeMapper digestOwners;
    private final com.fragpicker.digest.DigestScheduleStore digestChanges;
    public IndexStore(IndexMapper jobs, FragmentRecordMapper fragments, IndexProperties properties,
                      com.fragpicker.digest.DigestChangeMapper digestOwners, com.fragpicker.digest.DigestScheduleStore digestChanges) {
        properties.validate(); this.jobs = jobs; this.fragments = fragments; this.properties = properties;
        this.digestOwners=digestOwners; this.digestChanges=digestChanges;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<IndexLease> claim() {
        var job = jobs.lockNext(); if (job == null) return Optional.empty();
        if (job.getIndexAttemptCount() >= properties.maxAttempts()) {
            finish(job.getId(), job.getFragmentId(), job.getUserId(), "FAILED", "INDEX_LEASE_EXPIRED", 0); return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), owner, properties.leaseDuration().toSeconds()) != 1) throw new IllegalStateException("Missing index job");
        updateFragment(job.getFragmentId(), job.getUserId(), "INDEXING");
        return Optional.of(new IndexLease(job.getId(), job.getFragmentId(), job.getUserId(), job.getVersion() + 1, owner, job.getIndexAttemptCount() + 1));
    }
    @Transactional
    public boolean renew(IndexLease lease) {
        return jobs.lockValid(lease) != null && jobs.renew(lease.jobId(), properties.leaseDuration().toSeconds()) == 1;
    }
    public List<TextChunk> chunks(IndexLease lease) {
        if (jobs.hasKnowledge(lease) != 1) throw new IndexFailure("INDEX_SOURCE_MISSING", false);
        var result = new ArrayList<TextChunk>(); var chunker = new UnicodeChunker();
        for (var source : jobs.metadata(lease)) chunker.append(source, result, properties.maxChunks());
        for (boolean sentences : List.of(true, false)) {
            int after = -1;
            while (true) {
                var page = sentences ? jobs.sentences(lease, after) : jobs.points(lease, after);
                if (page.isEmpty()) break;
                for (var source : page) chunker.append(source, result, properties.maxChunks());
                after = page.getLast().sourceOrdinal();
            }
        }
        if (result.isEmpty()) throw new IndexFailure("INDEX_SOURCE_EMPTY", false);
        return List.copyOf(result);
    }
    @Transactional
    public boolean complete(IndexLease lease, List<IndexedChunk> chunks) {
        // Same user->job->fragment->change order as submission and manual ingestion operations.
        if (digestOwners.lockOwner(lease.userId()) == null) return false;
        if (jobs.lockValid(lease) == null) return false;
        if (chunks == null || chunks.isEmpty() || chunks.size() > properties.maxChunks() || chunks.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid index chunk collection");
        jobs.deleteIndex(lease);
        if (jobs.insertIndex(lease, LocalEmbeddingService.MODEL_ID, UnicodeChunker.VERSION, chunks.size()) != 1) throw new IllegalStateException("Cannot save index manifest");
        for (int i = 0; i < chunks.size(); i += 64) {
            var batch = chunks.subList(i, Math.min(i + 64, chunks.size()));
            if (jobs.insertChunks(lease, i, batch) != batch.size()) throw new IllegalStateException("Incomplete index batch");
        }
        finish(lease.jobId(), lease.fragmentId(), lease.userId(), "READY", null, 0);
        digestChanges.changedForFragment(lease.userId(),lease.fragmentId()); return true;
    }
    @Transactional
    public boolean fail(IndexLease lease, String error, boolean retryable) {
        if (jobs.lockValid(lease) == null) return false;
        if (error == null || !error.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid index error code");
        finish(lease.jobId(), lease.fragmentId(), lease.userId(), retryable && lease.attempt() < properties.maxAttempts() ? "INDEX_PENDING" : "FAILED", error, properties.retryDelay(lease.attempt())); return true;
    }
    private void finish(long id, long fragment, long user, String stage, String error, long delay) {
        if (jobs.finish(id, stage, error, delay) != 1) throw new IllegalStateException("Missing index state"); updateFragment(fragment, user, stage);
    }
    private void updateFragment(long id, long user, String stage) {
        if (fragments.update(null, Wrappers.<FragmentRecord>lambdaUpdate().eq(FragmentRecord::getId, id).eq(FragmentRecord::getUserId, user).set(FragmentRecord::getStatus, stage)) != 1) throw new IllegalStateException("Missing index fragment");
    }
}
