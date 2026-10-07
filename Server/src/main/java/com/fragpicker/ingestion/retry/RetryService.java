package com.fragpicker.ingestion.retry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fragpicker.common.api.ApiException;
import com.fragpicker.ingestion.*;
import com.fragpicker.user.UserAccountMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@Profile("database")
public class RetryService {
    private final UserAccountMapper users;
    private final RetryMapper data;
    private final RetryPlanner planner;
    private final TranscriptionMapper tasks;
    private final FragmentRecordMapper fragments;
    private final com.fragpicker.digest.DigestScheduleStore changes;
    public RetryService(UserAccountMapper users,RetryMapper data,RetryPlanner planner,TranscriptionMapper tasks,
                        FragmentRecordMapper fragments,com.fragpicker.digest.DigestScheduleStore changes) {
        this.users=users;this.data=data;this.planner=planner;this.tasks=tasks;this.fragments=fragments;this.changes=changes;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public RetryResponse retry(long owner,long fragment,String rawKey,RetryRequest input) {
        if (rawKey==null || !rawKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","请使用 UUID 格式的 Idempotency-Key");
        var key=UUID.fromString(rawKey).toString();
        // Fixed-width canonical input: neither secrets nor user text are included in this identity.
        String hash=hash(fragment+":"+input.replaceTranscription());
        var user=users.lockById(owner);
        if (user==null || !Boolean.TRUE.equals(user.getEnabled())) throw error(HttpStatus.UNAUTHORIZED,"SESSION_INVALID","请重新登录");
        var saved=data.find(owner,key); var job=data.lockJob(owner,fragment);
        if (saved!=null) {
            if (saved.fragmentId()!=fragment || !saved.requestHash().equals(hash)) throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","该请求标识已用于不同的重试内容");
            if (job==null) throw error(HttpStatus.NOT_FOUND,"FRAGMENT_NOT_FOUND","未找到投喂记录");
            return new RetryResponse(fragment,job.getStage(),saved.nextStage(),saved.jobVersion(),true);
        }
        if (job==null) throw error(HttpStatus.NOT_FOUND,"FRAGMENT_NOT_FOUND","未找到投喂记录");
        if (!"FAILED".equals(job.getStage())) throw error(HttpStatus.CONFLICT,"RETRY_NOT_FAILED","该投喂正在处理或已完成，请刷新状态");
        var task=tasks.find(fragment,owner); var plan=planner.plan(data.facts(owner,fragment),task,job.getErrorCode());
        if (plan.requiresNewTranscription() && !input.replaceTranscription()) throw error(HttpStatus.CONFLICT,"RETRY_CONFIRM_TRANSCRIPTION","需要确认重新提交转写，可能再次产生费用");
        if (data.coolingDown(owner,fragment)>0) throw error(HttpStatus.TOO_MANY_REQUESTS,"RETRY_COOLDOWN","请稍后再重试此投喂");
        if (data.remember(owner,key,fragment,hash,plan.stage(),input.replaceTranscription(),task,job.getVersion()+1)!=1) throw new IllegalStateException("Missing retry request");
        if (plan.requiresNewTranscription()) data.removeTask(owner,fragment);
        else if (plan.stage().equals("TRANSCRIBING") && data.extendQuery(owner,fragment)!=1) throw new IllegalStateException("Missing retry task");
        if (data.resume(job.getId(),owner,plan.stage())!=1) throw new IllegalStateException("Missing retry job");
        if (fragments.update(null,Wrappers.<FragmentRecord>lambdaUpdate().eq(FragmentRecord::getId,fragment).eq(FragmentRecord::getUserId,owner).set(FragmentRecord::getStatus,plan.stage()))!=1)
            throw new IllegalStateException("Missing retry fragment");
        var record=fragments.selectById(fragment); changes.changed(owner,record.getBusinessDate());
        return new RetryResponse(fragment,plan.stage(),plan.stage(),job.getVersion()+1,false);
    }
    private ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}
