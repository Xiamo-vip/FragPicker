package com.fragpicker.ingestion;

import com.fragpicker.integration.parsevideo.ParseVideoClient;
import com.fragpicker.integration.parsevideo.ParseVideoFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class ParseVideoWorker {
    private static final Logger log = LoggerFactory.getLogger(ParseVideoWorker.class);
    private final ParseJobStore store;
    private final ParseVideoClient parser;

    public ParseVideoWorker(ParseJobStore store, ParseVideoClient parser) { this.store = store; this.parser = parser; }

    @Scheduled(fixedDelayString = "${fragpicker.ingestion.worker.poll-delay}",
            initialDelayString = "${fragpicker.ingestion.worker.poll-delay}")
    public void poll() {
        try { runOnce(); }
        catch (Exception failure) {
            // No raw exception: provider bodies, signed URLs and database values may contain secrets.
            log.warn("Parse worker poll failed; leases remain recoverable ({})", failure.getClass().getSimpleName());
        }
    }

    public boolean runOnce() {
        var claimed = store.claim();
        if (claimed.isEmpty()) return false;
        var lease = claimed.get();
        try { store.complete(lease, parser.parse(lease.sourceUrl())); }
        catch (ParseVideoFailure failure) { store.fail(lease, "PARSE_" + failure.code().name(), failure.retryable()); }
        catch (Exception failure) {
            log.warn("Parse job {} failed internally ({})", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, "PARSE_INTERNAL_ERROR", true);
        }
        return true;
    }
}
