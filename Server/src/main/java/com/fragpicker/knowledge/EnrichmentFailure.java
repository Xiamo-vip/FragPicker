package com.fragpicker.knowledge;

public class EnrichmentFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public EnrichmentFailure(String code, boolean retryable) { super("Knowledge enrichment failed"); this.code = code; this.retryable = retryable; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
