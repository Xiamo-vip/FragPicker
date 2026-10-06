package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import java.util.Set;

public record TextChunk(String kind, Integer sourceOrdinal, Long startMs, Long endMs, String content) {
    private static final Set<String> KINDS = Set.of("TITLE", "AUTHOR", "NOTE", "SUMMARY", "AI_SUMMARY", "KEYWORDS", "POINTS", "TRANSCRIPT", "KEY_POINT");
    public TextChunk {
        if (kind == null || !KINDS.contains(kind) || content == null || content.isBlank()
                || content.codePointCount(0, content.length()) > LocalEmbeddingService.MAX_DOCUMENT_CODEPOINTS
                || (sourceOrdinal != null && sourceOrdinal < 0)
                || ((startMs == null) != (endMs == null)) || (startMs != null && (startMs < 0 || endMs < startMs)))
            throw new IllegalArgumentException("Invalid semantic text chunk");
        boolean timed = kind.equals("TRANSCRIPT") || kind.equals("KEY_POINT");
        if (timed != (startMs != null) || timed != (sourceOrdinal != null)) throw new IllegalArgumentException("Invalid chunk attribution");
    }
    @Override public String toString() { return "TextChunk[kind=" + kind + ", content=REDACTED]"; }
}
