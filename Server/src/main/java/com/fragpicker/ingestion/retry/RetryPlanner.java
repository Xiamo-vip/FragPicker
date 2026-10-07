package com.fragpicker.ingestion.retry;

import com.fragpicker.ingestion.TranscriptionRecord;
import org.springframework.stereotype.Component;

@Component
public class RetryPlanner {
    public RetryPlan plan(RetryFacts facts, TranscriptionRecord task, String error) {
        if (facts.enriched()) return new RetryPlan("INDEX_PENDING", false);
        if (facts.knowledge()) return new RetryPlan("KNOWLEDGE_PENDING", false);
        boolean replace = task != null && (task.taskId() == null || terminal(error));
        if (task != null && !replace) return new RetryPlan("TRANSCRIBING", false);
        String stage = !facts.metadata() ? "QUEUED"
                : !facts.video() || facts.coverRequired() && !facts.cover() ? "MEDIA_PENDING" : "TRANSCRIPTION_PENDING";
        return new RetryPlan(stage, replace);
    }
    private boolean terminal(String code) {
        return code != null && (code.equals("TINGWU_NO_SPEECH") || code.startsWith("TINGWU_TASK_") && !code.equals("TINGWU_TASK_TIMEOUT"));
    }
}
