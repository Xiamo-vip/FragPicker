package com.fragpicker.knowledge.index;

import com.fragpicker.knowledge.embedding.LocalEmbeddingService;
import java.util.*;

/** Fixed Unicode windows; all nonblank source text is covered, including supplementary characters. */
public class UnicodeChunker {
    public static final String VERSION = "unicode384-overlap48-v1";
    private static final int SIZE = LocalEmbeddingService.MAX_DOCUMENT_CODEPOINTS, OVERLAP = 48;
    public void append(IndexSource source, List<TextChunk> target, int maxChunks) {
        if (source.content() == null || source.content().isBlank()) return;
        String text = source.content(); int from = 0, remaining = text.codePointCount(0, text.length());
        while (from < text.length()) {
            int to = text.offsetByCodePoints(from, Math.min(SIZE, remaining));
            String part = text.substring(from, to);
            if (!part.isBlank()) {
                if (target.size() >= maxChunks) throw new IndexFailure("INDEX_TOO_LARGE", false);
                target.add(new TextChunk(source.kind(), source.sourceOrdinal(), source.startMs(), source.endMs(), part));
            }
            if (to == text.length()) break;
            from = text.offsetByCodePoints(to, -OVERLAP);
            remaining -= SIZE - OVERLAP;
        }
    }
}
