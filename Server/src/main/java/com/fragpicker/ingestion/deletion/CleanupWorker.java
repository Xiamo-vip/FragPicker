package com.fragpicker.ingestion.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.oss.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

public class CleanupWorker {
    private static final Logger log=LoggerFactory.getLogger(CleanupWorker.class);
    private final CleanupStore store;
    private final OssMediaStorage storage;
    private final ObjectMapper json;
    public CleanupWorker(CleanupStore store,OssMediaStorage storage,ObjectMapper json) { this.store=store;this.storage=storage;this.json=json; }
    @Scheduled(fixedDelayString="${fragpicker.ingestion.cleanup-worker.poll-delay}",initialDelayString="${fragpicker.ingestion.cleanup-worker.poll-delay}")
    public void poll() { try {runOnce();}catch(Exception error){log.warn("Media cleanup poll failed ({})",error.getClass().getSimpleName());} }
    public boolean runOnce() {
        var claimed=store.claim();if(claimed.isEmpty())return false;var lease=claimed.get();
        try {
            var buckets=json.readTree(lease.buckets());
            if(!buckets.isArray() || buckets.size()>4)throw new IllegalArgumentException("Invalid cleanup buckets");
            boolean empty=true;
            for(var bucket:buckets) {
                if(!bucket.isTextual())throw new IllegalArgumentException("Invalid cleanup target");
                if(!store.renew(lease))return true;
                if(!storage.deleteOwnedPage(bucket.asText(),lease.userId(),lease.fragmentId(),()->store.renew(lease)))empty=false;
            }
            store.finish(lease,empty);
        } catch(OssStorageFailure error) {store.fail(lease,"OSS_"+error.code().name(),error.retryable());}
        catch(Exception error) {log.warn("Media cleanup {} failed ({})",lease.fragmentId(),error.getClass().getSimpleName());store.fail(lease,"CLEANUP_INTERNAL_ERROR",true);}
        return true;
    }
}
