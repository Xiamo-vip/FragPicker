package com.fragpicker.digest;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

public class DigestScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(DigestScheduler.class);
    private final DigestScheduleStore store;
    public DigestScheduler(DigestScheduleStore store) { this.store=store; }
    @Scheduled(fixedDelayString="${fragpicker.digest.schedule.poll-delay:10s}")
    public void tick() {
        for (var change:store.due()) {
            try { store.dispatch(change); }
            catch (RuntimeException failed) { LOG.warn("Daily digest scheduling failed: DIGEST_SCHEDULE_UNAVAILABLE"); }
        }
    }
}
