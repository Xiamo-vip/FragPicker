package com.fragpicker.knowledge;

public record KnowledgeSource(String title, String author, String note, String summary, String keywords) {
    @Override public String toString() { return "KnowledgeSource[content=REDACTED]"; }
}
