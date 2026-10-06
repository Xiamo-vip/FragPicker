package com.fragpicker.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragpicker.integration.oss.MediaKind;
import com.fragpicker.integration.tingwu.TingwuResult;
import com.fragpicker.knowledge.KnowledgeResultMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("database")
public class TranscriptionJobStore {
    private final TranscriptionJobMapper jobs;
    private final TranscriptionMapper transcriptions;
    private final StoredMediaMapper media;
    private final FragmentRecordMapper fragments;
    private final KnowledgeResultMapper knowledge;
    private final TranscriptionWorkerProperties properties;
    private final ObjectMapper json;

    public TranscriptionJobStore(TranscriptionJobMapper jobs, TranscriptionMapper transcriptions, StoredMediaMapper media,
            FragmentRecordMapper fragments, KnowledgeResultMapper knowledge, TranscriptionWorkerProperties properties, ObjectMapper json) {
        properties.validate();
        this.jobs = jobs; this.transcriptions = transcriptions; this.media = media; this.fragments = fragments;
        this.knowledge = knowledge; this.properties = properties; this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TranscriptionLease> claim() {
        var job = jobs.lockNext();
        if (job == null) return Optional.empty();
        var record = transcriptions.find(job.getFragmentId(), job.getUserId());
        // A process may have died after the POST was accepted but before its response was committed.
        if (record != null && record.taskId() == null) {
            finish(job, "FAILED", "TINGWU_SUBMISSION_UNCERTAIN", job.getTranscriptionFailures(), 0);
            return Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        if (jobs.claim(job.getId(), owner, properties.leaseDuration().toSeconds()) != 1) throw new IllegalStateException("Missing transcription job");
        updateFragment(job.getFragmentId(), job.getUserId(), "TRANSCRIPTION_WORKING");
        var lease = new TranscriptionLease(job.getId(), job.getFragmentId(), job.getUserId(), job.getVersion() + 1, owner);
        if (Boolean.TRUE.equals(transcriptions.expired(lease, properties.maxTaskAge().toSeconds()))) {
            finish(job, "FAILED", "TINGWU_TASK_TIMEOUT", job.getTranscriptionFailures(), 0);
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    public TranscriptionRecord task(TranscriptionLease lease) { return transcriptions.find(lease.fragmentId(), lease.userId()); }
    public StoredMediaRecord video(TranscriptionLease lease) {
        var saved = media.find(lease.fragmentId(), lease.userId(), MediaKind.VIDEO);
        if (saved == null) throw new IllegalStateException("Missing private video checkpoint");
        return saved;
    }

    @Transactional
    public Optional<String> beginSubmission(TranscriptionLease lease) {
        if (jobs.lockValid(lease) == null) return Optional.empty();
        if (task(lease) != null) throw new IllegalStateException("Submission intent already exists");
        String key = "frag-" + lease.fragmentId() + "-" + UUID.randomUUID();
        if (transcriptions.insert(lease, key) != 1) throw new IllegalStateException("Cannot persist submission intent");
        return Optional.of(key);
    }

    @Transactional
    public boolean recordTask(TranscriptionLease lease, String taskKey, String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid cloud task ID");
        var job = jobs.lockOwned(lease);
        if (job == null) return false;
        var intent = task(lease);
        if (intent == null || !intent.taskKey().equals(taskKey)) return false;
        if (intent.taskId() != null) return intent.taskId().equals(taskId);
        // A late successful response can reconcile its own uncertain intent even after lease recovery.
        // No newer worker can create another cloud task while this intent exists.
        boolean original = "TRANSCRIPTION_WORKING".equals(job.getStage()) && job.getVersion() == lease.version()
                && lease.owner().equals(job.getLeaseOwner());
        boolean uncertain = "FAILED".equals(job.getStage()) && "TINGWU_SUBMISSION_UNCERTAIN".equals(job.getErrorCode());
        if (!original && !uncertain) return false;
        if (transcriptions.checkpoint(lease, taskKey, taskId) != 1) throw new IllegalStateException("Cannot checkpoint cloud task");
        finish(job, "TRANSCRIBING", null, 0, properties.queryDelay().toSeconds());
        return true;
    }

    @Transactional
    public boolean submissionRejected(TranscriptionLease lease, String code, boolean retryable) {
        var job = jobs.lockValid(lease);
        if (job == null) return false;
        var intent = task(lease);
        if (intent == null || intent.taskId() != null) throw new IllegalStateException("Not an unaccepted submission");
        // Only explicit cloud rejection permits removing an intent and a later POST.
        transcriptions.deleteRejected(lease);
        failLocked(job, lease, code, retryable);
        return true;
    }

    @Transactional
    public boolean ongoing(TranscriptionLease lease) {
        var job = jobs.lockValid(lease);
        if (job == null) return false;
        requireKnownTask(lease);
        finish(job, "TRANSCRIBING", null, 0, properties.queryDelay().toSeconds());
        return true;
    }

    @Transactional
    public boolean fail(TranscriptionLease lease, String code, boolean retryable) {
        var job = jobs.lockValid(lease);
        if (job == null) return false;
        var intent = task(lease);
        if (intent != null && intent.taskId() == null) {
            finish(job, "FAILED", "TINGWU_SUBMISSION_UNCERTAIN", job.getTranscriptionFailures(), 0);
        } else failLocked(job, lease, code, retryable);
        return true;
    }

    @Transactional
    public boolean complete(TranscriptionLease lease, TingwuResult result) {
        var job = jobs.lockValid(lease);
        if (job == null) return false;
        var task = requireKnownTask(lease);
        if (!task.taskId().equals(result.taskId()) || result.durationMs() < 0 || result.sentences().isEmpty()) {
            throw new IllegalArgumentException("Invalid normalized transcription result");
        }
        String keywords;
        try { keywords = json.writeValueAsString(result.keywords()); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot encode keywords"); }
        knowledge.insert(lease.fragmentId(), lease.userId(), result.durationMs(), result.summary(), keywords);
        // Separate bound statements avoid exceeding MySQL's packet limit for large transcripts.
        for (int i = 0; i < result.sentences().size(); i++) knowledge.insertSentence(lease.fragmentId(), lease.userId(), i, result.sentences().get(i));
        for (int i = 0; i < result.keyPoints().size(); i++) knowledge.insertPoint(lease.fragmentId(), lease.userId(), i, result.keyPoints().get(i));
        finish(job, "KNOWLEDGE_PENDING", null, 0, 0);
        return true;
    }

    private TranscriptionRecord requireKnownTask(TranscriptionLease lease) {
        var task = task(lease);
        if (task == null || task.taskId() == null) throw new IllegalStateException("Missing committed cloud task ID");
        return task;
    }
    private void failLocked(IngestionJob job, TranscriptionLease lease, String code, boolean retryable) {
        if (code == null || !code.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("Invalid stable error code");
        int failures = job.getTranscriptionFailures() + 1;
        String next = retryable && failures < properties.maxFailures()
                ? (task(lease) == null ? "TRANSCRIPTION_PENDING" : "TRANSCRIBING") : "FAILED";
        finish(job, next, code, failures, properties.retryDelaySeconds(failures));
    }
    private void finish(IngestionJob job, String stage, String code, int failures, long delay) {
        if (jobs.finish(job.getId(), stage, code, failures, delay) != 1) throw new IllegalStateException("Missing transcription job update");
        updateFragment(job.getFragmentId(), job.getUserId(), stage);
    }
    private void updateFragment(long id, long user, String stage) {
        if (fragments.update(null, Wrappers.<FragmentRecord>lambdaUpdate().eq(FragmentRecord::getId, id)
                .eq(FragmentRecord::getUserId, user).set(FragmentRecord::getStatus, stage)) != 1) {
            throw new IllegalStateException("Missing transcription fragment");
        }
    }
}
