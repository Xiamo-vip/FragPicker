package com.fragpicker.ingestion;

import com.fragpicker.integration.oss.*;
import com.fragpicker.integration.tingwu.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class TranscriptionWorker {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionWorker.class);
    private final TranscriptionJobStore store;
    private final OssMediaStorage storage;
    private final OssProperties oss;
    private final TingwuClient client;
    private final TingwuResultReader reader;
    private final TingwuProperties tingwu;

    public TranscriptionWorker(TranscriptionJobStore store, OssMediaStorage storage, OssProperties oss,
            TingwuClient client, TingwuResultReader reader, TingwuProperties tingwu) {
        this.store = store; this.storage = storage; this.oss = oss; this.client = client; this.reader = reader; this.tingwu = tingwu;
    }
    @Scheduled(fixedDelayString = "${fragpicker.ingestion.transcription-worker.poll-delay}",
            initialDelayString = "${fragpicker.ingestion.transcription-worker.poll-delay}")
    public void poll() {
        try { runOnce(); }
        catch (Exception failure) { log.warn("Transcription poll failed ({})", failure.getClass().getSimpleName()); }
    }
    public boolean runOnce() {
        var claimed = store.claim();
        if (claimed.isEmpty()) return false;
        var lease = claimed.get();
        try {
            var previous = store.task(lease);
            if (previous == null) submit(lease);
            else query(lease, previous.taskId());
        } catch (TingwuFailure failure) { store.fail(lease, "TINGWU_" + failure.code().name(), failure.retryable()); }
        catch (OssStorageFailure failure) { store.fail(lease, "OSS_" + failure.code().name(), failure.retryable()); }
        catch (Exception failure) {
            log.warn("Transcription job {} failed internally ({})", lease.jobId(), failure.getClass().getSimpleName());
            store.fail(lease, "TRANSCRIPTION_INTERNAL_ERROR", true);
        }
        return true;
    }
    private void submit(TranscriptionLease lease) {
        var media = store.video(lease);
        if (!oss.bucket().equals(media.bucket())) {
            store.fail(lease, "TRANSCRIPTION_BUCKET_MISMATCH", false); return;
        }
        var signed = storage.signedGetForTranscription(lease.userId(), lease.fragmentId(), media.objectKey(), tingwu.sourceUrlTtl());
        var intent = store.beginSubmission(lease);
        if (intent.isEmpty()) return;
        TingwuTask created;
        try { created = client.create(signed.url(), intent.get()); }
        catch (TingwuFailure failure) {
            if (failure.code() == TingwuFailure.Code.SUBMISSION_UNCERTAIN) store.fail(lease, "TINGWU_SUBMISSION_UNCERTAIN", false);
            else store.submissionRejected(lease, "TINGWU_" + failure.code().name(), failure.retryable());
            return;
        }
        // Failure to commit this ID leaves the durable intent uncertain, never another automatic POST.
        store.recordTask(lease, intent.get(), created.id());
    }
    private void query(TranscriptionLease lease, String taskId) {
        var task = client.get(taskId);
        switch (task.status()) {
            case ONGOING -> store.ongoing(lease);
            case FAILED, INVALID -> store.fail(lease, "TINGWU_TASK_" + (task.failure() == null ? "OTHER" : task.failure().name()), false);
            case COMPLETED -> {
                var result = reader.read(task);
                if (result.sentences().isEmpty()) store.fail(lease, "TINGWU_NO_SPEECH", false);
                else store.complete(lease, result);
            }
        }
    }
}
