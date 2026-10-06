package com.fragpicker.knowledge;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

public class EnrichmentWorker {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentWorker.class);
    private final EnrichmentStore store;
    private final KnowledgeEnricher enricher;
    private final String model;
    public EnrichmentWorker(EnrichmentStore store, KnowledgeEnricher enricher, String model) { this.store = store; this.enricher = enricher; this.model = model; }
    @Scheduled(fixedDelayString = "${fragpicker.knowledge.enrichment.poll-delay}", initialDelayString = "${fragpicker.knowledge.enrichment.poll-delay}")
    public void poll() {
        try { runOnce(); } catch (Exception failure) { log.warn("Knowledge poll failed ({})", failure.getClass().getSimpleName()); }
    }
    public boolean runOnce() {
        var claimed = store.claim(); if (claimed.isEmpty()) return false; var lease = claimed.get();
        try { store.complete(lease, enricher.enrich(store.source(lease), store.samples(lease)), model); }
        catch (EnrichmentFailure failure) { store.fail(lease, failure.code(), failure.retryable()); }
        catch (Exception failure) {
            log.warn("Knowledge job {} failed internally ({})", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, "KNOWLEDGE_INTERNAL_ERROR", true);
        }
        return true;
    }
}
