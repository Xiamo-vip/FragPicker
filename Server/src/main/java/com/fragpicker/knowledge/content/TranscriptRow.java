package com.fragpicker.knowledge.content;

public record TranscriptRow(int ordinal, long sentenceId, String speakerId, boolean speakerTruncated, long startMs, long endMs, int length, String text) {
    @Override public String toString() { return "TranscriptRow[text=REDACTED]"; }
}
