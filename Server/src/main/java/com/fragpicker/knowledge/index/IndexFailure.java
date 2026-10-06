package com.fragpicker.knowledge.index;

public class IndexFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public IndexFailure(String code, boolean retryable) { super("Semantic indexing failed"); this.code = code; this.retryable = retryable; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
