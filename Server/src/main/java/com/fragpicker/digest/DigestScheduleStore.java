package com.fragpicker.digest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.List;

@Service
@Profile("database")
public class DigestScheduleStore {
    private static final ZoneId BEIJING=ZoneId.of("Asia/Shanghai");
    private final DigestChangeMapper changes;
    private final DigestMapper jobs;
    private final Clock clock;
    public DigestScheduleStore(DigestChangeMapper changes,DigestMapper jobs,Clock clock) {
        this.changes=changes; this.jobs=jobs; this.clock=clock;
    }
    /** Caller owns the user row before locking ingestion/fragment rows; commit with the content change. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void changed(long owner,LocalDate date) {
        if (owner<1 || date==null) throw new IllegalArgumentException("Invalid changed day");
        var now=clock.instant(); changes.change(owner,date,dueAt(date,now),LocalDateTime.ofInstant(now,ZoneOffset.UTC));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void changedForFragment(long owner,long fragment) {
        var date=changes.fragmentDate(owner,fragment); if (date==null) throw new IllegalStateException("Missing changed fragment");
        changed(owner,date);
    }
    public List<DigestChange> due() { return changes.due(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)); }
    @Transactional
    public boolean dispatch(DigestChange candidate) {
        long owner=candidate.userId(); var date=candidate.businessDate();
        if (jobs.lockUser(owner)==null) return false;
        // Existing digest before the change row: no change->digest edge while a worker snapshots fragments.
        var job=jobs.lockDay(owner,date); var change=changes.lock(owner,date);
        if (change==null || change.version()<=change.scheduledVersion() || change.dueAt().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))) return false;
        if (job==null) jobs.insert(owner,date,change.dueAt());
        else if ("READY".equals(job.status()) || ("RUNNING".equals(job.status()) && job.requestedRevision()==job.workingRevision()))
            jobs.queueRevision(job.id(),change.dueAt());
        // QUEUED already incorporates changes in its next snapshot; FAILED requires explicit manual rebuild.
        changes.acknowledge(owner,date); return true;
    }
    public static LocalDateTime dueAt(LocalDate date,Instant now) {
        var cutoff=date.atTime(22,0).atZone(BEIJING).toInstant();
        var supplement=date.plusDays(1).atTime(0,15).atZone(BEIJING).toInstant();
        var due=now.isAfter(cutoff) ? (now.isAfter(supplement) ? now : supplement) : cutoff;
        return LocalDateTime.ofInstant(due,ZoneOffset.UTC);
    }
}
