package com.fragpicker.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.ingestion.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
@Profile("database")
public class EnrichmentStore {
    private final EnrichmentMapper jobs;
    private final FragmentRecordMapper fragments;
    private final EnrichmentProperties properties;
    private final ObjectMapper json;
    public EnrichmentStore(EnrichmentMapper jobs, FragmentRecordMapper fragments, EnrichmentProperties properties, ObjectMapper json) {
        properties.validate(); this.jobs = jobs; this.fragments = fragments; this.properties = properties; this.json = json;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<EnrichmentLease> claim() {
        var job = jobs.lockNext(); if (job == null) return Optional.empty();
        if (job.getKnowledgeAttemptCount() >= properties.maxAttempts()) {
            finish(job.getId(), job.getFragmentId(), job.getUserId(), "FAILED", "KNOWLEDGE_LEASE_EXPIRED", 0); return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), owner, properties.leaseDuration().toSeconds()) != 1) throw new IllegalStateException("Missing enrichment job");
        updateFragment(job.getFragmentId(), job.getUserId(), "KNOWLEDGE_ENRICHING");
        return Optional.of(new EnrichmentLease(job.getId(), job.getFragmentId(), job.getUserId(), job.getVersion() + 1, owner, job.getKnowledgeAttemptCount() + 1));
    }
    public KnowledgeSource source(EnrichmentLease lease) {
        var source = jobs.source(lease); if (source == null) throw new IllegalStateException("Missing normalized knowledge"); return source;
    }
    public List<String> samples(EnrichmentLease lease) { return jobs.samples(lease); }
    @Transactional
    public boolean complete(EnrichmentLease lease, EnrichmentResult result, String model) {
        if (jobs.lockValid(lease) == null) return false;
        if (model == null || model.isBlank() || model.length() > 128) throw new IllegalArgumentException("Invalid enrichment model ID");
        try {
            if (jobs.save(lease, result, json.writeValueAsString(result.points()), json.writeValueAsString(result.categories()), model) != 1) throw new IllegalStateException("Missing knowledge to enrich");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Cannot encode enrichment"); }
        finish(lease.jobId(), lease.fragmentId(), lease.userId(), "INDEX_PENDING", null, 0); return true;
    }
    @Transactional
    public boolean fail(EnrichmentLease lease, String error, boolean retryable) {
        if (jobs.lockValid(lease) == null) return false;
        if (error == null || !error.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid enrichment error code");
        finish(lease.jobId(), lease.fragmentId(), lease.userId(), retryable && lease.attempt() < properties.maxAttempts() ? "KNOWLEDGE_PENDING" : "FAILED", error, properties.retryDelay(lease.attempt())); return true;
    }
    private void finish(long id, long fragment, long user, String stage, String error, long delay) {
        if (jobs.finish(id, stage, error, delay) != 1) throw new IllegalStateException("Missing enrichment state"); updateFragment(fragment, user, stage);
    }
    private void updateFragment(long id, long user, String stage) {
        if (fragments.update(null, Wrappers.<FragmentRecord>lambdaUpdate().eq(FragmentRecord::getId, id).eq(FragmentRecord::getUserId, user).set(FragmentRecord::getStatus, stage)) != 1) throw new IllegalStateException("Missing enrichment fragment");
    }
}
