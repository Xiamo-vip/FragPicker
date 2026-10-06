package com.fragpicker.integration.tingwu;

import java.util.List;

public record TingwuResult(String taskId, long durationMs, List<Sentence> sentences, String summary,
                           List<String> keywords, List<KeyPoint> keyPoints) {
    public TingwuResult {
        sentences = List.copyOf(sentences); keywords = List.copyOf(keywords); keyPoints = List.copyOf(keyPoints);
    }
    public record Sentence(String paragraphId, String speakerId, long sentenceId, long startMs, long endMs, String text) { }
    public record KeyPoint(long sentenceId, long startMs, long endMs, String text) { }
    @Override public String toString() { return "TingwuResult[sentences=" + sentences.size() + ", content=REDACTED]"; }
}
