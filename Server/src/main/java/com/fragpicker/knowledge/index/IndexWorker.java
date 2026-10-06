package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

public class IndexWorker {
    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);
    private final IndexStore store;
    private final LocalEmbeddingService embeddings;
    public IndexWorker(IndexStore store, LocalEmbeddingService embeddings) { this.store = store; this.embeddings = embeddings; }
    @Scheduled(fixedDelayString = "${fragpicker.knowledge.index.poll-delay}", initialDelayString = "${fragpicker.knowledge.index.poll-delay}")
    public void poll() { try { runOnce(); } catch (Exception failure) { log.warn("Index poll failed ({})", failure.getClass().getSimpleName()); } }
    public boolean runOnce() {
        var claimed = store.claim(); if (claimed.isEmpty()) return false; var lease = claimed.get();
        try {
            var text = store.chunks(lease); var indexed = new ArrayList<IndexedChunk>(text.size());
            for (int i = 0; i < text.size(); i += 16) {
                if (!store.renew(lease)) return true;
                var batch = text.subList(i, Math.min(i + 16, text.size()));
                var vectors = embeddings.embedDocuments(batch.stream().map(TextChunk::content).toList());
                if (vectors.size() != batch.size()) throw new IllegalStateException("Incomplete embedding response");
                for (int j = 0; j < batch.size(); j++) indexed.add(new IndexedChunk(batch.get(j), VectorCodec.encode(vectors.get(j).vector())));
            }
            if (store.renew(lease)) store.complete(lease, indexed);
        } catch (IndexFailure failure) { store.fail(lease, failure.code(), failure.retryable()); }
        catch (Exception failure) {
            log.warn("Index job {} failed internally ({})", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, "INDEX_INTERNAL_ERROR", true);
        }
        return true;
    }
}
