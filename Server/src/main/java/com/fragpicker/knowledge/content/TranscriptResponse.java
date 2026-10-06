package com.fragpicker.knowledge.content;

import java.util.List;

public record TranscriptResponse(long fragmentId, Kind kind, boolean transcriptionAvailable, List<Segment> items, Cursor nextCursor) {
    public TranscriptResponse { items = List.copyOf(items); }
    public enum Kind { SENTENCE, KEY_POINT }
    public record Cursor(int ordinal, int offset) { }
    public record Segment(int ordinal, int offset, boolean continuation, long sentenceId, String speakerId, boolean speakerTruncated, long startMs, long endMs, String text) {
        @Override public String toString() { return "TranscriptSegment[text=REDACTED]"; }
    }
}
