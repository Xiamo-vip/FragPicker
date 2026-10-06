package com.fragpicker.knowledge.index;

public record IndexSource(String kind, Integer sourceOrdinal, Long startMs, Long endMs, String content) {
    @Override public String toString() { return "IndexSource[content=REDACTED]"; }
}
