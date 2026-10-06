package com.fragpicker.integration.parsevideo;

public class ParseVideoFailure extends RuntimeException {
    public enum Code { INVALID_LINK, PROVIDER_UNAVAILABLE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, UNSUPPORTED_CONTENT, TIMEOUT }
    private final Code code;
    private final boolean retryable;

    public ParseVideoFailure(Code code, boolean retryable) {
        super("Video parsing failed: " + code);
        this.code = code;
        this.retryable = retryable;
    }
    public Code code() { return code; }
    public boolean retryable() { return retryable; }
}
