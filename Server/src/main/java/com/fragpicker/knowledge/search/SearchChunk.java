package com.fragpicker.knowledge.search;

public record SearchChunk(long fragmentId, int ordinal, String sourceKind, Integer sourceOrdinal, Long startMs, Long endMs, String content, byte[] embedding) {
    @Override public String toString() { return "SearchChunk[content=REDACTED, embedding=REDACTED]"; }
}
