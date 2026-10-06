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
    public FragmentStatusService(FragmentRecordMapper fragments, IngestionJobMapper jobs) {
        this.fragments = fragments; this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public FragmentStatusResponse get(long userId, long id) {
        var fragment = fragments.selectOne(Wrappers.<FragmentRecord>lambdaQuery()
                .eq(FragmentRecord::getId, id).eq(FragmentRecord::getUserId, userId));
        if (fragment == null) throw new ApiException(HttpStatus.NOT_FOUND, "FRAGMENT_NOT_FOUND", "未找到投喂记录");
        var job = jobs.selectOne(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getFragmentId, id).eq(IngestionJob::getUserId, userId));
        if (job == null) throw new IllegalStateException("Fragment has no durable job");
        return new FragmentStatusResponse(fragment.getId(), fragment.getSourceUrl(), fragment.getSourceHost(),
                fragment.getNote(), fragment.getBusinessDate(), fragment.getBusinessZone(), fragment.getCreatedAt().toInstant(ZoneOffset.UTC),
                job.getStage(), job.getAttemptCount(), job.getErrorCode());
    }
}
