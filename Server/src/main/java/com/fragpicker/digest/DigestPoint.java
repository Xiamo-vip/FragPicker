package com.fragpicker.digest;

import java.util.*;

public record DigestPoint(String text, List<Long> sourceIds) {
    public DigestPoint {
        DigestText.require(text, 200);
        if (sourceIds == null || sourceIds.isEmpty() || sourceIds.size() > 3 || sourceIds.stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(sourceIds).size() != sourceIds.size()) throw new IllegalArgumentException("Invalid digest point");
        sourceIds = List.copyOf(sourceIds);
    }
    @Override public String toString() { return "DigestPoint[content=REDACTED]"; }
}
