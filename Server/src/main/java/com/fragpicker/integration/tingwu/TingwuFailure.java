package com.fragpicker.integration.tingwu;

public class TingwuFailure extends RuntimeException {
    public enum Code { ACCESS_DENIED, INVALID_REQUEST, TASK_NOT_FOUND, THROTTLED, UNAVAILABLE,
        SUBMISSION_UNCERTAIN, INVALID_RESPONSE, RESPONSE_TOO_LARGE, RESULT_EXPIRED, NETWORK_ERROR, TIMEOUT, TASK_NOT_COMPLETED }
    private final Code code;
    private final boolean retryable;
    public TingwuFailure(Code code, boolean retryable) {
        super("Tingwu operation failed: " + code); this.code = code; this.retryable = retryable;
    }
    public Code code() { return code; }
    public boolean retryable() { return retryable; }
}
