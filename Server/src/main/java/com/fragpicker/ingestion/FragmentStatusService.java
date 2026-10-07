package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.common.api.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

@Service
@Profile("database")
public class FragmentStatusService {
    private final FragmentRecordMapper fragments;
    private final IngestionJobMapper jobs;
    private final com.fragpicker.ingestion.retry.RetryMapper retries;
    private final com.fragpicker.ingestion.retry.RetryPlanner planner;
    private final TranscriptionMapper tasks;
    public FragmentStatusService(FragmentRecordMapper fragments, IngestionJobMapper jobs,
            com.fragpicker.ingestion.retry.RetryMapper retries, com.fragpicker.ingestion.retry.RetryPlanner planner, TranscriptionMapper tasks) {
        this.fragments = fragments; this.jobs = jobs;
        this.retries=retries;this.planner=planner;this.tasks=tasks;
    }

    @Transactional(readOnly = true)
    public FragmentStatusResponse get(long userId, long id) {
        var fragment = fragments.selectOne(Wrappers.<FragmentRecord>lambdaQuery()
                .eq(FragmentRecord::getId, id).eq(FragmentRecord::getUserId, userId));
        if (fragment == null) throw new ApiException(HttpStatus.NOT_FOUND, "FRAGMENT_NOT_FOUND", "未找到投喂记录");
        var job = jobs.selectOne(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getFragmentId, id).eq(IngestionJob::getUserId, userId));
        if (job == null) throw new IllegalStateException("Fragment has no durable job");
        boolean failed="FAILED".equals(job.getStage());
        var plan=failed ? planner.plan(retries.facts(userId,id),tasks.find(id,userId),job.getErrorCode()) : null;
        return new FragmentStatusResponse(fragment.getId(), fragment.getSourceUrl(), fragment.getSourceHost(),
                fragment.getNote(), fragment.getBusinessDate(), fragment.getBusinessZone(), fragment.getCreatedAt().toInstant(ZoneOffset.UTC),
                job.getStage(), job.getAttemptCount(), job.getErrorCode(),failed,plan==null ? null : plan.stage(),plan!=null && plan.requiresNewTranscription());
    }
}
